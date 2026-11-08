-- Owned by the findings module.
create table finding (
    id               uuid primary key,
    tenant_id        uuid           not null,
    fingerprint      text           not null,
    rule             text           not null,
    title            text           not null,
    description      text           not null,
    subscription_id  uuid,
    agent_id         uuid,
    expected_savings numeric(18, 2) not null check (expected_savings >= 0),
    status           text           not null check (status in ('new', 'review', 'accepted', 'in_progress', 'confirmed',
                                                               'rejected', 'postponed', 'auto_closed')),
    owner_id         uuid,
    postponed_until  date,
    reject_reason    text,
    missed_invoices  int            not null default 0,
    created_at       timestamptz    not null,
    unique (tenant_id, fingerprint),
    unique (tenant_id, id)
);

create table finding_event (
    id         uuid primary key,
    tenant_id  uuid        not null,
    finding_id uuid        not null,
    at         timestamptz not null,
    by_whom    text        not null,
    text       text        not null,
    foreign key (tenant_id, finding_id) references finding (tenant_id, id)
);

create index finding_event_by_finding on finding_event (finding_id, at);

alter table finding enable row level security;
alter table finding force row level security;
create policy tenant_isolation on finding
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table finding_event enable row level security;
alter table finding_event force row level security;
create policy tenant_isolation on finding_event
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Like approval_timer: postponed findings of every tenant wake up on their date, so this table has no RLS and
-- holds only ids and dates.
create table finding_wakeup (
    finding_id uuid primary key,
    tenant_id  uuid not null,
    wake_on    date not null
);
