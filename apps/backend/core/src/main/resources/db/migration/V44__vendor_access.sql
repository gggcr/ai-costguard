-- Доступ у поставщика (ADR 0065). Owned by the cabinet module. How a seat of a subscription is given and taken
-- back in the program itself: through the company's Microsoft Entra ID (the enterprise app's assignment; Entra
-- provisions the vendor) or straight through the vendor's SCIM 2.0 API. The SCIM token is sealed (AES-256-GCM).
create table subscription_access (
    tenant_id       uuid        not null,
    subscription_id uuid        not null,
    kind            text        not null check (kind in ('entra', 'scim')),
    entra_app_id    text,
    entra_app_name  text,
    scim_url        text,
    scim_token      bytea,
    scim_revoke     text        not null default 'deactivate' check (scim_revoke in ('deactivate', 'delete')),
    updated_at      timestamptz not null,
    primary key (tenant_id, subscription_id)
);

create table access_change (
    id              uuid primary key,
    tenant_id       uuid        not null,
    subscription_id uuid        not null,
    employee_id     uuid        not null,
    action          text        not null check (action in ('grant', 'revoke')),
    status          text        not null check (status in ('done', 'failed')),
    detail          text,
    at              timestamptz not null
);
create index access_change_by_subscription on access_change (tenant_id, subscription_id, at desc);

alter table subscription_access enable row level security;
alter table subscription_access force row level security;
create policy tenant_isolation on subscription_access
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on subscription_access for select using (current_user = 'costguard_backup');

alter table access_change enable row level security;
alter table access_change force row level security;
create policy tenant_isolation on access_change
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on access_change for select using (current_user = 'costguard_backup');
