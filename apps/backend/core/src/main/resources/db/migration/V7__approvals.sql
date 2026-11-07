-- Owned by the approvals module. operation_id and agent_id have no foreign key: operations live in ClickHouse
-- and agents belong to another module.
create table approval_request (
    id           uuid primary key,
    tenant_id    uuid           not null,
    operation_id uuid           not null,
    agent_id     uuid           not null,
    description  text           not null,
    amount       numeric(18, 6) not null check (amount >= 0),
    reason       text           not null,
    status       text           not null check (status in ('pending', 'approved', 'rejected', 'expired')),
    created_at   timestamptz    not null,
    expires_at   timestamptz    not null,
    escalated_at timestamptz,
    decided_by   text,
    comment      text,
    unique (tenant_id, operation_id)
);

create index approval_request_by_status on approval_request (status, created_at desc);

alter table approval_request enable row level security;
alter table approval_request force row level security;
create policy tenant_isolation on approval_request
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The timer serves every tenant, but RLS shows it none. This table has no RLS on purpose, so it holds only ids
-- and times and no business data. The timer reads what is due here and acts inside each tenant's own session.
create table approval_timer (
    approval_id uuid primary key,
    tenant_id   uuid        not null,
    escalate_at timestamptz,
    expires_at  timestamptz not null
);

create index approval_timer_due on approval_timer (expires_at);
