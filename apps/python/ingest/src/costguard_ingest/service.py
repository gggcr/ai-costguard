"""The Kafka loop: ingest.files.v1 and ingest.accepted.v1 in; ingest.results.v1, cost.records.v1 and
ingest.normalized.v1 out.

The offset is committed only after every output message is acknowledged, so a crash replays the input; the
outputs carry stable keys and ids, and the core ignores a result for a file that already moved on.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime

from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec
from costguard_ingest import pipeline, storage, tables

log = logging.getLogger("costguard.ingest")

FILES = "ingest.files.v1"
ACCEPTED = "ingest.accepted.v1"
RESULTS = "ingest.results.v1"


class IngestSettings(Settings):
    ingest_group_id: str = "ingest-pipeline"
    ingest_storage_dir: str = ""
    ingest_s3_bucket: str = "costguard-imports"


def result_of(file: dict, parsed: pipeline.Parsed | None, failure: str | None) -> dict:
    return {
        "file_id": str(file["file_id"]), "tenant_id": str(file["tenant_id"]),
        "status": "FAILED" if failure else "PARSED",
        "columns": parsed.columns if parsed else [], "suggested_mapping": parsed.suggested_mapping if parsed else {},
        "rows": parsed.rows if parsed else 0,
        "total": (parsed.total if parsed else pipeline.Decimal(0)).quantize(pipeline.Decimal("0.000001")),
        "errors": [{"row": r, "message": m} for r, m in parsed.errors] if parsed else [],
        "uncertain": [{"row": r, "field": f, "value": v, "confidence": c} for r, f, v, c in parsed.uncertain]
        if parsed else [],
        "failure": failure, "reported_at": datetime.now(UTC),
    }


class Handler:
    """What one input message turns into. Kept apart from the consumer so tests can call it directly."""

    def __init__(self, files: storage.Files) -> None:
        self._files = files

    def on_file(self, file: dict) -> list[pipeline.Message]:
        key = str(file["file_id"])
        try:
            parsed = pipeline.parse(file["file_name"], self._files.get(file["s3_key"]))
        except tables.Unsupported as e:
            return [pipeline.Message(RESULTS, key, result_of(file, None, str(e)))]
        except FileNotFoundError:
            return [pipeline.Message(RESULTS, key, result_of(file, None, "The file is not in storage"))]
        return [pipeline.Message(RESULTS, key, result_of(file, parsed, None))]

    def on_accepted(self, accepted: dict) -> list[pipeline.Message]:
        vendor = accepted.get("vendor_id")
        messages, skipped = pipeline.accept(
            uuid.UUID(str(accepted["tenant_id"])), uuid.UUID(str(accepted["file_id"])), accepted["s3_key"],
            accepted["file_name"], None if vendor is None else uuid.UUID(str(vendor)), accepted["mapping"],
            accepted["corrections"], self._files.get(accepted["s3_key"]))
        for row, reason in skipped:
            log.warning("File %s row %s not written: %s", accepted["file_id"], row, reason)
        return messages


def run(settings: IngestSettings) -> None:
    from confluent_kafka import Consumer, KafkaException, Producer

    files: storage.Files = (storage.LocalFiles(settings.ingest_storage_dir) if settings.ingest_storage_dir
                            else storage.S3Files(settings.s3_endpoint, settings.s3_access_key,
                                                 settings.s3_secret_key, settings.ingest_s3_bucket))
    handler = Handler(files)
    codec = Codec(SchemaRegistry(settings.schema_registry_url))
    consumer = Consumer({"bootstrap.servers": settings.kafka_bootstrap_servers, "group.id": settings.ingest_group_id,
                         "enable.auto.commit": False, "auto.offset.reset": "earliest"})
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers, "enable.idempotence": True,
                         "acks": "all"})
    consumer.subscribe([FILES, ACCEPTED])
    log.info("Ingest pipeline reads %s and %s", FILES, ACCEPTED)
    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                raise KafkaException(msg.error())
            record = codec.decode(msg.topic(), msg.value())
            out = handler.on_file(record) if msg.topic() == FILES else handler.on_accepted(record)
            failed: list[str] = []
            for m in out:
                producer.produce(m.topic, key=m.key.encode(), value=codec.encode(m.topic, m.value),
                                 on_delivery=lambda err, _m, failed=failed: failed.append(str(err)) if err else None)
            producer.flush(30)
            if failed or len(producer):
                raise RuntimeError(f"Output not delivered, the input will be read again: {failed[:3]}")
            consumer.commit(message=msg, asynchronous=False)
    finally:
        consumer.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    run(IngestSettings())


if __name__ == "__main__":
    main()
