-- Owned by the connections module (ADR 0019): the systems a company connected and the pulls the connector agent
-- reported. One connection per system type and tenant: the agent keeps its cursor per type.
create table connection (
    id                uuid primary key,
    tenant_id         uuid        not null,
    type              text        not null,
    name              text        not null,
    enabled           boolean     not null,
    -- false for a system the agent reported but that was configured only in its file, not in the core
    managed           boolean     not null,
    settings          jsonb       not null default '{}',
    sync_requested_at timestamptz,
    created_at        timestamptz not null,
    updated_at        timestamptz not null,
    unique (tenant_id, type),
    unique (tenant_id, id)
);

create table connection_run (
    id           uuid primary key,
    tenant_id    uuid        not null,
    connection_id uuid       not null,
    started_at   timestamptz not null,
    finished_at  timestamptz not null,
    status       text        not null check (status in ('SUCCEEDED', 'FAILED')),
    records      int         not null,
    error        text,
    foreign key (tenant_id, connection_id) references connection (tenant_id, id) on delete cascade
);

create index connection_run_latest on connection_run (connection_id, finished_at desc);

alter table connection enable row level security;
alter table connection force row level security;
create policy tenant_isolation on connection
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table connection_run enable row level security;
alter table connection_run force row level security;
create policy tenant_isolation on connection_run
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
