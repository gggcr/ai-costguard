-- Owned by the agents module. department_id and owner_id have no foreign key: those tables belong to org.
create table mandate (
    id                 uuid primary key,
    tenant_id          uuid           not null,
    name               text           not null,
    models             text[]         not null,
    vendors            text[]         not null,
    max_per_operation  numeric(18, 6) not null check (max_per_operation >= 0),
    approval_threshold numeric(18, 6) not null check (approval_threshold >= 0),
    hours              text           not null,
    status             text           not null check (status in ('draft', 'published')),
    version            int            not null default 0,
    -- The terms in force. They change only on publication, so editing a draft does not touch running agents.
    published_expression text,
    published_threshold  numeric(18, 6),
    updated_at         timestamptz    not null,
    unique (tenant_id, id),
    unique (tenant_id, name)
);

create table agent (
    id            uuid primary key,
    tenant_id     uuid        not null,
    name          text        not null,
    description   text        not null,
    framework     text        not null,
    department_id uuid        not null,
    owner_id      uuid        not null,
    mandate_id    uuid        not null,
    status        text        not null check (status in ('active', 'suspended')),
    risk_score    int         not null default 0 check (risk_score between 0 and 100),
    risk_reason   text        not null default '',
    created_at    timestamptz not null,
    unique (tenant_id, id),
    foreign key (tenant_id, mandate_id) references mandate (tenant_id, id)
);

-- Only the hash is stored: the secret is shown once, when the key is issued.
create table virtual_key (
    id         uuid primary key,
    tenant_id  uuid        not null,
    agent_id   uuid        not null,
    prefix     text        not null,
    hash       text        not null unique,
    status     text        not null check (status in ('active', 'revoked')),
    created_at timestamptz not null,
    foreign key (tenant_id, agent_id) references agent (tenant_id, id) on delete cascade
);

create index agent_by_mandate on agent (mandate_id);
create index virtual_key_by_agent on virtual_key (agent_id);

alter table mandate enable row level security;
alter table mandate force row level security;
create policy tenant_isolation on mandate
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table agent enable row level security;
alter table agent force row level security;
create policy tenant_isolation on agent
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table virtual_key enable row level security;
alter table virtual_key force row level security;
create policy tenant_isolation on virtual_key
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
