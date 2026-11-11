-- «Мои документы» (ADR 0052). A notification may belong to one employee: their cabinet feed, never the company's.
alter table notification add column employee_id uuid;
create index notification_by_employee on notification (tenant_id, employee_id, at desc) where employee_id is not null;

-- Owned by the cabinet module. Documents the employer gives an employee: contract, agreements, orders. The file
-- lives in the company's storage (S3, ADR 0049) under <tenant>/documents/<id>; handover acts stay in asset_act and
-- are listed next to these.
create table employee_document (
    id           uuid primary key,
    tenant_id    uuid        not null,
    employee_id  uuid        not null,
    kind         text        not null check (kind in ('employment_contract', 'agreement', 'order', 'other')),
    title        text        not null,
    number       text,
    signed_on    date,
    file_key     text        not null,
    file_name    text        not null,
    content_type text        not null,
    size         bigint      not null,
    uploaded_by  text        not null,
    created_at   timestamptz not null,
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);
create index employee_document_by_employee on employee_document (tenant_id, employee_id, created_at desc);

alter table employee_document enable row level security;
alter table employee_document force row level security;
create policy tenant_isolation on employee_document
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
