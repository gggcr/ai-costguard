-- Owned by the cabinet module (ADR 0054). A payment of a pay sheet or of the connector that names no employee of the
-- company for sure: the address names none, or several (they differ in case only), or is not an address. It is paid
-- to nobody, and kept here with its reason for finance to sort out (finding R1 of 2026-11-12): before, the core took
-- one of the employees whose address matched by chance. The row keeps what the source reported, as it reported it.
create table payroll_unmatched (
    id             uuid primary key,
    tenant_id      uuid           not null,
    -- the same payment reported again (the same sheet loaded twice, the connector retrying) is one row
    fingerprint    text           not null,
    source         text           not null,
    reported_email text,
    reported_name  text,
    kind           text           not null check (kind in ('advance', 'salary', 'vacation_pay', 'bonus', 'other')),
    period         date           not null,
    pay_date       date           not null,
    amount         numeric(14, 2) not null check (amount >= 0),
    currency       char(3)        not null,
    external_key   text,
    reason         text           not null check (reason in ('ambiguous employee', 'employee not found', 'bad email')),
    -- how many employees the address named: 0, or 2 and more when ambiguous
    candidates     int            not null default 0,
    first_seen     timestamptz    not null,
    last_seen      timestamptz    not null,
    unique (tenant_id, fingerprint)
);
create index payroll_unmatched_by_seen on payroll_unmatched (tenant_id, last_seen desc);

alter table payroll_unmatched enable row level security;
alter table payroll_unmatched force row level security;
create policy tenant_isolation on payroll_unmatched
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on payroll_unmatched for select using (current_user = 'costguard_backup');
