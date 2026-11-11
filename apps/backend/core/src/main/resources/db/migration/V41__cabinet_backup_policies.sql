-- Backups (ADR 0025): the tables of «Мой кабинет» (ADR 0051–0057) are read by costguard_backup like every other
-- table with RLS. Added in one migration because V35–V40 left them out; RlsBackupPolicyTest checks every table.
create policy backup_read on employee_document for select using (current_user = 'costguard_backup');
create policy backup_read on vacation_request for select using (current_user = 'costguard_backup');
create policy backup_read on payroll_schedule for select using (current_user = 'costguard_backup');
create policy backup_read on payroll_payment for select using (current_user = 'costguard_backup');
create policy backup_read on license_request for select using (current_user = 'costguard_backup');
create policy backup_read on purchase_request for select using (current_user = 'costguard_backup');
create policy backup_read on shadow_spend for select using (current_user = 'costguard_backup');
