-- Owned by the connections module (ADR 0030): secrets a company chose to keep in the core, AES-GCM ciphertext
-- (SecretBox). The key is never in the database. secret_names says which are set, without their values.
alter table connection add column secrets bytea;
alter table connection add column secret_names text[] not null default '{}';
