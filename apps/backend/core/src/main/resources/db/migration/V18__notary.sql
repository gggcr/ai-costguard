-- What the NEAR notary wrote for the core (ADR 0009). Owned by agents (mandate_notarization) and approvals
-- (the notary columns of approval_request). Only hashes go to the chain; these rows keep what they were computed
-- from, so the company can show anyone checking that the hash on the chain is theirs.

create table mandate_notarization (
    tenant_id    uuid        not null,
    mandate_id   uuid        not null,
    version      int         not null check (version > 0),
    mandate_hash text        not null,
    requested_at timestamptz not null,
    near_tx_hash text,
    notarized_at timestamptz,
    primary key (mandate_id, version),
    foreign key (tenant_id, mandate_id) references mandate (tenant_id, id) on delete cascade
);

alter table mandate_notarization enable row level security;
alter table mandate_notarization force row level security;
create policy tenant_isolation on mandate_notarization
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Set only for an approved operation of at least costguard.notary.confirm-above.
alter table approval_request add column notary_hash text;
alter table approval_request add column near_tx_hash text;
alter table approval_request add column notarized_at timestamptz;
