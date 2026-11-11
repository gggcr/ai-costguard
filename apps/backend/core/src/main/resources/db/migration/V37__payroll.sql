-- Зарплата (ADR 0054). Owned by the cabinet module. Pay never goes to Kafka or ClickHouse: it reaches the core over
-- REST (a file of finance, the 1С:ЗУП connector) and only the employee and owner or finance read it.

-- The days of the month the company pays: the advance and the salary of the previous month.
create table payroll_schedule (
    tenant_id   uuid primary key,
    advance_day integer     not null check (advance_day between 1 and 31),
    salary_day  integer     not null check (salary_day between 1 and 31),
    updated_at  timestamptz not null
);

-- One payment to one employee. A file or the connector sending the same payment again replaces it.
create table payroll_payment (
    id           uuid primary key,
    tenant_id    uuid           not null,
    employee_id  uuid           not null,
    kind         text           not null check (kind in ('advance', 'salary', 'vacation_pay', 'bonus', 'other')),
    period       date           not null,
    pay_date     date           not null,
    amount       numeric(14, 2) not null check (amount >= 0),
    currency     char(3)        not null,
    source       text           not null,
    external_key text,
    notified     boolean        not null default false,
    created_at   timestamptz    not null,
    unique (tenant_id, employee_id, kind, period, pay_date),
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);
create index payroll_by_employee on payroll_payment (tenant_id, employee_id, pay_date desc);
create index payroll_due on payroll_payment (tenant_id, pay_date) where not notified;

-- The tenants the pay-day job visits; like audit_tenant it has no RLS and holds nothing but ids.
create table payroll_tenant (
    tenant_id uuid primary key
);

alter table payroll_schedule enable row level security;
alter table payroll_schedule force row level security;
create policy tenant_isolation on payroll_schedule
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table payroll_payment enable row level security;
alter table payroll_payment force row level security;
create policy tenant_isolation on payroll_payment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
