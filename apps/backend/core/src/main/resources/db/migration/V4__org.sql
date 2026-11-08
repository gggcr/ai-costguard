-- Owned by the org module.
create table department (
    id        uuid primary key,
    tenant_id uuid not null,
    name      text not null,
    unique (tenant_id, name),
    unique (tenant_id, id)
);

create table employee (
    id            uuid primary key,
    tenant_id     uuid not null,
    department_id uuid not null,
    name          text not null,
    email         text not null,
    position      text not null,
    dismissed_at  date,
    unique (tenant_id, email),
    unique (tenant_id, id),
    foreign key (tenant_id, department_id) references department (tenant_id, id)
);

create table user_account (
    id          uuid primary key,
    tenant_id   uuid not null,
    name        text not null,
    email       text not null,
    role        text not null check (role in ('owner', 'finance', 'it_admin', 'agent_owner', 'auditor')),
    employee_id uuid,
    unique (tenant_id, email),
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);

create index employee_by_department on employee (department_id);

alter table department enable row level security;
alter table department force row level security;
create policy tenant_isolation on department
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table employee enable row level security;
alter table employee force row level security;
create policy tenant_isolation on employee
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table user_account enable row level security;
alter table user_account force row level security;
create policy tenant_isolation on user_account
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
