-- Вход через Microsoft 365 (ADR 0058). Owned by the org module. A company connects its own Microsoft Entra ID: its
-- people log in with their work account, and Keycloak asks for a one-time code of an authenticator app. The client
-- secret lives only in Keycloak, never here.
create table company_login (
    tenant_id    uuid primary key,
    provider     text        not null check (provider in ('microsoft')),
    directory_id text        not null,
    client_id    text        not null,
    alias        text        not null unique,
    domains      text        not null,
    updated_at   timestamptz not null
);

alter table company_login enable row level security;
alter table company_login force row level security;
create policy tenant_isolation on company_login
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on company_login for select using (current_user = 'costguard_backup');

-- The login page asks for the work e-mail before anyone is signed in: the domain leads to the company's provider.
-- Like audit_tenant it has no RLS: it is read before a tenant is known and holds only domains and ids.
create table login_domain (
    domain    text primary key,
    tenant_id uuid not null,
    alias     text not null
);
