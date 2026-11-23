-- Subscriptions at the payment provider (ADR 0077, Stripe): renewals, cancellations and failed payments come as
-- provider events about the subscription, not about a payment the core started. Owned by the plans module.

-- The company's subscription. One per company: a change of plan updates it at the provider instead of starting
-- another one.
create table plan_subscription (
    tenant_id            uuid primary key,
    provider             text        not null,
    subscription_id      text        not null,
    customer_id          text        not null,
    plan                 text        not null check (plan in ('start', 'business')),
    -- the provider's status: active, trialing, past_due, unpaid, canceled, incomplete, incomplete_expired, paused
    status               text        not null,
    cancel_at_period_end boolean     not null default false,
    -- the end of the last period the provider confirmed as paid
    paid_until           timestamptz,
    -- the time of the provider event that set the status: an older event arriving later does not undo it
    status_at            timestamptz not null,
    updated_at           timestamptz not null
);

alter table plan_subscription enable row level security;
alter table plan_subscription force row level security;
create policy tenant_isolation on plan_subscription
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on plan_subscription for select using (current_user = 'costguard_backup');

-- An event names the subscription and no company: this finds the company (ids only, no RLS, as plan_payment_ref).
create table plan_subscription_ref (
    provider        text not null,
    subscription_id text not null,
    tenant_id       uuid not null,
    primary key (provider, subscription_id)
);

-- Provider events already applied: a delivery repeated by the provider changes nothing (ids only, no RLS).
create table plan_billing_event (
    provider    text        not null,
    event_id    text        not null,
    tenant_id   uuid,
    received_at timestamptz not null,
    primary key (provider, event_id)
);

create index plan_billing_event_tenant on plan_billing_event (tenant_id) where tenant_id is not null;
