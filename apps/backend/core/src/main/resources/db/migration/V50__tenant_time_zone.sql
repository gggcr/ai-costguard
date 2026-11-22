-- Owned by the org module (ADR 0073). The time zone of a company, IANA (America/Chicago): what "today", "this month"
-- and "every morning" mean for it. Empty: the main zone of its country, Moscow for a company without a country.
alter table tenant add column time_zone text;
