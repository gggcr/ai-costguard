-- SCIM 2.0 server (ADR 0043), owned by the org module.

-- The bearer tokens an identity provider (Okta, Entra ID, Keycloak...) sends to /scim/v2. Only the SHA-256 of a
-- token is kept. Like audit_tenant it has no RLS: the request names no company until its token is found, and the
-- table holds only hashes, ids and dates.
create table scim_token (
    id                    uuid primary key,
    tenant_id             uuid        not null,
    name                  text        not null,
    token_hash            text        not null unique,
    -- what active = false means: true dismisses the employee, false only marks the account switched off
    dismiss_on_deactivate boolean     not null default false,
    created_at            timestamptz not null,
    last_used_at          timestamptz,
    revoked_at            timestamptz
);

create index scim_token_by_tenant on scim_token (tenant_id);

-- How the identity provider knows an employee: its own id (externalId) and the userName, and whether it switched
-- the account off. An employee made by hand has no row until the provider links it.
create table scim_user (
    tenant_id   uuid        not null,
    employee_id uuid        primary key,
    external_id text,
    user_name   text        not null,
    active      boolean     not null default true,
    created_at  timestamptz not null,
    updated_at  timestamptz not null,
    unique (tenant_id, user_name),
    foreign key (employee_id) references employee (id)
);

alter table scim_user enable row level security;
alter table scim_user force row level security;
create policy tenant_isolation on scim_user
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on scim_user for select using (current_user = 'costguard_backup');
