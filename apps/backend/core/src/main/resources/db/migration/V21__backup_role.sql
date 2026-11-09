-- Backups (ADR 0025). The nightly logical dump runs as costguard_backup, a role deploy/terraform creates with its own
-- password. Under forced RLS the application role sees no rows without a tenant, so a dump as that role would be
-- empty. Every table with RLS gets one more policy: costguard_backup may read every row. It cannot write: the policy
-- is for SELECT only and the role gets SELECT only. The application role cannot use it, a policy names the role.
--
-- A table added later with RLS needs the same policy in its migration; RlsBackupPolicyTest fails without it.
do $$
declare
    t record;
begin
    for t in
        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind = 'r' and c.relrowsecurity
    loop
        execute format('create policy backup_read on %I for select using (current_user = %L)', t.relname,
                       'costguard_backup');
    end loop;

    -- Grants need the role. Where it does not exist yet (a laptop, the tests), the backup runbook grants them once
    -- it is created: tools/backup/grant-backup-role.sql.
    if exists (select 1 from pg_roles where rolname = 'costguard_backup') then
        execute 'grant usage on schema public to costguard_backup';
        execute 'grant select on all tables in schema public to costguard_backup';
        execute 'alter default privileges in schema public grant select on tables to costguard_backup';
    end if;
end $$;
