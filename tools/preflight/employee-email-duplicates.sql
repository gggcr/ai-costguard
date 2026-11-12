-- Preflight before a unique index on employee (tenant_id, lower(email)) (ADR 0070). Read only: it changes nothing.
-- Lists every company with two or more employees, not erased, whose addresses differ in case only, and the rows.
-- Run as a role that may read every company, e.g. costguard_backup (backup_read policy, SELECT only):
--   psql "$DB_URL" -U costguard_backup -v ON_ERROR_STOP=1 -f tools/preflight/employee-email-duplicates.sql
-- An empty result means the index can be created.
select e.tenant_id,
       lower(e.email)                                              as address,
       count(*)                                                    as employees,
       count(*) filter (where e.dismissed_at is null)              as working,
       string_agg(e.id::text || ' ' || e.email || coalesce(' dismissed ' || e.dismissed_at::text, ''), '; '
                  order by e.email)                                as rows
from employee e
where e.erased_at is null
group by e.tenant_id, lower(e.email)
having count(*) > 1
order by e.tenant_id, address;
