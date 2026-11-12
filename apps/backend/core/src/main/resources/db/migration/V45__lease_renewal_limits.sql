-- Owned by the cabinet module (ADR 0066, 0067).
-- Аренда лицензии на срок: a person asks for a program for N days; when the days are over the seat goes back, and a
-- license taken from an idle colleague goes back to that colleague. license_id is the borrower's
-- license_assignment; it has no foreign key: that table belongs to the software module.
alter table license_request add column days int check (days between 1 and 90);

create table license_lease (
    license_id      uuid primary key,
    tenant_id       uuid        not null,
    subscription_id uuid        not null,
    employee_id     uuid        not null,
    lender_id       uuid,
    ends_on         date        not null,
    reminded        boolean     not null default false,
    created_at      timestamptz not null
);
create index license_lease_due on license_lease (tenant_id, ends_on);

-- Напоминание перед автопродлением: one per license and renewal date.
create table renewal_reminder (
    tenant_id    uuid        not null,
    license_id   uuid        not null,
    renewal_date date        not null,
    sent_at      timestamptz not null,
    primary key (license_id, renewal_date)
);

-- Продление лимита агента: the owner asks, the head of their department (or owner/finance) decides.
create table limit_request (
    id            uuid primary key,
    tenant_id     uuid           not null,
    employee_id   uuid           not null,
    agent_id      uuid           not null,
    budget_id     uuid           not null,
    current_limit numeric(18, 6) not null,
    spent         numeric(18, 6) not null,
    add_amount    numeric(18, 6) not null check (add_amount > 0),
    currency      char(3)        not null,
    reason        text           not null,
    status        text           not null check (status in ('pending', 'approved', 'rejected', 'cancelled')),
    decided_by    text,
    decided_at    timestamptz,
    decision_comment text,
    created_at    timestamptz    not null,
    foreign key (tenant_id, employee_id) references employee (tenant_id, id)
);
create index limit_request_by_employee on limit_request (tenant_id, employee_id, created_at desc);
create index limit_request_pending on limit_request (tenant_id, status) where status = 'pending';

-- The tenants the cabinet's morning jobs visit; like audit_tenant it has no RLS and holds nothing but ids.
create table cabinet_tenant (
    tenant_id uuid primary key
);
insert into cabinet_tenant (tenant_id) select tenant_id from audit_tenant on conflict do nothing;

create function cabinet_tenant_seen() returns trigger language plpgsql as $$
begin
    insert into cabinet_tenant (tenant_id) values (new.tenant_id) on conflict do nothing;
    return new;
end;
$$;
create trigger cabinet_tenant_seen after insert on license_assignment
    for each row execute function cabinet_tenant_seen();

alter table license_lease enable row level security;
alter table license_lease force row level security;
create policy tenant_isolation on license_lease
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on license_lease for select using (current_user = 'costguard_backup');

alter table renewal_reminder enable row level security;
alter table renewal_reminder force row level security;
create policy tenant_isolation on renewal_reminder
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on renewal_reminder for select using (current_user = 'costguard_backup');

alter table limit_request enable row level security;
alter table limit_request force row level security;
create policy tenant_isolation on limit_request
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on limit_request for select using (current_user = 'costguard_backup');
