-- Owned by the notifications module. One feed per tenant, as in the frontend.
create table notification (
    id        uuid primary key,
    tenant_id uuid        not null,
    at        timestamptz not null,
    text      text        not null,
    link      text,
    level     text        not null check (level in ('info', 'warning', 'critical', 'good')),
    read      boolean     not null default false
);

create index notification_by_time on notification (tenant_id, at desc);

alter table notification enable row level security;
alter table notification force row level security;
create policy tenant_isolation on notification
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
