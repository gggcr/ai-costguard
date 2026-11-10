-- Invoices and payments in the core (ADR 0033). Owned by the software module. They come from the ingest pipeline and
-- the connectors (ingest.normalized.v1): entity_key is the key of the entity there, so a redelivered or corrected
-- entity changes its row instead of adding one. Amounts are in the document's currency.

create table invoice (
    id              uuid primary key,
    tenant_id       uuid           not null,
    entity_key      text           not null,
    vendor_id       uuid           not null,
    -- the subscription the invoice is for, when the core could tell; null when it could not
    subscription_id uuid,
    number          text           not null default '',
    issued_on       date           not null,
    -- false when the source named no date and issued_on is the day the invoice arrived
    dated           boolean        not null,
    currency        char(3)        not null,
    total           numeric(18, 2) not null,
    source          text           not null default '',
    received_at     timestamptz    not null,
    unique (tenant_id, entity_key),
    unique (tenant_id, id),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id),
    foreign key (tenant_id, subscription_id) references subscription (tenant_id, id)
);

create index invoice_by_subscription on invoice (subscription_id, issued_on);

create table invoice_line (
    tenant_id   uuid           not null,
    invoice_id  uuid           not null,
    line_no     int            not null,
    description text           not null,
    amount      numeric(18, 2) not null,
    primary key (invoice_id, line_no),
    foreign key (tenant_id, invoice_id) references invoice (tenant_id, id) on delete cascade
);

create table payment (
    id           uuid primary key,
    tenant_id    uuid           not null,
    entity_key   text           not null,
    vendor_id    uuid           not null,
    -- the invoice the payment settles, when one matched
    invoice_id   uuid,
    amount       numeric(18, 2) not null,
    currency     char(3)        not null,
    paid_on      date           not null,
    dated        boolean        not null,
    purpose      text           not null default '',
    -- TRANSFER, CARD, SBP... as the bank said; '' when unknown
    kind         text           not null default '',
    -- the masked card number, when paid by card
    card         text           not null default '',
    mcc          text           not null default '',
    -- payment, service or commission (ADR 0015)
    role         text           not null default 'payment',
    intermediary text           not null default '',
    source       text           not null default '',
    received_at  timestamptz    not null,
    unique (tenant_id, entity_key),
    foreign key (tenant_id, vendor_id) references vendor (tenant_id, id),
    foreign key (tenant_id, invoice_id) references invoice (tenant_id, id)
);

create index payment_by_vendor on payment (vendor_id, paid_on);

alter table invoice enable row level security;
alter table invoice force row level security;
create policy tenant_isolation on invoice
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on invoice for select using (current_user = 'costguard_backup');

alter table invoice_line enable row level security;
alter table invoice_line force row level security;
create policy tenant_isolation on invoice_line
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on invoice_line for select using (current_user = 'costguard_backup');

alter table payment enable row level security;
alter table payment force row level security;
create policy tenant_isolation on payment
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
create policy backup_read on payment for select using (current_user = 'costguard_backup');

-- The copies below run without a tenant, and the tables force RLS even on their owner. Flyway connects as the owner,
-- so for this transaction the owner reads every row; the policies come back at the end.
alter table ingest_entity no force row level security;
alter table vendor no force row level security;
alter table subscription no force row level security;
alter table invoice no force row level security;
alter table payment no force row level security;

-- What the pipeline and the connectors already reported.

insert into invoice (id, tenant_id, entity_key, vendor_id, number, issued_on, dated, currency, total, source,
                     received_at)
select gen_random_uuid(), e.tenant_id, e.entity_key, e.linked_id,
       coalesce(e.payload ->> 'number', ''),
       case when e.payload ->> 'date' ~ '^\d{4}-\d{2}-\d{2}' then substr(e.payload ->> 'date', 1, 10)::date
            else e.first_seen::date end,
       coalesce(e.payload ->> 'date' ~ '^\d{4}-\d{2}-\d{2}', false),
       upper(coalesce(nullif(e.payload ->> 'currency', ''), 'RUB')),
       (e.payload ->> 'total')::numeric(18, 2),
       coalesce(e.payload ->> 'source', e.payload ->> 'file_name', ''),
       e.first_seen
from ingest_entity e
where e.entity_type = 'INVOICE' and e.linked_id is not null
  and e.payload ->> 'total' ~ '^-?\d+(\.\d+)?$'
  and length(coalesce(nullif(e.payload ->> 'currency', ''), 'RUB')) = 3
  and exists (select 1 from vendor v where v.tenant_id = e.tenant_id and v.id = e.linked_id)
on conflict (tenant_id, entity_key) do nothing;

-- an invoice of a vendor with one subscription is for that subscription, as SoftwareLinker decides for new ones
update invoice i set subscription_id = (select s.id from subscription s where s.vendor_id = i.vendor_id)
where i.subscription_id is null
  and (select count(*) from subscription s where s.vendor_id = i.vendor_id) = 1;

insert into payment (id, tenant_id, entity_key, vendor_id, amount, currency, paid_on, dated, purpose, kind, card, mcc,
                     role, intermediary, source, received_at)
select gen_random_uuid(), e.tenant_id, e.entity_key, e.linked_id,
       (e.payload ->> 'amount')::numeric(18, 2),
       upper(coalesce(nullif(e.payload ->> 'currency', ''), 'RUB')),
       case when e.payload ->> 'date' ~ '^\d{4}-\d{2}-\d{2}' then substr(e.payload ->> 'date', 1, 10)::date
            else e.first_seen::date end,
       coalesce(e.payload ->> 'date' ~ '^\d{4}-\d{2}-\d{2}', false),
       coalesce(e.payload ->> 'purpose', ''), coalesce(e.payload ->> 'kind', ''),
       coalesce(e.payload ->> 'card', ''), coalesce(e.payload ->> 'mcc', ''),
       coalesce(e.payload ->> 'role', 'payment'), coalesce(e.payload ->> 'intermediary', ''),
       coalesce(e.payload ->> 'source', ''), e.first_seen
from ingest_entity e
where e.entity_type = 'PAYMENT' and e.linked_id is not null
  and e.payload ->> 'amount' ~ '^-?\d+(\.\d+)?$'
  and length(coalesce(nullif(e.payload ->> 'currency', ''), 'RUB')) = 3
  and exists (select 1 from vendor v where v.tenant_id = e.tenant_id and v.id = e.linked_id)
on conflict (tenant_id, entity_key) do nothing;

alter table ingest_entity force row level security;
alter table vendor force row level security;
alter table subscription force row level security;
alter table invoice force row level security;
alter table payment force row level security;
