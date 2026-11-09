-- Gives the backup role what V21__backup_role.sql could not when the role did not exist yet (ADR 0025).
-- Run once, as the application role (the owner of the tables), after deploy/terraform created costguard_backup:
--
--   psql "$DB_URL" -U costguard -f tools/backup/grant-backup-role.sql
--
-- Safe to run again.
grant usage on schema public to costguard_backup;
grant select on all tables in schema public to costguard_backup;
alter default privileges in schema public grant select on tables to costguard_backup;
