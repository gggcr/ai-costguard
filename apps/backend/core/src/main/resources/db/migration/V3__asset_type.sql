-- Owned by the assets module. Rows that existed before this column cannot be classified from free-text
-- model names, so they become OTHER. New rows must state their type: the default is dropped.
alter table asset
    add column type text not null default 'OTHER'
        check (type in ('LAPTOP', 'PHONE', 'MONITOR', 'OTHER'));

alter table asset alter column type drop default;
