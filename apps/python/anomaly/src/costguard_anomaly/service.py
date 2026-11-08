"""The Kafka loop of the detector, two inputs and two outputs.

- cost.records.v1 in, findings.v1 out: spend spikes and double charges (costguard_anomaly.detector).
- agent.operations.v1 in, agent.risk-signals.v1 out: the risk of every agent and task, rules and models
  (costguard_anomaly.scorer, ADR 0016).

The detector keeps its history in memory, so on start it reads both topics from the beginning and never commits
offsets. While it catches up it publishes nothing old: findings for days older than REPORT_DAYS and signals for
operations older than SIGNAL_MINUTES were published before. The models come back from MLflow
(MLFLOW_TRACKING_URI) instead of being learned again, and are saved there as they learn and on shutdown.
"""

from __future__ import annotations

import logging
import uuid
from datetime import UTC, datetime, timedelta

from costguard_anomaly import models as modelling
from costguard_anomaly.detector import Anomaly, Detector
from costguard_anomaly.risk import Rules, Score
from costguard_anomaly.scorer import Scorer, signal
from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec

log = logging.getLogger("costguard.anomaly")

COSTS = "cost.records.v1"
FINDINGS = "findings.v1"
OPERATIONS = "agent.operations.v1"
SIGNALS = "agent.risk-signals.v1"
REPORT_DAYS = 3
SIGNAL_MINUTES = 10


class AnomalySettings(Settings):
    mlflow_tracking_uri: str = ""
    anomaly_time_zone: str = "Europe/Moscow"
    anomaly_models_save_every: int = 5000


def finding(a: Anomaly, now: datetime) -> dict:
    return {"fingerprint": a.fingerprint, "tenant_id": str(a.tenant_id), "rule": a.rule, "title": a.title,
            "expected_savings": a.expected_savings.quantize(a.expected_savings.__class__("0.000001")),
            "currency": a.currency, "entity_ids": a.entity_ids, "detected_at": now}


def worth_reporting(a: Anomaly, now: datetime) -> bool:
    return a.day >= (now - timedelta(days=REPORT_DAYS)).date()


def worth_signalling(s: Score, now: datetime) -> bool:
    return now - s.at <= timedelta(minutes=SIGNAL_MINUTES)


def signal_key(s: Score) -> str:
    """The key of the compacted topic: the agent, or the agent's task."""
    return str(s.agent_id) if s.task_id is None else f"{s.agent_id}:{s.task_id}"


def scorer(settings: AnomalySettings, store: modelling.Store | None = None) -> Scorer:
    if store is None and settings.mlflow_tracking_uri:
        store = modelling.MlflowStore(settings.mlflow_tracking_uri)
    models = modelling.Models(store, settings.anomaly_models_save_every)
    try:
        if models.load():
            log.info("Risk models of %d agents loaded from MLflow", len(models.agents))
    except Exception:  # MLflow is away: the rules work without the models, which learn again
        log.exception("Risk models not loaded; the rules score alone until the models learn again")
    return Scorer(Rules(settings.anomaly_time_zone), models)


def run(settings: AnomalySettings) -> None:
    from confluent_kafka import Consumer, KafkaException, Producer

    codec = Codec(SchemaRegistry(settings.schema_registry_url))
    consumer = Consumer({"bootstrap.servers": settings.kafka_bootstrap_servers,
                         "group.id": f"anomaly-{uuid.uuid4()}", "enable.auto.commit": False,
                         "auto.offset.reset": "earliest"})
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers, "enable.idempotence": True,
                         "acks": "all"})
    detector = Detector()
    risk = scorer(settings)
    consumer.subscribe([COSTS, OPERATIONS])
    log.info("Anomaly detector reads %s and %s", COSTS, OPERATIONS)
    last_cleanup = last_tick = datetime.now(UTC)

    def publish(scores: list[Score], now: datetime) -> None:
        for s in scores:
            if worth_signalling(s, now):
                producer.produce(SIGNALS, key=signal_key(s).encode(), value=codec.encode(SIGNALS, signal(s)))

    try:
        while True:
            msg = consumer.poll(1.0)
            now = datetime.now(UTC)
            if msg is not None:
                if msg.error():
                    raise KafkaException(msg.error())
                record = codec.decode(msg.topic(), msg.value())
                if msg.topic() == COSTS:
                    for a in detector.add(record):
                        if worth_reporting(a, now):
                            producer.produce(FINDINGS, key=a.fingerprint.encode(),
                                             value=codec.encode(FINDINGS, finding(a, now)))
                else:
                    publish(risk.add(record), now)
            if now - last_tick > timedelta(seconds=30):
                publish(risk.tick(now), now)
                last_tick = now
            producer.poll(0)
            if now - last_cleanup > timedelta(hours=1):
                detector.forget_before(now.date())
                risk.forget_events()
                last_cleanup = now
    finally:
        producer.flush(30)
        consumer.close()
        try:
            risk.models.save()
        except Exception:
            log.exception("Risk models not saved on shutdown")


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    run(AnomalySettings())


if __name__ == "__main__":
    main()
