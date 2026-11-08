"""Messages that a schema of schemas/avro allows, any of them: what a consumer must take without failing.

Each sample is written with the schema and read back, so it reaches the consumer exactly as the codec decodes a
Kafka message: uuid.UUID, Decimal, datetime and date for the logical types. Values range over what the schema
permits, not what a friendly producer sends: empty strings, nulls in every optional field, zero and negative
amounts, empty and long arrays.
"""

from __future__ import annotations

import io
import random
import uuid
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal

import fastavro

from costguard_common import schemas

WORDS = ["", "a", "ООО «Ромашка»", "Тучка CRM", "x" * 200, "agent 7f1c0d1e-0000-0000-0000-000000000001", " ", "0"]


def _value(schema, rng: random.Random, named: dict):
    if isinstance(schema, str):
        if schema in named:
            return _value(named[schema], rng, named)
        return {"null": None, "boolean": rng.random() < 0.5, "int": rng.choice([0, 1, -1, 7, 2**31 - 1]),
                "long": rng.choice([0, 1, -5, 10**12]), "float": rng.uniform(-1e3, 1e3),
                "double": rng.choice([0.0, -1.5, 1e9, 3.14]), "string": rng.choice(WORDS),
                "bytes": rng.randbytes(rng.randint(0, 8))}[schema]
    if isinstance(schema, list):
        return _value(rng.choice(schema), rng, named)
    kind, logical = schema.get("type"), schema.get("logicalType")
    if logical == "uuid":
        return uuid.UUID(int=rng.getrandbits(128), version=4)
    if logical == "timestamp-millis":
        return datetime(2026, 1, 1, tzinfo=UTC) + timedelta(milliseconds=rng.randint(0, 10**11))
    if logical == "date":
        return date(2026, 1, 1) + timedelta(days=rng.randint(-400, 400))
    if logical == "decimal":
        scale = schema.get("scale", 0)
        digits = schema.get("precision", 10) - scale - 1
        whole = rng.choice([0, 1, -1, 10 ** min(digits, 6), -(10 ** min(digits, 3))])
        return (Decimal(whole) + Decimal(rng.randint(0, 999)) / 1000).quantize(Decimal(1).scaleb(-scale))
    if kind == "record":
        named[schema["name"]] = schema
        named[schema.get("namespace", "") + "." + schema["name"]] = schema
        return {f["name"]: _value(f["type"], rng, named) for f in schema["fields"]}
    if kind == "enum":
        named[schema["name"]] = schema
        return rng.choice(schema["symbols"])
    if kind == "array":
        return [_value(schema["items"], rng, named) for _ in range(rng.choice([0, 1, 3]))]
    if kind == "map":
        return {rng.choice(WORDS) or "k": _value(schema["values"], rng, named) for _ in range(rng.choice([0, 2]))}
    if kind == "fixed":
        return rng.randbytes(schema["size"])
    return _value(kind, rng, named)


def samples(topic: str, n: int = 40, seed: int = 0) -> list[dict]:
    """n messages of the topic as a consumer gets them."""
    raw = schemas.raw(topic)
    parsed = schemas.parsed(topic)
    rng = random.Random(f"{topic}:{seed}")
    out = []
    for _ in range(n):
        value = _value(raw, rng, {})
        buffer = io.BytesIO()
        fastavro.schemaless_writer(buffer, parsed, value)
        out.append(fastavro.schemaless_reader(io.BytesIO(buffer.getvalue()), parsed))
    return out


def fits(topic: str, value: dict) -> dict:
    """The value written with the topic's schema and read back; raises when it does not fit."""
    parsed = schemas.parsed(topic)
    buffer = io.BytesIO()
    fastavro.schemaless_writer(buffer, parsed, value)
    return fastavro.schemaless_reader(io.BytesIO(buffer.getvalue()), parsed)
