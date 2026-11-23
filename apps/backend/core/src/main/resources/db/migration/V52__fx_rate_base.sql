-- Owned by the catalog module. A rate is the price of one unit of a currency in a base currency (ADR 0075): roubles
-- for the Bank of Russia, euros for the European Central Bank. Every rate loaded so far is the Bank of Russia's.
-- A deployment may switch its source; rates of both bases then live side by side, and a conversion takes both of
-- its currencies from one base.
alter table fx_rate add column base char(3) not null default 'RUB';
alter table fx_rate drop constraint fx_rate_pkey;
alter table fx_rate add primary key (rate_date, base, currency);
