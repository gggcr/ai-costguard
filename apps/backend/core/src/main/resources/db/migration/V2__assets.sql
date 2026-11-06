-- Owned by the assets module.
create table asset (
    id               uuid primary key,
    tenant_id        uuid           not null,
    inventory_number text           not null,
    model            text           not null,
    cost             numeric(14, 2) not null check (cost >= 0),
    status           text           not null check (status in ('IN_STOCK', 'ASSIGNED', 'RETIRED')),
    unique (tenant_id, inventory_number),
    unique (tenant_id, id)
);

-- employee_id has no foreign key: the employee table belongs to the org module.
create table asset_assignment (
    id          uuid primary key,
    tenant_id   uuid not null,
    asset_id    uuid not null,
    employee_id uuid not null,
    issued_at   date not null,
    return_due  date,
    returned_at date,
    foreign key (tenant_id, asset_id) references asset (tenant_id, id),
    check (returned_at is null or returned_at >= issued_at)
);

-- An asset has at most one current holder.
create unique index asset_assignment_one_open on asset_assignment (asset_id) where returned_at is null;
create index asset_assignment_open_by_employee on asset_assignment (employee_id) where returned_at is null;

alter table asset enable row level security;
alter table asset force row level security;
create policy tenant_isolation on asset
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table asset_assignment enable row level security;
alter table asset_assignment force row level security;
create policy tenant_isolation on asset_assignment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
