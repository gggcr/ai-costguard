-- Makes the application role an ordinary one: no superuser, no BYPASSRLS. Safe to run repeatedly.
-- Run it as an administrator, not as the application role:
--
--   psql -h localhost -U postgres -d postgres -v app_user=costguard -v app_db=costguard \
--        -f infra/local/postgres/fix-app-role.sql
--
-- Add -v app_password=... to set the password. Without it an existing role keeps its password.

\set ON_ERROR_STOP on

select current_user = :'app_user' as running_as_app_role \gset
\if :running_as_app_role
    do $$ begin
        raise exception 'Run this as another superuser: it would take the rights from the role you are logged in as.';
    end $$;
\endif

select format('create role %I login', :'app_user')
where not exists (select from pg_roles where rolname = :'app_user') \gexec

select format('alter role %I login nosuperuser nobypassrls nocreatedb nocreaterole noreplication', :'app_user') \gexec

\if :{?app_password}
    select format('alter role %I password %L', :'app_user', :'app_password') \gexec
\endif

select format('create database %I owner %I', :'app_db', :'app_user')
where not exists (select from pg_database where datname = :'app_db') \gexec

select format('alter database %I owner to %I', :'app_db', :'app_user') \gexec

\connect :app_db

-- Objects left by an earlier run under a superuser. FORCE only binds the owner if the owner is this role.
select format('alter schema public owner to %I', :'app_user') \gexec

select format('alter table %I.%I owner to %I', n.nspname, c.relname, :'app_user')
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
where n.nspname = 'public'
  and c.relkind in ('r', 'p', 'v', 'm')
  and pg_get_userbyid(c.relowner) <> :'app_user' \gexec

select format('alter sequence %I.%I owner to %I', n.nspname, c.relname, :'app_user')
from pg_class c
join pg_namespace n on n.oid = c.relnamespace
left join pg_depend d on d.objid = c.oid and d.deptype in ('a', 'i')
where n.nspname = 'public'
  and c.relkind = 'S'
  and d.objid is null
  and pg_get_userbyid(c.relowner) <> :'app_user' \gexec

select rolname, rolsuper, rolbypassrls from pg_roles where rolname = :'app_user';
