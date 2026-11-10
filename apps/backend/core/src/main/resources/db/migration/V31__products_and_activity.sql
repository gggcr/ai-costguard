-- PRODUCT and ACTIVITY_EVENT of the board's data model (ADR 0042), owned by the software module.

-- What a vendor sells, once per company. A subscription is to a product; the category says what the product is for
-- (mail, calls, tasks...), and when a person sets it, the overlap rule takes it over the dictionary of names.
-- name_key is the name in lower case with single spaces: two spellings of one product are one row.
create table product (
    id        uuid primary key,
    tenant_id uuid not null,
    vendor_id uuid not null,
    name      text not null,
    name_key  text not null,
    category  text,
    unique (tenant_id, id),
    unique (tenant_id, vendor_id, name_key),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id)
);

alter table subscription add column product_id uuid;
alter table subscription add constraint subscription_product
    foreign key (tenant_id, product_id) references product (tenant_id, id);

-- One sign-in or use of a product by a person, as an identity provider, a connector or a file reported it. The
-- last activity of a license is the newest of them. Kept for PRIVACY_RETAIN_ACTIVITY, like the activity it explains.
create table activity_event (
    id              uuid primary key,
    tenant_id       uuid        not null,
    employee_id     uuid        not null,
    product_id      uuid        not null,
    subscription_id uuid,
    occurred_at     timestamptz not null,
    kind            text        not null default 'sign_in',
    source          text        not null default '',
    unique (tenant_id, employee_id, product_id, occurred_at, kind),
    foreign key (tenant_id, product_id) references product (tenant_id, id)
);

create index activity_event_by_product on activity_event (product_id, occurred_at desc);
create index activity_event_by_employee on activity_event (employee_id);

alter table product enable row level security;
alter table product force row level security;
create policy tenant_isolation on product
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on product for select using (current_user = 'costguard_backup');

alter table activity_event enable row level security;
alter table activity_event force row level security;
create policy tenant_isolation on activity_event
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on activity_event for select using (current_user = 'costguard_backup');

-- Every subscription gets its product. The copy runs without a tenant, and the tables force RLS even on their
-- owner; Flyway connects as the owner, so for this transaction the owner reads every row.
alter table subscription no force row level security;
alter table product no force row level security;
insert into product (id, tenant_id, vendor_id, name, name_key)
select gen_random_uuid(), s.tenant_id, s.vendor_id, min(btrim(s.product)),
       lower(regexp_replace(btrim(s.product), '\s+', ' ', 'g'))
from subscription s
group by s.tenant_id, s.vendor_id, lower(regexp_replace(btrim(s.product), '\s+', ' ', 'g'))
on conflict do nothing;
update subscription s set product_id = p.id from product p
where p.tenant_id = s.tenant_id and p.vendor_id = s.vendor_id
  and p.name_key = lower(regexp_replace(btrim(s.product), '\s+', ' ', 'g'));
alter table subscription force row level security;
alter table product force row level security;
