"""Kafka contracts of the Python services, against schemas/avro.

Producers: every message a service builds fits the schema of the topic it goes to. Consumers: every message the
schema allows is taken without failing, and what the consumer answers fits its own topic. The Java side checks
the same in TopicContractTest of the core and the gateway.
"""

import uuid
from datetime import UTC, datetime
from decimal import Decimal

import pytest
from samples import fits, samples

from costguard_anomaly import detector, scorer
from costguard_anomaly import service as anomaly
from costguard_common import schemas
from costguard_connectors import contract, entities
from costguard_ingest import pipeline
from costguard_ingest import service as ingest
from costguard_notary import batches, notarize
from costguard_notary import service as notary

NOW = datetime(2026, 11, 8, 12, tzinfo=UTC)
TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")


# every topic a service names has its schema

@pytest.mark.parametrize("topic", sorted({
    ingest.FILES, ingest.ACCEPTED, ingest.RESULTS, notary.RECEIPTS, notary.ANCHORS, notary.REQUESTS,
    notary.CONFIRMATIONS, anomaly.COSTS, anomaly.FINDINGS, anomaly.OPERATIONS, anomaly.SIGNALS, contract.COSTS,
    "ingest.normalized.v1"}))
def test_every_topic_a_service_names_has_a_schema(topic):
    assert topic in schemas.topics()


# consumers take whatever the schema allows


class AnyFile:
    """Storage where every key holds the same small CSV."""

    def get(self, key):
        return "Поставщик;Сумма;Дата\nООО Ландыш;100,50;01.11.2026\n".encode("cp1251")

    def put(self, key, content):
        pass


def test_ingest_takes_any_uploaded_file_message():
    handler = ingest.Handler(AnyFile())
    for message in samples("ingest.files.v1"):
        for out in handler.on_file(message):
            fits(out.topic, out.value)


def test_ingest_takes_any_accepted_file_message():
    handler = ingest.Handler(AnyFile())
    for message in samples("ingest.accepted.v1"):
        for out in handler.on_accepted(message):
            fits(out.topic, out.value)


def test_the_notary_takes_any_receipt():
    for message in samples("journal.receipts.v1"):
        receipt = notary.receipt(message)
        assert receipt.hash == message["hash"]


def test_the_notary_takes_any_request_and_answers_in_its_schema():
    published = []
    for message in samples("notary.requests.v1"):
        notarize.handle(message, lambda method, args: "TX", published.append, lambda: NOW)
    for confirmation in published:
        fits("notary.confirmations.v1", confirmation)


def test_the_detector_takes_any_cost_record():
    d = detector.Detector()
    for message in samples("cost.records.v1", n=80):
        for a in d.add(message):
            fits("findings.v1", anomaly.finding(a, NOW))


def test_the_risk_scorer_takes_any_operation():
    s = scorer.Scorer()
    for message in samples("agent.operations.v1", n=80):
        for signal in s.add(message):
            fits("agent.risk-signals.v1", scorer.signal(signal))
    for signal in s.tick(NOW):
        fits("agent.risk-signals.v1", scorer.signal(signal))


# producers build what the schemas say


def test_ingest_results_and_records_fit():
    content = "Поставщик;Сумма;Дата\nООО Ландыш;100,50;01.11.2026\n".encode("cp1251")
    parsed = pipeline.parse("a.csv", content)
    file = {"file_id": uuid.uuid4(), "tenant_id": TENANT}
    fits(ingest.RESULTS, ingest.result_of(file, parsed, None))
    fits(ingest.RESULTS, ingest.result_of(file, None, "Unknown file type"))
    messages, _ = pipeline.accept(TENANT, uuid.uuid4(), "k", "a.csv", None, parsed.suggested_mapping, {}, content)
    assert {m.topic for m in messages} == {"cost.records.v1", "ingest.normalized.v1"}
    for m in messages:
        fits(m.topic, m.value)


def test_notary_anchors_and_confirmations_fit():
    [b] = batches.batches([batches.Receipt(uuid.uuid4(), TENANT, "a" * 64, NOW)])
    fits(notary.ANCHORS, b.anchor_message("TX", NOW))
    request = samples("notary.requests.v1", n=1)[0]
    fits(notary.CONFIRMATIONS, notarize.confirmation(request, "TX", NOW))


def test_connector_messages_fit():
    batch = contract.Batch()
    vendor = entities.vendor(TENANT, "ООО Ландыш", "7700000002", source="test")
    person = entities.employee(TENANT, "Иванова Анна", "ivanova@romashka.example", source="test")
    batch.entities += [vendor, person,
                       entities.license_assignment(TENANT, "Тучка", "CRM", person, NOW.date(), "test"),
                       entities.invoice(TENANT, "77", vendor, Decimal("12000"), "RUB", NOW.date(), "test")]
    batch.costs.append(entities.cost(TENANT, "test", "77", Decimal("12000"), "RUB", NOW.date(), "ООО Ландыш"))
    for m in contract.messages(TENANT, "test", batch, now=NOW):
        fits(m.topic, m.value)
