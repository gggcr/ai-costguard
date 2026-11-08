-- Owned by the assets module.

-- MDM systems (Jamf, Intune) know a device by its serial number, accounting by its inventory number.
alter table asset add column serial_number text;
create unique index asset_serial_number on asset (tenant_id, serial_number) where serial_number is not null;

alter table asset_assignment add unique (tenant_id, id);

-- The handover act of an issue, made in the same transaction as the issue. It is a signed paper's source:
-- the PDF is kept as it was made, so a later change of the employee or the asset does not rewrite it.
create table asset_act (
    id            uuid primary key,
    tenant_id     uuid        not null,
    assignment_id uuid        not null,
    number        text        not null,
    issued_on     date        not null,
    issued_by     text        not null,
    pdf           bytea       not null,
    sha256        char(64)    not null,
    created_at    timestamptz not null,
    unique (tenant_id, number),
    unique (assignment_id),
    foreign key (tenant_id, assignment_id) references asset_assignment (tenant_id, id)
);

-- Act numbers run per tenant and year: АВ-2026-0001. The row lock of the upsert serializes two issues.
create table asset_act_counter (
    tenant_id uuid    not null,
    year      integer not null,
    last      integer not null,
    primary key (tenant_id, year)
);

create function asset_act_is_fixed() returns trigger language plpgsql as $$
begin
    raise exception 'asset act is immutable';
end $$;

create trigger asset_act_fixed before update or delete on asset_act
    for each row execute function asset_act_is_fixed();

alter table asset_act enable row level security;
alter table asset_act force row level security;
create policy tenant_isolation on asset_act
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table asset_act_counter enable row level security;
alter table asset_act_counter force row level security;
create policy tenant_isolation on asset_act_counter
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
