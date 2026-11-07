-- Owned by the journal module. Each tenant has its own hash chain of receipts.
create table receipt (
    id            uuid primary key,
    tenant_id     uuid        not null,
    operation_id  uuid        not null,
    seq           bigint      not null,
    payload       text        not null,
    hash          char(64)    not null,
    previous_hash char(64)    not null,
    signature     text        not null,
    key_id        text        not null,
    issued_at     timestamptz not null,
    batch_id      uuid,
    unique (tenant_id, operation_id),
    unique (tenant_id, seq)
);

-- The head of each chain. Its row lock makes receipts of one tenant line up one after another.
create table receipt_chain (
    tenant_id uuid primary key,
    seq       bigint   not null,
    hash      char(64) not null
);

create table anchor_batch (
    id           uuid primary key,
    tenant_id    uuid        not null,
    merkle_root  char(64)    not null,
    near_tx_hash text        not null,
    anchored_at  timestamptz not null
);

-- Receipts are evidence: once issued they change only to record the batch that anchored them.
create function receipt_is_fixed() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' or new.payload <> old.payload or new.hash <> old.hash or new.signature <> old.signature
       or new.previous_hash <> old.previous_hash or new.seq <> old.seq or (old.batch_id is not null) then
        raise exception 'receipt is immutable';
    end if;
    return new;
end $$;

create trigger receipt_fixed before update or delete on receipt
    for each row execute function receipt_is_fixed();

alter table receipt enable row level security;
alter table receipt force row level security;
create policy tenant_isolation on receipt
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table receipt_chain enable row level security;
alter table receipt_chain force row level security;
create policy tenant_isolation on receipt_chain
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table anchor_batch enable row level security;
alter table anchor_batch force row level security;
create policy tenant_isolation on anchor_batch
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
