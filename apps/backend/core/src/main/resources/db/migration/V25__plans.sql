-- Owned by the plans module (ADR 0033, ADR 0034). Every table with RLS also gets the backup policy (ADR 0025).

-- The plan of a company. No row: a company that existed before plans, which keeps working without limits until an
-- operator gives it a plan.
create table tenant_plan (
    tenant_id     uuid primary key,
    plan          text        not null check (plan in ('trial', 'start', 'business')),
    trial_ends_at timestamptz,
    -- The end of the trial or of the paid period; null: no end. At this moment the company becomes read-only.
    access_until  timestamptz,
    version       bigint      not null,
    updated_at    timestamptz not null
);

alter table tenant_plan enable row level security;
alter table tenant_plan force row level security;
create policy tenant_isolation on tenant_plan
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on tenant_plan for select using (current_user = 'costguard_backup');

-- What the watcher walks across companies (ADR 0002: ids and dates only, so no RLS). warned_for and ended_for hold
-- the access_until that was warned about or ended, so a new period is watched again.
create table plan_schedule (
    tenant_id    uuid primary key,
    access_until timestamptz,
    warned_for   timestamptz,
    ended_for    timestamptz
);

create index plan_schedule_due on plan_schedule (access_until) where access_until is not null;

create table payment (
    id                  uuid primary key,
    tenant_id           uuid          not null,
    plan                text          not null,
    amount              numeric(18, 2) not null,
    currency            char(3)       not null,
    provider            text          not null,
    provider_payment_id text,
    status              text          not null check (status in ('pending', 'succeeded', 'canceled')),
    created_at          timestamptz   not null,
    paid_at             timestamptz
);

alter table payment enable row level security;
alter table payment force row level security;
create policy tenant_isolation on payment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on payment for select using (current_user = 'costguard_backup');

-- A provider's notice names its own payment id and no company: this finds the company (ids only, no RLS).
create table payment_ref (
    provider            text not null,
    provider_payment_id text not null,
    tenant_id           uuid not null,
    payment_id          uuid not null,
    primary key (provider, provider_payment_id)
);
