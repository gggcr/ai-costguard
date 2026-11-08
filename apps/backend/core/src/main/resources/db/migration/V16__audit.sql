-- Owned by the findings module. The tenants the audit engine visits. Like finding_wakeup it has no RLS: the
-- engine has to list tenants before it can bind one, and the table holds nothing but their ids.
create table audit_tenant (
    tenant_id uuid primary key
);

create function audit_tenant_seen() returns trigger language plpgsql as $$
begin
    insert into audit_tenant (tenant_id) values (new.tenant_id) on conflict do nothing;
    return new;
end;
$$;

create trigger audit_tenant_seen after insert on subscription for each row execute function audit_tenant_seen();
create trigger audit_tenant_seen after insert on license_assignment for each row execute function audit_tenant_seen();
create trigger audit_tenant_seen after insert on contract for each row execute function audit_tenant_seen();
create trigger audit_tenant_seen after insert on asset for each row execute function audit_tenant_seen();
