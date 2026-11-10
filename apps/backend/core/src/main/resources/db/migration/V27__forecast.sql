-- Owned by the analytics module (ADR 0034). The forecasts the forecast job publishes: the company, its departments
-- reconciled with it, the registry baseline and the expected savings, as one document. The newest one is shown;
-- the older ones stay to compare forecasts with what happened.
create table forecast (
    id           uuid primary key,
    tenant_id    uuid        not null,
    published_at timestamptz not null,
    body         jsonb       not null
);

create index forecast_newest on forecast (tenant_id, published_at desc);

alter table forecast enable row level security;
alter table forecast force row level security;
create policy tenant_isolation on forecast
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on forecast for select using (current_user = 'costguard_backup');
