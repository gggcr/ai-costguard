"""The Schema Registry wire format, byte for byte what ai.costguard.events.wire.AvroWire writes.

Logical types follow the schemas: uuid is a str or uuid.UUID, decimal a decimal.Decimal with scale 6,
timestamp-millis a timezone-aware datetime, date a datetime.date.
"""

from __future__ import annotations

import io
import struct

import fastavro

from costguard_common import schemas
from costguard_common.registry import SchemaRegistry

MAGIC = 0


def encode(schema_id: int, schema, record: dict) -> bytes:
    out = io.BytesIO()
    out.write(struct.pack(">bI", MAGIC, schema_id))
    fastavro.schemaless_writer(out, schema, record)
    return out.getvalue()


def schema_id(message: bytes) -> int:
    if len(message) < 5 or message[0] != MAGIC:
        raise ValueError("Not in the Schema Registry wire format")
    return struct.unpack(">I", message[1:5])[0]


def decode(message: bytes, writer, reader=None) -> dict:
    return fastavro.schemaless_reader(io.BytesIO(message[5:]), writer, reader)


class Codec:
    """Encodes for a topic with the schema of schemas/avro, decodes with the writer's schema from the registry."""

    def __init__(self, registry: SchemaRegistry) -> None:
        self._registry = registry

    def encode(self, topic: str, record: dict) -> bytes:
        return encode(self._registry.id_of(topic, schemas.raw(topic)), schemas.parsed(topic), record)

    def decode(self, topic: str, message: bytes) -> dict:
        return decode(message, self._registry.schema_of(schema_id(message)), schemas.parsed(topic))
