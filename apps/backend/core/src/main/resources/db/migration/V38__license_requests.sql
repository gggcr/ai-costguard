-- Пул лицензий (ADR 0055). Owned by the cabinet module. An employee asks for a program the company subscribes to;
-- a free seat is given at once, otherwise IT decides, and an idle license of a colleague may go back to the pool for
-- them. No lending between people and no points: a license is reassigned.
create table license_request (
    id              uuid primary key,
    tenant_id       uuid        not null,
    employee_id     uuid        not null,
    subscription_id uuid        not null,
    reason          text,
    status          text        not null check (status in ('granted', 'pending', 'approved', 'rejected', 'cancelled')),
    idle_license_id uuid,
    decided_by      text,
    decided_at      timestamptz,
    decision_comment text,
    created_at      timestamptz not null,
    foreign key (tenant_id, employee_id) references employee (tenant_id, id),
    foreign key (tenant_id, subscription_id) references subscription (tenant_id, id)
);
create index license_request_by_employee on license_request (tenant_id, employee_id, created_at desc);
create index license_request_pending on license_request (tenant_id, status) where status = 'pending';

alter table license_request enable row level security;
alter table license_request force row level security;
create policy tenant_isolation on license_request
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
