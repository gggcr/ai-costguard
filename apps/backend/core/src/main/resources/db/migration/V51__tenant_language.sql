-- Owned by the org module (ADR 0074). The language the core writes the company's texts in: notifications,
-- e-mail, findings, notices of the cabinet. Empty: Russian where the country speaks it, English elsewhere, and
-- Russian for a company without a country.
alter table tenant add column language text check (language in ('ru', 'en'));
