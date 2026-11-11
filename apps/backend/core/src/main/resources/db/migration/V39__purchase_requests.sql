-- Запрос на покупку ПО (ADR 0056). Owned by the cabinet module. An employee asks to buy a program, an API or a
-- subscription, with the invoice or receipt; a license the company already has turns the purchase down at once.
create table purchase_request (
    id                      uuid primary key,
    tenant_id               uuid           not null,
    employee_id             uuid           not null,
    product                 text           not null,
    vendor                  text,
    amount                  numeric(14, 2) not null check (amount >= 0),
    currency                char(3)        not null,
    period                  text           not null check (period in ('once', 'month', 'year')),
    reason                  text,
    file_key                text,
    file_name               text,
    status                  text           not null check (status in ('pending', 'approved', 'rejected',
                                                                      'has_license', 'cancelled')),
    subscription_id         uuid,
    license_request_id      uuid,
    decided_by              text,
    decided_at              timestamptz,
    decision_comment        text,
    created_at              timestamptz    not null,
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);
create index purchase_request_by_employee on purchase_request (tenant_id, employee_id, created_at desc);
create index purchase_request_pending on purchase_request (tenant_id, status) where status = 'pending';

alter table purchase_request enable row level security;
alter table purchase_request force row level security;
create policy tenant_isolation on purchase_request
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
