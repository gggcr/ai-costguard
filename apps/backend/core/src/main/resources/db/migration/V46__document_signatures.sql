-- Подписание документов простой электронной подписью (ADR 0068). Owned by the cabinet module.
-- signers: who must sign a document — employee, employer (role owner), accountant (role finance).
alter table employee_document add column signers text[] not null default '{}';

create table document_signature (
    id              uuid primary key,
    tenant_id       uuid        not null,
    document_id     uuid        not null references employee_document (id) on delete cascade,
    party           text        not null check (party in ('employee', 'employer', 'accountant')),
    signer_name     text        not null,
    signer_email    text        not null,
    method          text        not null check (method in ('totp', 'pin')),
    document_sha256 text        not null,
    signed_at       timestamptz not null,
    unique (document_id, party)
);
create index document_signature_by_document on document_signature (document_id);

-- The signing key of a person, by the e-mail of their sign-in: a TOTP secret (sealed, AES-256-GCM) or a PIN hash
-- (bcrypt). last_step stops a code from being used twice; five wrong codes lock signing for 15 minutes.
create table signing_key (
    tenant_id      uuid        not null,
    email          text        not null,
    method         text        check (method in ('totp', 'pin')),
    totp_secret    bytea,
    pending_secret bytea,
    pin_hash       text,
    last_step      bigint,
    failed         int         not null default 0,
    locked_until   timestamptz,
    updated_at     timestamptz not null,
    primary key (tenant_id, email)
);

alter table document_signature enable row level security;
alter table document_signature force row level security;
create policy tenant_isolation on document_signature
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on document_signature for select using (current_user = 'costguard_backup');

alter table signing_key enable row level security;
alter table signing_key force row level security;
create policy tenant_isolation on signing_key
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on signing_key for select using (current_user = 'costguard_backup');
