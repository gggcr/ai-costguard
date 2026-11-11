-- Отпуска (ADR 0053). The head of a department decides on the vacations of its people.
alter table department add column head_employee_id uuid;
alter table department add constraint department_head foreign key (tenant_id, head_employee_id)
    references employee (tenant_id, id);

-- Owned by the cabinet module. A vacation request of an employee and the decision on it.
create table vacation_request (
    id                   uuid primary key,
    tenant_id            uuid        not null,
    employee_id          uuid        not null,
    kind                 text        not null check (kind in ('annual', 'unpaid', 'study', 'other')),
    starts_on            date        not null,
    ends_on              date        not null,
    days                 integer     not null check (days > 0),
    comment              text,
    status               text        not null check (status in ('pending', 'approved', 'rejected', 'cancelled')),
    decided_by           text,
    decided_at           timestamptz,
    decision_comment     text,
    created_at           timestamptz not null,
    check (ends_on >= starts_on),
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);
create index vacation_by_employee on vacation_request (tenant_id, employee_id, starts_on desc);
create index vacation_pending on vacation_request (tenant_id, status) where status = 'pending';

alter table vacation_request enable row level security;
alter table vacation_request force row level security;
create policy tenant_isolation on vacation_request
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
