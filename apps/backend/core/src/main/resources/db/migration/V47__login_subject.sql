-- Owned by the org module (accounts) and the cabinet module (signing keys). A person is known by the subject of
-- their Keycloak account (the token's sub), which they cannot change, and no longer by the e-mail of the token,
-- which a person could change in their Keycloak account (the e-mail spoofing finding of 2026-11-12).

alter table user_account add column keycloak_subject text;
-- One Keycloak account is one CostGuard account. Many rows may still have none (NULLs are distinct).
create unique index user_account_keycloak_subject on user_account (keycloak_subject);

-- Existing accounts take the subject only from records the core itself wrote when it created the Keycloak account:
-- the invitation (keycloak_user_id) and the self-registration of the owner. Nothing is matched by the e-mail of a
-- token. An account without such a record keeps NULL until its person signs in with a confirmed e-mail
-- (FirstLogin). The updates run without a tenant and the tables force RLS even on their owner; Flyway connects as
-- the owner, so for this transaction the owner reads every row (as in V26), and the policies come back at the end.
alter table user_account no force row level security;
alter table invitation no force row level security;
alter table registration no force row level security;
alter table employee no force row level security;

-- The newest invitation of each account decides, and only if it was not revoked: revoking deletes the Keycloak
-- account and withdraws the access, so an older invitation does not bring it back. Several invitations made at the
-- same newest moment decide only if they name one Keycloak account; nothing is chosen by chance. A subject is taken
-- only if no other account claims it.
with newest as (
    select i.user_account_id, i.tenant_id, i.keycloak_user_id, i.status
    from invitation i
    where i.created_at = (select max(j.created_at) from invitation j where j.user_account_id = i.user_account_id)
), latest as (
    select user_account_id, min(tenant_id::text)::uuid as tenant_id, min(keycloak_user_id) as keycloak_user_id
    from newest
    group by user_account_id
    having count(distinct keycloak_user_id) = 1 and count(distinct tenant_id) = 1
       and bool_and(status <> 'revoked') and min(keycloak_user_id) <> ''
), single as (
    select keycloak_user_id from latest group by keycloak_user_id having count(*) = 1
)
update user_account u set keycloak_subject = l.keycloak_user_id
from latest l
where l.user_account_id = u.id and l.tenant_id = u.tenant_id and u.keycloak_subject is null
  and l.keycloak_user_id in (select keycloak_user_id from single);

-- The owner the registration created, in the same company and with the address the core stored for both.
with owners as (
    select u.id, r.keycloak_user_id
    from registration r
    join user_account u on u.tenant_id = r.tenant_id and lower(u.email) = lower(r.email) and u.role = 'owner'
    where u.keycloak_subject is null and r.keycloak_user_id is not null and r.keycloak_user_id <> ''
), single as (
    select keycloak_user_id from owners group by keycloak_user_id having count(*) = 1
)
update user_account u set keycloak_subject = o.keycloak_user_id
from owners o
where o.id = u.id and o.keycloak_user_id in (select keycloak_user_id from single)
  and not exists (select 1 from user_account x where x.keycloak_subject = o.keycloak_user_id);

-- The cabinet used to find the employee of an account by the e-mail of the token. It now reads only
-- user_account.employee_id, so an account that relied on the address takes the link here, only where the address
-- names one person both ways: exactly one account of the company has it (linked or not) and exactly one working
-- employee has it (ignoring case, as the application compares; stored addresses are not trimmed, as the application
-- never trims them), the account has no employee yet and no account is linked to that employee. Both addresses are
-- the company's data, never the token's. An existing link is never replaced, and no employee gains a second account.
with working as (
    select e.id, e.tenant_id, lower(e.email) as address
    from employee e
    where e.dismissed_at is null and e.erased_at is null
), pairs as (
    select u.id as user_id, w.id as employee_id
    from user_account u
    join working w on w.tenant_id = u.tenant_id and w.address = lower(u.email)
    where u.employee_id is null
      and (select count(*) from working w2 where w2.tenant_id = u.tenant_id and w2.address = lower(u.email)) = 1
      and (select count(*) from user_account u2
           where u2.tenant_id = u.tenant_id and lower(u2.email) = lower(u.email)) = 1
      and not exists (select 1 from user_account x where x.employee_id = w.id)
)
update user_account u set employee_id = p.employee_id
from pairs p
where p.user_id = u.id;

alter table user_account force row level security;
alter table invitation force row level security;
alter table registration force row level security;
alter table employee force row level security;

-- Signing keys (ADR 0068) belong to an account, not to an e-mail. Existing keys are not moved to an account: one
-- set under an address could have been set by someone else who had put that address on their Keycloak account, and
-- it would then lock the real person out. Their rows stay (user_account_id NULL) and sign nothing; the person sets
-- a key again. The e-mail column stays: it names the key's address and is the AAD of its sealed secret.
alter table signing_key add column user_account_id uuid references user_account (id) on delete cascade;
alter table signing_key drop constraint signing_key_pkey;
alter table signing_key add constraint signing_key_account unique (user_account_id);

-- Who signed, by account; NULL for the signatures made before. A signature is a legal record (ADR 0068): it outlives
-- the account, which then reads NULL, while the signer's name and address stay in the row.
alter table document_signature add column signer_account_id uuid references user_account (id) on delete set null;
