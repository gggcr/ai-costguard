-- Owned by the imports module. What the ingest pipeline and the connectors reported (ingest.normalized.v1), one
-- row per entity key. linked_id is the vendor, employee or license the entity was matched to; null means people
-- have to look at it.
create table ingest_entity (
    tenant_id   uuid        not null,
    entity_key  text        not null,
    entity_type text        not null,
    source_id   uuid        not null,
    payload     jsonb       not null,
    confidence  double precision not null,
    linked_id   uuid,
    first_seen  timestamptz not null,
    last_seen   timestamptz not null,
    primary key (tenant_id, entity_key)
);

create index ingest_entity_unlinked on ingest_entity (tenant_id, entity_type) where linked_id is null;

alter table ingest_entity enable row level security;
alter table ingest_entity force row level security;
create policy tenant_isolation on ingest_entity
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
