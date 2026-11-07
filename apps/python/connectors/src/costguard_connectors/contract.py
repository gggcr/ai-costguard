"""What every connector does, and how its findings become Kafka messages.

A connector reads one system and returns a Batch: entities for ingest.normalized.v1, cost records for
cost.records.v1, and the cursor to start from next time. It never publishes itself, so it can be tested with a
fake HTTP transport and run either by the platform or by the agent inside the client's network.

Connectors have no uploaded file, but ingest.normalized.v1 wants a file id; it is derived from the tenant and the
connector name, so the core sees every pull of one connector as one source.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable
from dataclasses import dataclass, field
from datetime import UTC, datetime
from typing import Protocol

from costguard_common import normalized
from costguard_common.focus import CostRecord

COSTS = "cost.records.v1"


@dataclass
class Batch:
    entities: list[normalized.Entity] = field(default_factory=list)
    costs: list[CostRecord] = field(default_factory=list)
    cursor: str | None = None


class Connector(Protocol):
    name: str

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch: ...


@dataclass(frozen=True)
class Message:
    topic: str
    key: str
    value: dict


def source_id(tenant: uuid.UUID, connector: str) -> uuid.UUID:
    return uuid.uuid5(uuid.NAMESPACE_URL, f"costguard:connector:{tenant}:{connector}")


def messages(tenant: uuid.UUID, connector: str, batch: Batch, now: datetime | None = None) -> list[Message]:
    """Entities first, each key once (the last report wins), then the costs."""
    now = now or datetime.now(UTC)
    source = source_id(tenant, connector)
    latest: dict[str, normalized.Entity] = {}
    for e in batch.entities:
        latest[e.key] = e
    out = [Message(normalized.TOPIC, e.key, e.message(tenant, source, now)) for e in latest.values()]
    out += [Message(COSTS, str(tenant), c.avro()) for c in batch.costs]
    return out


def run(connector: Connector, tenant: uuid.UUID, cursor: str | None,
        publish: Callable[[list[Message]], None]) -> str | None:
    """One pull. The new cursor is returned only after publish returned, so a failed publish repeats the pull."""
    batch = connector.pull(tenant, cursor)
    publish(messages(tenant, connector.name, batch))
    return batch.cursor if batch.cursor is not None else cursor
