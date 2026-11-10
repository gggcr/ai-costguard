"""Runs the migrations against a real ClickHouse: CLICKHOUSE_TEST_URL=http://localhost:18123 (user default, no
password). Skipped without it. The Kafka tables are created but never reach a broker here; the Avro test feeds
the same bytes the Kafka engine would decode, as an Avro file, into a table of the queue's structure.
"""

import importlib.util
import io
import os
import uuid
from datetime import date
from decimal import Decimal
from pathlib import Path

import fastavro
import httpx
import pytest

from costguard_common import schemas

URL = os.environ.get("CLICKHOUSE_TEST_URL")
pytestmark = pytest.mark.skipif(not URL, reason="CLICKHOUSE_TEST_URL is not set")

spec = importlib.util.spec_from_file_location("apply", Path(__file__).parents[1] / "apply.py")
apply = importlib.util.module_from_spec(spec)
spec.loader.exec_module(apply)

ENV = {"KAFKA_BROKERS": "localhost:1", "SCHEMA_REGISTRY_URL": "http://localhost:1"}
TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")


@pytest.fixture(scope="module")
def db():
    client = httpx.Client(base_url=URL, timeout=60, trust_env=False)  # a system proxy must not see localhost
    name = f"t_{uuid.uuid4().hex[:8]}"
    client.post("/", content=f"CREATE DATABASE {name}".encode()).raise_for_status()

    def q(sql: str, body: bytes | None = None) -> str:
        r = client.post("/", params={"database": name, **({"query": sql} if body is not None else {})},
                        content=body if body is not None else sql.encode())
        assert r.status_code == 200, r.text
        return r.text

    yield client, name, q
    client.post("/", content=f"DROP DATABASE {name}".encode())


def test_migrations_apply_once(db):
    client, name, q = db
    assert apply.apply(client, name, ENV) == ["001_cost_records.sql", "002_agent_operations.sql",
                                              "003_spend_views.sql", "004_spend_by_department.sql"]
    assert apply.apply(client, name, ENV) == []
    assert "cost_records_queue" in q("SHOW TABLES")


def test_newest_version_wins_and_months_add_up(db):
    _, _, q = db
    rid = uuid.uuid4()
    q(f"""INSERT INTO cost_records (id, tenant_id, source, source_id, billed_cost, effective_cost, billing_currency,
          charge_period_start, charge_period_end, service_provider_name, ingested_at) VALUES
          ('{rid}', '{TENANT}', 'INVOICE_LINE', 'f:2', 100, 100, 'RUB', '2026-10-05', '2026-10-05', 'Ромашка',
           '2026-10-06 00:00:00'),
          ('{rid}', '{TENANT}', 'INVOICE_LINE', 'f:2', 120, 120, 'RUB', '2026-10-05', '2026-10-05', 'Ромашка',
           '2026-10-07 00:00:00'),
          ('{uuid.uuid4()}', '{TENANT}', 'OPERATION', 'op', 30.5, 30.5, 'RUB', '2026-10-20', '2026-10-20',
           'agent x', '2026-10-21 00:00:00')""")
    rows = q(f"SELECT month, source, billed FROM spend_by_month WHERE tenant_id = '{TENANT}' "
             "ORDER BY source FORMAT TSV").splitlines()
    assert rows == ["2026-10-01\tINVOICE_LINE\t120", "2026-10-01\tOPERATION\t30.5"]


def test_departments_add_up_to_the_company(db):
    _, _, q = db
    tenant = uuid.uuid4()
    department = uuid.uuid4()
    q(f"""INSERT INTO cost_records (id, tenant_id, source, source_id, billed_cost, effective_cost, billing_currency,
          charge_period_start, charge_period_end, service_provider_name, department_id) VALUES
          ('{uuid.uuid4()}', '{tenant}', 'INVOICE_LINE', 'a', 100, 100, 'RUB', '2026-10-05', '2026-10-05', 'x',
           '{department}'),
          ('{uuid.uuid4()}', '{tenant}', 'INVOICE_LINE', 'b', 40, 40, 'RUB', '2026-10-09', '2026-10-09', 'y', NULL)""")
    rows = q(f"SELECT department, billed FROM spend_by_department_month WHERE tenant_id = '{tenant}' "
             "ORDER BY department FORMAT TSV").splitlines()
    assert rows == ["	40", f"{department}	100"]


