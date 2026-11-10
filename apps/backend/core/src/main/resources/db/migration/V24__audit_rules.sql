-- Six more audit rules (ADR 0032).

-- Owned by the software module. The plans a product is sold on, as the company buys them: the price of a month and
-- of a year, per seat or for the whole plan, and how many users a plan allows. The rules "oversized plan" and
-- "monthly instead of yearly" read it. product is matched to subscription.product by the cleaned name.
create table product_plan (
    id          uuid primary key,
    tenant_id   uuid           not null,
    vendor_id   uuid           not null,
    product     text           not null,
    name        text           not null,
    per_seat    boolean        not null,
    max_users   int check (max_users > 0),
    price_month numeric(18, 2) check (price_month >= 0),
    price_year  numeric(18, 2) check (price_year >= 0),
    unique (tenant_id, vendor_id, product, name),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id),
    check (price_month is not null or price_year is not null)
);

-- The plan of a subscription, by name; null when nobody said.
alter table subscription add column plan text;

alter table product_plan enable row level security;
alter table product_plan force row level security;
create policy tenant_isolation on product_plan
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on product_plan for select using (current_user = 'costguard_backup');

-- A company that has only uploaded a bank statement or invoices is audited too: the shadow SaaS and the AI spend
-- past the gateway are found in payments alone.
create trigger audit_tenant_seen after insert on ingest_entity for each row execute function audit_tenant_seen();
