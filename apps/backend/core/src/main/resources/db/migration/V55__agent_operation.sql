-- The operation journal of installs without ClickHouse (ADR 0080): every decision of the gateway, as ClickHouse's
-- agent_operations keeps it for the others. One row per operation; a later decision on the same operation (allowed
-- after an approval) replaces the earlier one, as ReplacingMergeTree(occurred_at) does there. Owned by the journal
-- module. No personal data: ids, amounts, the provider and the model.
create table agent_operation (
    tenant_id      uuid           not null,
    operation_id   uuid           not null,
    agent_id       uuid           not null,
    occurred_at    timestamptz    not null,
    kind           text           not null,
    provider       text           not null,
    model          text,
    decision       text           not null,
    denial_code    text,
    estimated_cost numeric(18, 6) not null,
    actual_cost    numeric(18, 6),
    currency       text           not null,
    input_tokens   bigint,
    output_tokens  bigint,
    primary key (tenant_id, operation_id)
);

create index agent_operation_latest on agent_operation (tenant_id, occurred_at desc);

alter table agent_operation enable row level security;
alter table agent_operation force row level security;
create policy tenant_isolation on agent_operation
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on agent_operation for select using (current_user = 'costguard_backup');
