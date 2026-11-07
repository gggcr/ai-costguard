import io
import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import fastavro

from costguard_anomaly import service
from costguard_anomaly.detector import Detector
from costguard_common import schemas

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
START = date(2026, 10, 1)


def record(amount, day, provider="agent 7f1c0d1e-0000-0000-0000-000000000001", source="OPERATION", sid=None):
    return {"id": str(uuid.uuid4()), "tenant_id": str(TENANT), "source": source, "source_id": sid or str(uuid.uuid4()),
            "billed_cost": Decimal(amount), "effective_cost": Decimal(amount), "billing_currency": "RUB",
            "charge_period_start": day, "charge_period_end": day, "service_provider_name": provider,
            "department_id": None}


def history(detector, days=20, base=100):
    for i in range(days):
        assert detector.add(record(base + (i % 5), START + timedelta(days=i))) == []


def test_spike_is_found_and_grows():
    d = Detector()
    history(d)
    day = START + timedelta(days=20)
    assert d.add(record(150, day)) == []  # within the usual range
    [spike] = d.add(record(400, day))
    assert spike.rule == "anomaly.spike"
    assert spike.expected_savings == Decimal(550) - Decimal(102)
    assert "agent:7f1c0d1e-0000-0000-0000-000000000001" in spike.entity_ids
    [again] = d.add(record(100, day))
    assert again.fingerprint == spike.fingerprint and again.expected_savings > spike.expected_savings


def test_no_spike_without_enough_history():
    d = Detector()
    history(d, days=5)
    assert d.add(record(10_000, START + timedelta(days=5))) == []


def test_steady_spend_with_zero_spread_still_needs_a_real_jump():
    d = Detector()
    for i in range(10):
        d.add(record(100, START + timedelta(days=i)))
    day = START + timedelta(days=10)
    assert d.add(record(150, day)) == []
    assert d.add(record(100, day))[0].rule == "anomaly.spike"


def test_double_charge():
    d = Detector()
    day = START
    assert d.add(record(5000, day, "Ромашка", "INVOICE_LINE", "a.csv:2")) == []
    assert d.add(record(5000, day, "Ромашка", "INVOICE_LINE", "a.csv:2")) == []  # same line again is not double
    [double] = d.add(record(5000, day, "Ромашка", "PAYMENT", "statement:7"))
    assert double.rule == "anomaly.double-charge" and double.expected_savings == 5000
    assert d.add(record(5000, day, "agent x", "OPERATION")) == []


def test_redelivered_record_changes_nothing():
    d = Detector()
    history(d)
    r = record(1000, START + timedelta(days=20))
    assert d.add(r)
    assert d.add(r) == []


def test_forget_keeps_memory_bounded():
    d = Detector()
    history(d)
    d.forget_before(START + timedelta(days=200))
    assert all(not s.days for s in d._series.values())


def test_finding_fits_the_schema():
    d = Detector()
    history(d)
    [a] = d.add(record(1000, START + timedelta(days=20)))
    now = datetime(2026, 10, 22, tzinfo=UTC)
    value = service.finding(a, now)
    schema = schemas.parsed("findings.v1")
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schema, value)
    assert fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schema)["rule"] == "anomaly.spike"
    assert service.worth_reporting(a, now)
    assert not service.worth_reporting(a, now + timedelta(days=10))
