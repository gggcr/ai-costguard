import io
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

import pyarrow as pa
import pyarrow.parquet as pq
import pytest

from costguard_ingest import pipeline, tables


def focus_parquet() -> bytes:
    """Columns typed the way AWS Data Exports write FOCUS 1.0: decimals, timestamps with a zone, nulls."""
    table = pa.table({
        "BilledCost": pa.array([Decimal("12.345678"), Decimal("0.000001"), Decimal("0")], pa.decimal128(20, 10)),
        "BillingCurrency": ["USD", "USD", "USD"],
        "ChargePeriodStart": pa.array([datetime(2026, 10, 1, tzinfo=UTC), datetime(2026, 10, 2, 13, tzinfo=UTC),
                                       datetime(2026, 10, 3, tzinfo=UTC)], pa.timestamp("ms", tz="UTC")),
        "ChargePeriodEnd": pa.array([datetime(2026, 10, 2, tzinfo=UTC), datetime(2026, 10, 2, 14, tzinfo=UTC),
                                     datetime(2026, 10, 4, tzinfo=UTC)], pa.timestamp("ms", tz="UTC")),
        "ProviderName": ["AWS", "AWS", "AWS"],
        "ServiceName": ["Amazon EC2", "Amazon S3", None],
    })
    out = io.BytesIO()
    pq.write_table(table, out, compression="snappy")
    return out.getvalue()


def test_a_focus_export_in_parquet_is_read_with_its_types():
    content = focus_parquet()
    t = tables.read("part-00001.snappy.parquet", content)
    assert t.columns[:2] == ["BilledCost", "BillingCurrency"] and len(t.rows) == 3
    parsed = pipeline.parse("part-00001.snappy.parquet", content)
    assert parsed.suggested_mapping["BilledCost"] == "amount" and parsed.errors == []
    assert parsed.total == Decimal("12.345679")
    messages, skipped = pipeline.accept(uuid.uuid4(), uuid.uuid4(), "k", "part.parquet", None,
                                        parsed.suggested_mapping, {}, content)
    costs = [m.value for m in messages if m.topic == "cost.records.v1"]
    # a timestamp with a zone becomes the day of the charge; a free row stays, at zero
    assert [c["charge_period_start"] for c in costs] == [date(2026, 10, 1), date(2026, 10, 2), date(2026, 10, 3)]
    assert [Decimal(str(c["billed_cost"])) for c in costs] == [Decimal("12.345678"), Decimal("0.000001"), 0]
    assert skipped == []


def test_a_broken_parquet_file_says_so():
    with pytest.raises(tables.Unsupported, match="Parquet"):
        tables.read("x.parquet", b"PAR1 not really")
