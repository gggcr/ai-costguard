-- Почта при добавлении сотрудника (ADR 0059): the core makes the person's Microsoft 365 account through Microsoft
-- Graph, so it keeps the app's client secret, sealed with AES-256-GCM under costguard.connections.encryption-key.
alter table company_login add column client_secret    bytea;
alter table company_login add column create_mailboxes boolean not null default false;
alter table company_login add column license_sku      text;
alter table company_login add column usage_location   text;
