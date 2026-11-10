-- Owned by the findings module (ADR 0033): when the task was created. Only invoices issued on that day or later can
-- show the saving, and the last invoice before it is what the company paid without the change.
alter table finding add column task_at timestamptz;

-- Findings already in progress take the moment of their "Создана задача". The update runs without a tenant, and the
-- tables force RLS even on their owner; Flyway connects as the owner, so for this transaction the owner reads every
-- row, and the policies come back at the end.
alter table finding no force row level security;
alter table finding_event no force row level security;

update finding f set task_at = (select max(e.at) from finding_event e
                                where e.finding_id = f.id and e.text like 'Создана задача%')
where f.status = 'in_progress';

alter table finding force row level security;
alter table finding_event force row level security;
