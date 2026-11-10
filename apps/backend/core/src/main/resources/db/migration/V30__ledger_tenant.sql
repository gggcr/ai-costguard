-- Owned by the ledger module (ADR 0040). The companies the counter snapshot visits. Like audit_tenant it has no RLS:
-- the job has to list companies before it can bind one, and the table holds nothing but their ids.
create table ledger_tenant (
    tenant_id uuid primary key
);

create function ledger_tenant_seen() returns trigger language plpgsql as $$
begin
    insert into ledger_tenant (tenant_id) values (new.tenant_id) on conflict do nothing;
    return new;
end;
$$;

create trigger ledger_tenant_seen after insert on budget for each row execute function ledger_tenant_seen();

-- Companies that have budgets already. The copy runs without a tenant, and budget forces RLS even on its owner;
-- Flyway connects as the owner, so for this transaction the owner reads every row.
alter table budget no force row level security;
insert into ledger_tenant (tenant_id) select distinct tenant_id from budget on conflict do nothing;
alter table budget force row level security;
