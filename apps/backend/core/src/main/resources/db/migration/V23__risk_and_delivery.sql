-- Owned by the agents module: the latest risk of each agent (task_id '') and of each of its tasks, from
-- agent.risk-signals.v1 (ADR 0031). The agent row keeps the strictest of them for the list and the card.
create table agent_risk (
    tenant_id   uuid        not null,
    agent_id    uuid        not null,
    task_id     text        not null default '',
    score       int         not null check (score between 0 and 100),
    action      text        not null check (action in ('NORMAL', 'SLOW_DOWN', 'REQUIRE_APPROVAL', 'STOP')),
    reasons     text[]      not null default '{}',
    detected_at timestamptz not null,
    primary key (tenant_id, agent_id, task_id)
);

alter table agent add column risk_action text not null default 'NORMAL'
    check (risk_action in ('NORMAL', 'SLOW_DOWN', 'REQUIRE_APPROVAL', 'STOP'));

alter table agent_risk enable row level security;
alter table agent_risk force row level security;
create policy tenant_isolation on agent_risk
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on agent_risk for select using (current_user = 'costguard_backup');

-- Owned by the notifications module (ADR 0031). Where a user wants notifications outside the web client and from
-- which level: off, info, warning or critical. A user without a row gets e-mail for critical ones only.
create table notification_setting (
    user_id            uuid primary key references user_account (id) on delete cascade,
    tenant_id          uuid        not null,
    email_level        text        not null default 'critical'
        check (email_level in ('off', 'info', 'warning', 'critical')),
    telegram_level     text        not null default 'warning'
        check (telegram_level in ('off', 'info', 'warning', 'critical')),
    telegram_chat_id   bigint,
    telegram_linked_at timestamptz
);

-- One message to one user over one channel. The address is read when it is sent, so a changed e-mail or an
-- unlinked Telegram takes effect for messages still waiting.
create table notification_delivery (
    id              uuid primary key,
    tenant_id       uuid        not null,
    notification_id uuid        not null references notification (id) on delete cascade,
    user_id         uuid        not null references user_account (id) on delete cascade,
    channel         text        not null check (channel in ('email', 'telegram')),
    status          text        not null default 'pending' check (status in ('pending', 'sent', 'failed')),
    attempts        int         not null default 0,
    next_attempt_at timestamptz not null,
    sent_at         timestamptz,
    last_error      text
);

create index notification_delivery_due on notification_delivery (tenant_id, next_attempt_at)
    where status = 'pending';

alter table notification_setting enable row level security;
alter table notification_setting force row level security;
create policy tenant_isolation on notification_setting
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on notification_setting for select using (current_user = 'costguard_backup');

alter table notification_delivery enable row level security;
alter table notification_delivery force row level security;
create policy tenant_isolation on notification_delivery
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on notification_delivery for select using (current_user = 'costguard_backup');

-- The companies that ever had messages to send; the sender visits them. Like audit_tenant it has no RLS: the sender
-- lists companies before it can bind one, and the table holds nothing but their ids.
create table notification_tenant (
    tenant_id uuid primary key
);

-- Telegram linking: the bot receives "/start <code>" and does not know the company, so the code (its SHA-256)
-- leads to the company and the user. No RLS for that reason; it holds ids only and expires in minutes.
create table telegram_link (
    code_hash  char(64) primary key,
    tenant_id  uuid        not null,
    user_id    uuid        not null references user_account (id) on delete cascade,
    expires_at timestamptz not null
);

-- The last update of the bot that was handled; one row. Instances poll Telegram one at a time (advisory lock).
create table telegram_state (
    id            int primary key check (id = 1),
    update_offset bigint not null
);
insert into telegram_state (id, update_offset) values (1, 0);
