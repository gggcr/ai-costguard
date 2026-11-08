-- Owned by the imports module. The file itself is in object storage under storage_key.
create table import_job (
    id             uuid primary key,
    tenant_id      uuid        not null,
    file_name      text        not null,
    type           text        not null check (type in ('excel', 'csv', 'pdf', 'scan', 'bank')),
    vendor_id      uuid,
    uploaded_at    timestamptz not null,
    uploaded_by    text        not null,
    status         text        not null check (status in ('uploaded', 'mapping', 'review', 'done', 'failed')),
    sha256         char(64)    not null,
    storage_key    text        not null,
    size_bytes     bigint      not null,
    rows           int         not null default 0,
    total          numeric(18, 2) not null default 0,
    columns        text[]      not null default '{}',
    mapping        jsonb       not null default '{}',
    errors         jsonb       not null default '[]',
    failure        text,
    template_saved boolean     not null default false,
    unique (tenant_id, sha256),
    unique (tenant_id, id)
);

create table import_review_task (
    id             uuid primary key,
    tenant_id      uuid    not null,
    job_id         uuid    not null,
    row            int     not null,
    field          text    not null,
    value          text    not null,
    confidence     numeric(4, 3) not null,
    resolved       boolean not null default false,
    resolved_value text,
    foreign key (tenant_id, job_id) references import_job (tenant_id, id)
);

create index import_review_task_by_job on import_review_task (job_id);

alter table import_job enable row level security;
alter table import_job force row level security;
create policy tenant_isolation on import_job
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

alter table import_review_task enable row level security;
alter table import_review_task force row level security;
create policy tenant_isolation on import_review_task
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
