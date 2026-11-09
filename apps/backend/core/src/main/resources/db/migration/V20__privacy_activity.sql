-- Retention and erasure of personal data (ADR 0021) and the activity log (ADR 0022).

-- Rows that are evidence (receipts, ledger entries, handover acts, the activity log) cannot be changed or deleted.
-- Only two paths may delete them, both in the privacy module: the retention job, for what is past its term, and the
-- erasure of a whole company. They set app.purge for their own transaction only; nothing else sets it.
create function purging() returns boolean language sql stable as $$
    select coalesce(current_setting('app.purge', true), '') = 'on'
$$;

create or replace function ledger_entry_is_append_only() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' and purging() then
        return old;
    end if;
    raise exception 'ledger_entry is append-only';
end $$;

create or replace function receipt_is_fixed() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' then
        if purging() then
            return old;
        end if;
        raise exception 'receipt is immutable';
    end if;
    if new.payload <> old.payload or new.hash <> old.hash or new.signature <> old.signature
       or new.previous_hash <> old.previous_hash or new.seq <> old.seq or (old.batch_id is not null) then
        raise exception 'receipt is immutable';
    end if;
    return new;
end $$;

create or replace function asset_act_is_fixed() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' and purging() then
        return old;
    end if;
    raise exception 'asset act is immutable';
end $$;

-- Owned by the org module. An erased employee keeps its id, so the history that points at it (assignments,
-- agents, findings) stays whole, but nothing in the row says who it was.
alter table employee add column erased_at timestamptz;

-- Owned by the activity module (ADR 0022). Who did it is kept apart from what was done: erasing a person blanks
-- their actor row and leaves every entry as it was.
create table activity_actor (
    id        uuid primary key,
    tenant_id uuid        not null,
    -- the subject of the Keycloak token; null once erased
    subject   text,
    name      text,
    email     text,
    erased_at timestamptz,
    unique (tenant_id, subject),
    unique (tenant_id, id)
);

create table activity_entry (
    id          uuid primary key,
    tenant_id   uuid        not null,
    at          timestamptz not null,
    -- null when no person acted: a connector, a timer, the retention job
    actor_id    uuid,
    system      text,
    action      text        not null,
    target_type text        not null,
    target_id   uuid,
    -- ids, amounts and codes only, never a name or an e-mail
    details     jsonb       not null default '{}',
    foreign key (tenant_id, actor_id) references activity_actor (tenant_id, id),
    check ((actor_id is null) <> (system is null))
);

create index activity_entry_by_time on activity_entry (tenant_id, at desc, id desc);
create index activity_entry_by_target on activity_entry (tenant_id, target_id);

create function activity_entry_is_append_only() returns trigger language plpgsql as $$
begin
    if tg_op = 'DELETE' and purging() then
        return old;
    end if;
    raise exception 'activity_entry is append-only';
end $$;

create trigger activity_entry_append_only before update or delete on activity_entry
    for each row execute function activity_entry_is_append_only();

alter table activity_actor enable row level security;
alter table activity_actor force row level security;
create policy tenant_isolation on activity_actor
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table activity_entry enable row level security;
alter table activity_entry force row level security;
create policy tenant_isolation on activity_entry
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Owned by the privacy module (ADR 0021). One row per erased employee: what each store did, in counts. It names
-- nobody: the employee id points at a row that no longer says who it was.
create table erasure (
    id         uuid primary key,
    tenant_id  uuid        not null,
    subject_id uuid        not null,
    reason     text        not null check (reason in ('request', 'retention')),
    erased_at  timestamptz not null,
    report     jsonb       not null,
    unique (tenant_id, subject_id)
);

alter table erasure enable row level security;
alter table erasure force row level security;
create policy tenant_isolation on erasure
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Like approval_timer, no RLS: it outlives the company it erases, so it holds ids, times and counts only.
create table company_erasure (
    tenant_id     uuid primary key,
    requested_at  timestamptz not null,
    scheduled_for timestamptz not null,
    cancelled_at  timestamptz,
    completed_at  timestamptz,
    attempts      int         not null default 0,
    last_error    text,
    report        jsonb
);

create index company_erasure_due on company_erasure (scheduled_for) where completed_at is null and cancelled_at is null;

-- The tenants the retention job visits. No RLS, like audit_tenant: under RLS no query can list tenants. Triggers
-- add a tenant when it gets something retention removes later.
create table privacy_tenant (
    tenant_id uuid primary key
);

create function privacy_tenant_seen() returns trigger language plpgsql as $$
begin
    insert into privacy_tenant (tenant_id) values (new.tenant_id) on conflict do nothing;
    return new;
end;
$$;

create trigger privacy_tenant_seen after insert or update on employee for each row execute function privacy_tenant_seen();
create trigger privacy_tenant_seen after insert on notification for each row execute function privacy_tenant_seen();
create trigger privacy_tenant_seen after insert on asset_act for each row execute function privacy_tenant_seen();
create trigger privacy_tenant_seen after insert on import_job for each row execute function privacy_tenant_seen();
create trigger privacy_tenant_seen after insert or update on ingest_entity for each row execute function privacy_tenant_seen();
create trigger privacy_tenant_seen after insert on activity_entry for each row execute function privacy_tenant_seen();

-- Tenants that exist already: the tables without RLS are the only ones a migration can read.
insert into privacy_tenant (tenant_id)
select tenant_id from audit_tenant
union select tenant_id from approval_timer
union select tenant_id from finding_wakeup
on conflict do nothing;
