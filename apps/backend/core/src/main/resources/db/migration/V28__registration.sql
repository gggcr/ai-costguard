-- Owned by the org module: self-registration and invitations (ADR 0036).

-- A self-registered company is pending until its owner confirms the e-mail; companies before that are active.
alter table tenant add column country char(2);
alter table tenant add column status text not null default 'active' check (status in ('pending', 'active'));

-- The owner's account of a registration, until the e-mail is confirmed or the registration is dropped.
create table registration (
    tenant_id        uuid primary key,
    keycloak_user_id text        not null,
    email            text        not null,
    created_at       timestamptz not null,
    confirmed_at     timestamptz
);

alter table registration enable row level security;
alter table registration force row level security;
create policy tenant_isolation on registration
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on registration for select using (current_user = 'costguard_backup');

-- When an unconfirmed registration is dropped. Read across companies by the sweeper: ids and dates only, no RLS
-- (ADR 0002).
create table registration_timer (
    tenant_id  uuid primary key,
    expires_at timestamptz not null
);

create index registration_timer_due on registration_timer (expires_at);

-- Registration attempts for the rate limit: a SHA-256 of the client address or the e-mail, never the value itself.
-- No company: an attempt belongs to nobody yet. Kept for the rate-limit window only.
create table registration_attempt (
    kind     text        not null check (kind in ('ip', 'email')),
    key_hash text        not null,
    at       timestamptz not null
);

create index registration_attempt_by_key on registration_attempt (kind, key_hash, at);

-- An invitation of a person to the company's CostGuard: their account and role, until they set a password.
create table invitation (
    id               uuid primary key,
    tenant_id        uuid        not null,
    user_account_id  uuid        not null references user_account (id) on delete cascade,
    email            text        not null,
    role             text        not null check (role in ('owner', 'finance', 'it_admin', 'agent_owner', 'auditor')),
    keycloak_user_id text        not null,
    status           text        not null check (status in ('sent', 'accepted', 'revoked')),
    created_at       timestamptz not null,
    accepted_at      timestamptz
);

create index invitation_by_email on invitation (tenant_id, lower(email));

alter table invitation enable row level security;
alter table invitation force row level security;
create policy tenant_isolation on invitation
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on invitation for select using (current_user = 'costguard_backup');
