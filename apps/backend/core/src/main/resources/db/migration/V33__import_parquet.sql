-- Parquet files: the FOCUS exports of AWS and Azure (ADR 0046).
alter table import_job drop constraint import_job_type_check;
alter table import_job add constraint import_job_type_check
    check (type in ('excel', 'csv', 'parquet', 'pdf', 'scan', 'bank'));
