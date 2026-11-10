-- Row policies (ADR 0035): a reader of one company sees the rows of that company only. The reports and forecast jobs
-- read per company; they connect as costguard_reader and send the company in the custom setting SQL_tenant_id with
-- every query. A reader who sends no company gets an error, not every company's rows. Everyone else (the core,
-- which reconciles budgets across companies, the backups, the Kafka materialized views) sees every row, as before:
-- once a table has a row policy, a user no policy names would see nothing.
--
-- Applied by apply.py with CLICKHOUSE_ACCESS_MANAGEMENT=1, as a user who may manage access. ${CLICKHOUSE_DB} is the
-- database of the migrations; ${CLICKHOUSE_READER_PASSWORD} is the password of the reader, from the secret.
CREATE ROLE IF NOT EXISTS costguard_tenant_reader;

GRANT SELECT ON ${CLICKHOUSE_DB}.cost_records TO costguard_tenant_reader;
GRANT SELECT ON ${CLICKHOUSE_DB}.agent_operations TO costguard_tenant_reader;
GRANT SELECT ON ${CLICKHOUSE_DB}.spend_by_month TO costguard_tenant_reader;
GRANT SELECT ON ${CLICKHOUSE_DB}.spend_by_department_month TO costguard_tenant_reader;
GRANT SELECT ON ${CLICKHOUSE_DB}.agent_spend_by_day TO costguard_tenant_reader;

CREATE ROW POLICY IF NOT EXISTS tenant_only ON ${CLICKHOUSE_DB}.cost_records FOR SELECT
    USING tenant_id = toUUIDOrZero(getSetting('SQL_tenant_id')) TO costguard_tenant_reader;
CREATE ROW POLICY IF NOT EXISTS everyone_else ON ${CLICKHOUSE_DB}.cost_records FOR SELECT
    USING 1 TO ALL EXCEPT costguard_tenant_reader;

CREATE ROW POLICY IF NOT EXISTS tenant_only ON ${CLICKHOUSE_DB}.agent_operations FOR SELECT
    USING tenant_id = toUUIDOrZero(getSetting('SQL_tenant_id')) TO costguard_tenant_reader;
CREATE ROW POLICY IF NOT EXISTS everyone_else ON ${CLICKHOUSE_DB}.agent_operations FOR SELECT
    USING 1 TO ALL EXCEPT costguard_tenant_reader;

CREATE USER IF NOT EXISTS costguard_reader IDENTIFIED WITH sha256_password BY '${CLICKHOUSE_READER_PASSWORD}'
    DEFAULT ROLE costguard_tenant_reader;
GRANT costguard_tenant_reader TO costguard_reader;
