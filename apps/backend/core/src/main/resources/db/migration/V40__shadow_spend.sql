-- Теневые траты (ADR 0057). Owned by the cabinet module. A purchase an employee made with their own card for work.
-- The row names no employee: only their department and a pseudonym (an HMAC of the employee with a server key)
-- that lets the reporter list and delete their own reports. Management reads aggregates only.
create table shadow_spend (
    id          uuid primary key,
    tenant_id   uuid           not null,
    reporter    char(64)       not null,
    department  text           not null,
    service     text           not null,
    service_key text           not null,
    category    text           not null,
    amount      numeric(14, 2) not null check (amount >= 0),
    currency    char(3)        not null,
    paid_on     date           not null,
    purpose     text,
    source      text           not null check (source in ('form', 'file')),
    created_at  timestamptz    not null
);
create index shadow_spend_by_reporter on shadow_spend (tenant_id, reporter, paid_on desc);
create index shadow_spend_by_service on shadow_spend (tenant_id, service_key);

alter table shadow_spend enable row level security;
alter table shadow_spend force row level security;
create policy tenant_isolation on shadow_spend
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
