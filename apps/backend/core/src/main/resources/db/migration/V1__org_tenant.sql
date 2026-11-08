-- Owned by the org module.
create table tenant (
    id         uuid primary key,
    name       text        not null,
    currency   char(3)     not null default 'RUB',
    plan       text        not null default 'trial',
    created_at timestamptz not null default now()
);

-- Row-level security: a session sees only the tenant set in app.tenant_id.
-- The same pattern goes on every table with a tenant_id.
alter table tenant enable row level security;
alter table tenant force row level security;

create policy tenant_isolation on tenant
    using (id = nullif(current_setting('app.tenant_id', true), '')::uuid);
