-- Owned by the approvals module (ADR 0009, finding H2 of 2026-11-12). Who decided an approval was the e-mail of the
-- token (or, for an agent owner deciding in the cabinet, the token's name), and the approver hash the NEAR notary
-- keeps for ever was made from it. A person could put another address or name on their Keycloak account and leave
-- someone else in that trail. The decider is now the CostGuard account of the token's subject.
--
-- decided_by_account: that account. No foreign key on purpose: the decision outlives the account (an erasure deletes
--   it), and the id alone then names nobody, while it still lets the approver hash be checked.
-- approver_hash: the hash sent to the notary, kept to compare with the chain.
-- approver_hash_version: 1 for the hashes made before (from decided_by), 2 for those from the account. The hashes
--   already on the chain are not recomputed: they stay v1 and are checked as v1.
alter table approval_request add column decided_by_account uuid;
alter table approval_request add column approver_hash text check (approver_hash ~ '^[0-9a-f]{64}$');
alter table approval_request add column approver_hash_version smallint check (approver_hash_version in (1, 2));

-- The approvals that went to the notary before this: their approver hash was v1. Without a tenant the update would
-- see no row under the forced RLS; Flyway connects as the owner, so the policies are off for this transaction only.
alter table approval_request no force row level security;
update approval_request set approver_hash_version = 1 where notary_hash is not null;
alter table approval_request force row level security;