def test_avro_of_the_contract_fits_the_queue_columns(db):
    _, _, q = db
    q("CREATE TABLE cost_check AS cost_records_queue ENGINE = Memory")
    record = {"id": str(uuid.uuid4()), "tenant_id": str(TENANT), "source": "PAYMENT", "source_id": "st:7",
              "billed_cost": Decimal("12000.500000"), "effective_cost": Decimal("12000.500000"),
              "billing_currency": "RUB", "charge_period_start": date(2026, 10, 5),
              "charge_period_end": date(2026, 10, 5), "service_provider_name": "ООО «Ромашка»",
              "department_id": None}
    out = io.BytesIO()
    fastavro.writer(out, schemas.parsed("cost.records.v1"), [record])
    q("INSERT INTO cost_check FORMAT Avro", out.getvalue())
    assert q("SELECT source, billed_cost, charge_period_start, service_provider_name, department_id FROM cost_check "
             "FORMAT TSV").strip() == "PAYMENT\t12000.5\t2026-10-05\tООО «Ромашка»\t\\N"


def test_avro_of_operations_fits(db):
    _, _, q = db
    q("CREATE TABLE ops_check AS agent_operations_queue ENGINE = Memory")
    from datetime import UTC, datetime

    record = {"operation_id": str(uuid.uuid4()), "tenant_id": str(TENANT), "agent_id": str(uuid.uuid4()),
              "task_id": None, "kind": "LLM_CALL", "provider": "openai", "model": "gpt-x", "decision": "ALLOWED",
              "denial_code": None, "estimated_cost": Decimal("1.250000"), "actual_cost": Decimal("1.100000"),
              "currency": "RUB", "input_tokens": 100, "output_tokens": 50, "mandate_version": 3,
              "prompt_simhash": None, "occurred_at": datetime(2026, 10, 5, 12, 0, tzinfo=UTC)}
    out = io.BytesIO()
    fastavro.writer(out, schemas.parsed("agent.operations.v1"), [record])
    q("INSERT INTO ops_check FORMAT Avro", out.getvalue())
    assert q("SELECT decision, actual_cost, occurred_at FROM ops_check FORMAT TSV").strip() == \
        "ALLOWED\t1.1\t2026-10-05 12:00:00.000"


def test_missing_variable_stops(db):
    with pytest.raises(SystemExit, match="KAFKA_BROKERS"):
        apply.render("x '${KAFKA_BROKERS}'", {})


def test_a_reader_sees_only_the_company_it_names(db):
    """Row policies (ADR 0035). CI runs ClickHouse with access management for the default user
    (tests/users.d/access-management.xml); a server without it cannot hold them, and the test says so."""
    client, name, q = db
    probe = client.post("/", content=b"CREATE ROLE IF NOT EXISTS costguard_tenant_reader")
    if probe.status_code != 200:
        # CI mounts the config, so there a refusal is a failure, not a reason to skip
        assert not os.environ.get("CLICKHOUSE_ACCESS_TEST"), probe.text
        pytest.skip("the test user may not manage access")
    assert apply.apply_access(client, name, {"CLICKHOUSE_READER_PASSWORD": "reader-test"}) == ["001_row_policies.sql"]
    assert apply.apply_access(client, name, {"CLICKHOUSE_READER_PASSWORD": "reader-test"}) == []
    mine, theirs = uuid.uuid4(), uuid.uuid4()
    q(f"""INSERT INTO cost_records (id, tenant_id, source, source_id, billed_cost, effective_cost, billing_currency,
          charge_period_start, charge_period_end, service_provider_name) VALUES
          ('{uuid.uuid4()}', '{mine}', 'INVOICE_LINE', 'm', 10, 10, 'RUB', '2026-10-05', '2026-10-05', 'x'),
          ('{uuid.uuid4()}', '{theirs}', 'INVOICE_LINE', 't', 20, 20, 'RUB', '2026-10-05', '2026-10-05', 'y')""")

    def as_reader(sql: str, **settings) -> httpx.Response:
        return client.post("/", params={"database": name, **settings}, content=sql.encode(),
                           headers={"X-ClickHouse-User": "costguard_reader", "X-ClickHouse-Key": "reader-test"})

    among = f"WHERE tenant_id IN ('{mine}', '{theirs}')"
    r = as_reader(f"SELECT toString(tenant_id) FROM cost_records {among} FORMAT TSV", SQL_tenant_id=str(mine))
    assert r.status_code == 200 and r.text.split() == [str(mine)]
    # through the views as well
    r = as_reader(f"SELECT sum(billed) FROM spend_by_month {among} FORMAT TSV", SQL_tenant_id=str(mine))
    assert r.status_code == 200 and r.text.strip() == "10"
    # naming no company is an error, not every company
    assert as_reader(f"SELECT count() FROM cost_records {among}").status_code != 200
    # nothing else of the database: raw Kafka messages may carry any company
    assert as_reader("SELECT count() FROM kafka_errors", SQL_tenant_id=str(mine)).status_code != 200
    # everyone else still sees every company
    assert q(f"SELECT count() FROM cost_records {among} FORMAT TSV").strip() == "2"
