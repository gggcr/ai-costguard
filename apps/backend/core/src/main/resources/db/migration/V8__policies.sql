-- Owned by the policies module. Company-wide rules that apply on top of every agent's mandate.
create table policy_rule (
    id         uuid primary key,
    tenant_id  uuid        not null,
    name       text        not null,
    expression text        not null,
    effect     text        not null check (effect in ('DENY', 'REQUIRE_APPROVAL')),
    enabled    boolean     not null default true,
    updated_at timestamptz not null,
    unique (tenant_id, name)
);

-- The version of what the gateway has. Rules change freely; the gateway sees them only when published.
create table policy_publication (
    tenant_id    uuid primary key,
    version      bigint      not null,
    published_at timestamptz not null
);

alter table policy_rule enable row level security;
alter table policy_rule force row level security;
create policy tenant_isolation on policy_rule
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table policy_publication enable row level security;
alter table policy_publication force row level security;
create policy tenant_isolation on policy_publication
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
