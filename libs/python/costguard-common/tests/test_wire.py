import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

import pytest

from costguard_common import schemas, wire
from costguard_common.focus import CostRecord, CostSource

# What ai.costguard.events.wire.AvroWire wrote for this AgentState with schema id 42; see the Java test
# AvroPublisherTest. Fixed bytes keep both sides honest.
AGENT = uuid.UUID("11111111-1111-1111-1111-111111111111")
TENANT = uuid.UUID("22222222-2222-2222-2222-222222222222")


def agent_state() -> dict:
    return {
        "tenant_id": str(TENANT), "agent_id": str(AGENT), "virtual_key_hashes": ["hash"], "suspended": False,
        "mandate_version": 3, "mandate_expression": "cost <= 1.0", "approval_threshold": Decimal("300.000000"),
        "updated_at": datetime(2026, 11, 7, 10, 0, tzinfo=UTC),
    }


def test_every_schema_parses():
    assert len(schemas.topics()) == 21
    for topic in schemas.topics():
        schemas.parsed(topic)


def test_round_trip_keeps_logical_types():
    schema = schemas.parsed("agents.state.v1")
    message = wire.encode(42, schema, agent_state())

    assert message[0] == 0
    assert wire.schema_id(message) == 42
    back = wire.decode(message, schema)
    assert back["approval_threshold"] == Decimal("300.000000")
    assert str(back["agent_id"]) == str(AGENT)
    assert back["updated_at"] == datetime(2026, 11, 7, 10, 0, tzinfo=UTC)


def test_bytes_match_the_java_encoder():
    message = wire.encode(42, schemas.parsed("agents.state.v1"), agent_state())

    expected = bytes.fromhex(JAVA_AGENT_STATE_HEX)
    assert message == expected


def test_rejects_other_formats():
    with pytest.raises(ValueError):
        wire.schema_id(b"\x01abcd")


def test_cost_record_has_a_stable_id_and_six_decimals():
    record = CostRecord(
        id=CostRecord.stable_id(CostSource.INVOICE_LINE, "inv-1:3"), tenant_id=TENANT, source=CostSource.INVOICE_LINE,
        source_id="inv-1:3", billed_cost=Decimal("12.3"), effective_cost=Decimal("12.3"), billing_currency="rub",
        charge_period_start=date(2026, 11, 1), charge_period_end=date(2026, 11, 30), service_provider_name="Zoom")

    assert record.id == CostRecord.stable_id(CostSource.INVOICE_LINE, "inv-1:3")
    assert record.billing_currency == "RUB"
    assert wire.decode(wire.encode(1, schemas.parsed("cost.records.v1"), record.avro()),
                       schemas.parsed("cost.records.v1"))["billed_cost"] == Decimal("12.300000")


JAVA_AGENT_STATE_HEX = (
    "000000002a4832323232323232322d323232322d323232322d323232322d3232323232323232323232324831313131313131312d"
    "313131312d313131312d313131312d313131313131313131313131020868617368000002060216636f7374203c3d20312e30020811"
    "e1a30080849bd7b668"
)
