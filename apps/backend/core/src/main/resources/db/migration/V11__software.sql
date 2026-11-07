-- Owned by the software module. department_id and employee_id have no foreign key: those tables belong to org.
create table vendor (
    id        uuid primary key,
    tenant_id uuid not null,
    name      text not null,
    inn       text not null default '',
    category  text not null default '',
    unique (tenant_id, id),
    unique (tenant_id, name)
);

create table contract (
    id          uuid primary key,
    tenant_id   uuid           not null,
    vendor_id   uuid           not null,
    number      text           not null,
    start_date  date           not null,
    end_date    date           not null,
    notice_days int            not null check (notice_days >= 0),
    auto_renew  boolean        not null,
    amount      numeric(18, 2) not null check (amount >= 0),
    unique (tenant_id, id),
    unique (tenant_id, vendor_id, number),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id),
    check (end_date >= start_date)
);

create table subscription (
    id             uuid primary key,
    tenant_id      uuid           not null,
    vendor_id      uuid           not null,
    product        text           not null,
    contract_id    uuid,
    department_id  uuid           not null,
    seats          int            not null check (seats >= 0),
    price_per_seat numeric(18, 2) not null check (price_per_seat >= 0),
    billing_period text           not null check (billing_period in ('month', 'year')),
    renewal_date   date           not null,
    unique (tenant_id, id),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id),
    foreign key (tenant_id, contract_id) references contract (tenant_id, id)
);

create table license_assignment (
    id              uuid primary key,
    tenant_id       uuid not null,
    subscription_id uuid not null,
    employee_id     uuid not null,
    last_active     date,
    foreign key (tenant_id, subscription_id) references subscription (tenant_id, id),
    unique (subscription_id, employee_id)
);

create index license_assignment_by_employee on license_assignment (employee_id);

alter table vendor enable row level security;
alter table vendor force row level security;
create policy tenant_isolation on vendor
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table contract enable row level security;
alter table contract force row level security;
create policy tenant_isolation on contract
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table subscription enable row level security;
alter table subscription force row level security;
create policy tenant_isolation on subscription
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table license_assignment enable row level security;
alter table license_assignment force row level security;
create policy tenant_isolation on license_assignment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
