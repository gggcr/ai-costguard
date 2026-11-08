"""What the core says the agent should pull, and what the agent tells the core it did (ADR 0019).

- connectors.config.v1 (compacted, from the core): the systems a company connected on the page, with their settings
  that are not secrets, whether they are still connected and whether a person asked to sync now. It is merged over
  the agent's own file: a connected system adds or replaces its section, a disconnected one removes it. Secrets
  stay in the agent's environment, as before.
- connectors.runs.v1 (to the core): one report per pull of a connector, with the number of records it published
  and, for a failure, the error with every secret of the environment blanked out.
"""

from __future__ import annotations

import re
import uuid
from collections.abc import Iterable
from datetime import datetime

from costguard_connectors import contract

CONFIG = "connectors.config.v1"
RUNS = "connectors.runs.v1"
# The connector's name where it differs from its section of the config
TYPE_OF = {"1c": "onec"}
LISTS = {"ignore", "accounts", "intermediaries"}
NUMBERS = {"min_image_kb", "min_image_side"}
_SECRET_NAME = re.compile(r"(TOKEN|PASSWORD|SECRET|KEY|WEBHOOK)$")


def type_of(connector) -> str:
    return TYPE_OF.get(connector.name, connector.name)


def _setting(key: str, value: str):
    if key in LISTS:
        return [v.strip() for v in value.split(",") if v.strip()]
    if key in NUMBERS:
        return int(value)
    return value


def latest(records: Iterable[dict], tenant: uuid.UUID) -> dict[str, dict]:
    """The latest state of each connection of the tenant, by type."""
    out: dict[str, dict] = {}
    for r in records:
        if str(r["tenant_id"]) != str(tenant):
            continue
        known = out.get(r["type"])
        if known is None or r["updated_at"] >= known["updated_at"]:
            out[r["type"]] = r
    return out


def merge(config: dict, connections: dict[str, dict]) -> tuple[dict, dict[str, uuid.UUID]]:
    """The agent's config with the core's connections over it, and the connection id of each type."""
    merged = {k: (dict(v) if isinstance(v, dict) else v) for k, v in config.items()}
    ids: dict[str, uuid.UUID] = {}
    for kind, r in connections.items():
        ids[kind] = uuid.UUID(str(r["connection_id"]))
        if r["enabled"]:
            merged[kind] = {**(merged.get(kind) or {}),
                            **{k: _setting(k, v) for k, v in (r.get("settings") or {}).items()}}
        else:
            merged.pop(kind, None)
    return merged, ids


def due(connections: dict[str, dict], last_started: dict[str, datetime]) -> set[str]:
    """Types a person asked to sync since the agent last started them."""
    out = set()
    for kind, r in connections.items():
        asked = r.get("sync_requested_at")
        if r["enabled"] and asked is not None and (kind not in last_started or last_started[kind] < asked):
            out.add(kind)
    return out


def redact(text: str, env: dict[str, str]) -> str:
    """The error without any secret of the environment: an URL with a webhook token, a password in a message."""
    for name, value in env.items():
        if value and len(value) >= 6 and _SECRET_NAME.search(name.upper()):
            text = text.replace(value, "***")
    return text[:2000]


def run_message(tenant: uuid.UUID, kind: str, connection_id: uuid.UUID | None, started: datetime,
                finished: datetime, records: int, error: str | None) -> contract.Message:
    return contract.Message(RUNS, f"{tenant}:{kind}", {
        "run_id": str(uuid.uuid4()), "tenant_id": str(tenant),
        "connection_id": None if connection_id is None else str(connection_id), "type": kind,
        "started_at": started, "finished_at": finished, "status": "FAILED" if error else "SUCCEEDED",
        "records": int(records), "error": error})


def read_config(bootstrap: str, registry_url: str, tenant: uuid.UUID, timeout: float = 30) -> dict[str, dict]:
    """The whole compacted topic, read from the start to its end, for one tenant."""
    import time

    from confluent_kafka import OFFSET_BEGINNING, Consumer, TopicPartition

    from costguard_common.registry import SchemaRegistry
    from costguard_common.wire import Codec

    codec = Codec(SchemaRegistry(registry_url))
    consumer = Consumer({"bootstrap.servers": bootstrap, "group.id": f"connector-agent-{uuid.uuid4()}",
                         "enable.auto.commit": False})
    try:
        partitions = consumer.list_topics(CONFIG, timeout=timeout).topics[CONFIG].partitions
        consumer.assign([TopicPartition(CONFIG, p, OFFSET_BEGINNING) for p in partitions])
        ends = {p: consumer.get_watermark_offsets(TopicPartition(CONFIG, p), timeout=timeout)[1] for p in partitions}
        done = {p for p, end in ends.items() if end == 0}
        records = []
        deadline = time.monotonic() + timeout
        while len(done) < len(ends) and time.monotonic() < deadline:
            msg = consumer.poll(1.0)
            if msg is None or msg.error():
                continue
            if msg.value() is not None:
                records.append(codec.decode(CONFIG, msg.value()))
            if msg.offset() + 1 >= ends[msg.partition()]:
                done.add(msg.partition())
        return latest(records, tenant)
    finally:
        consumer.close()
