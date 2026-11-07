-- Owned by the catalog module.
-- Prices are per tenant: a company may have its own contract price with a provider.
create table price (
    id         uuid primary key,
    tenant_id  uuid           not null,
    sku_id     text           not null,
    vendor     text           not null,
    model      text,
    unit       text           not null check (unit in ('INPUT_TOKEN', 'OUTPUT_TOKEN', 'REQUEST')),
    unit_price numeric(18, 9) not null check (unit_price >= 0),
    currency   char(3)        not null,
    valid_from date           not null,
    unique (tenant_id, sku_id, valid_from)
);

alter table price enable row level security;
alter table price force row level security;
create policy tenant_isolation on price
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Bank of Russia rates are public and the same for everyone, so this table has no tenant and no RLS.
-- rate is roubles for one unit of the currency.
create table fx_rate (
    rate_date date           not null,
    currency  char(3)        not null,
    rate      numeric(18, 8) not null check (rate > 0),
    primary key (rate_date, currency)
);
