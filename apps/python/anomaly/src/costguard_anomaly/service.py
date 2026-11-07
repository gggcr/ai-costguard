"""The Kafka loop: cost.records.v1 in, findings.v1 out.

The detector keeps its history in memory, so on start it reads cost.records.v1 from the beginning and never
commits offsets. Findings for days older than REPORT_DAYS are not published while it catches up: they were
published before, and findings are idempotent by fingerprint anyway. Long history belongs in ClickHouse.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

from costguard_anomaly.detector import Anomaly, Detector
from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec

log = logging.getLogger("costguard.anomaly")

COSTS = "cost.records.v1"
FINDINGS = "findings.v1"
REPORT_DAYS = 3


def finding(a: Anomaly, now: datetime) -> dict:
    return {"fingerprint": a.fingerprint, "tenant_id": str(a.tenant_id), "rule": a.rule, "title": a.title,
            "expected_savings": a.expected_savings.quantize(a.expected_savings.__class__("0.000001")),
            "currency": a.currency, "entity_ids": a.entity_ids, "detected_at": now}


def worth_reporting(a: Anomaly, now: datetime) -> bool:
    return a.day >= (now - timedelta(days=REPORT_DAYS)).date()


def run(settings: Settings) -> None:
    from confluent_kafka import Consumer, KafkaException, Producer

    codec = Codec(SchemaRegistry(settings.schema_registry_url))
    consumer = Consumer({"bootstrap.servers": settings.kafka_bootstrap_servers,
                         "group.id": f"anomaly-{uuid.uuid4()}", "enable.auto.commit": False,
                         "auto.offset.reset": "earliest"})
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers, "enable.idempotence": True,
                         "acks": "all"})
    detector = Detector()
    consumer.subscribe([COSTS])
    log.info("Anomaly detector reads %s", COSTS)
    last_cleanup = datetime.now(UTC)
    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is None:
                continue
            if msg.error():
                raise KafkaException(msg.error())
            now = datetime.now(UTC)
            for a in detector.add(codec.decode(COSTS, msg.value())):
                if worth_reporting(a, now):
                    producer.produce(FINDINGS, key=a.fingerprint.encode(), value=codec.encode(FINDINGS,
                                                                                               finding(a, now)))
            producer.poll(0)
            if now - last_cleanup > timedelta(hours=1):
                detector.forget_before(now.date())
                last_cleanup = now
    finally:
        producer.flush(30)
        consumer.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    run(Settings())


if __name__ == "__main__":
    main()
