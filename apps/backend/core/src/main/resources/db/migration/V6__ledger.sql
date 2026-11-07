-- Owned by the ledger module. ref_id points at a department or an agent: those tables belong to other modules.
create table budget (
    id           uuid primary key,
    tenant_id    uuid           not null,
    name         text           not null,
    level        text           not null check (level in ('company', 'department', 'agent')),
    ref_id       uuid,
    parent_id    uuid,
    limit_amount numeric(18, 6) not null check (limit_amount >= 0),
    currency     char(3)        not null,
    version      bigint         not null default 1,
    unique (tenant_id, id),
    foreign key (tenant_id, parent_id) references budget (tenant_id, id),
    check ((level = 'company') = (parent_id is null)),
    check ((level = 'company') = (ref_id is null))
);

create unique index budget_one_company on budget (tenant_id) where level = 'company';
create unique index budget_one_per_ref on budget (tenant_id, level, ref_id) where ref_id is not null;

-- An operation is posted once per budget and kind, so a redelivered Kafka message changes nothing.
create table ledger_entry (
    id           uuid primary key,
    tenant_id    uuid           not null,
    budget_id    uuid           not null,
    operation_id uuid           not null,
    kind         text           not null check (kind in ('charge', 'reversal')),
    amount       numeric(18, 6) not null,
    occurred_at  timestamptz    not null,
    foreign key (tenant_id, budget_id) references budget (tenant_id, id),
    unique (budget_id, operation_id, kind),
    check ((kind = 'charge') = (amount >= 0))
);

create index ledger_entry_by_operation on ledger_entry (operation_id);

-- The ledger is append-only: a mistake is corrected by a reversal, never by editing history.
create function ledger_entry_is_append_only() returns trigger language plpgsql as $$
begin
    raise exception 'ledger_entry is append-only';
end $$;

create trigger ledger_entry_append_only before update or delete on ledger_entry
    for each row execute function ledger_entry_is_append_only();

alter table budget enable row level security;
alter table budget force row level security;
create policy tenant_isolation on budget
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table ledger_entry enable row level security;
alter table ledger_entry force row level security;
create policy tenant_isolation on ledger_entry
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
