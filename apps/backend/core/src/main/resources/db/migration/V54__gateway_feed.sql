-- The state the gateway needs, for installs without Kafka (ADR 0080, EVENTS_TRANSPORT=direct). One row per topic
-- and key, as a compacted topic keeps it: a newer value replaces the older and takes a new seq, a NULL value is a
-- tombstone (an agent or a company erased, ADR 0021). The gateway reads the rows after the last seq it has seen.
-- Written only by the outbox, read only by the gateway through the core; empty when the core runs with Kafka.
-- Every company's rows, like the topics: no RLS, the gateway serves all of them.
create sequence gateway_feed_seq;

create table gateway_feed (
    topic text   not null,
    key   text   not null,
    value bytea,
    seq   bigint not null,
    primary key (topic, key)
);

create index gateway_feed_by_seq on gateway_feed (seq);

-- One row, made with the table: a recreated database has another epoch, its seqs start again, and a gateway that sees
-- the new epoch reads the feed from 0.
create table gateway_feed_epoch (
    id  uuid    primary key default gen_random_uuid(),
    one boolean not null default true unique check (one)
);
insert into gateway_feed_epoch default values;
