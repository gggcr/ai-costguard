"""The Kafka loop of the notary, with two consumers of their own groups.

journal.receipts.v1 in, a NEAR transaction per batch, journal.anchors.v1 out. Receipts collect until
NOTARY_BATCH_SIZE of them or NOTARY_INTERVAL_SECONDS passed; then every batch is anchored and published, and only
then are the offsets committed. A crash in between replays the receipts and builds the same batches again.

notary.requests.v1 in, a NEAR transaction per request, notary.confirmations.v1 out (costguard_notary.notarize).
Each request is committed once its confirmation is delivered. The groups are separate, so committing a request
never commits receipts that are still waiting for their batch.
"""

from __future__ import annotations

import logging
import time
import uuid
from datetime import UTC, datetime

import httpx

from costguard_common import consuming, logs, tracing
from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec
from costguard_notary import batches, near, notarize

log = logging.getLogger("costguard.notary")

RECEIPTS = "journal.receipts.v1"
ANCHORS = "journal.anchors.v1"
REQUESTS = "notary.requests.v1"
CONFIRMATIONS = "notary.confirmations.v1"


class NotarySettings(Settings):
    near_rpc_url: str = "https://rpc.testnet.near.org"
    near_account_id: str = ""
    near_private_key: str = ""
    near_contract_id: str = ""
    notary_batch_size: int = 1000
    notary_interval_seconds: int = 600
    notary_group_id: str = "notary"
    notary_requests_group_id: str = "notary-requests"


def receipt(record: dict) -> batches.Receipt:
    return batches.Receipt(uuid.UUID(str(record["receipt_id"])), uuid.UUID(str(record["tenant_id"])),
                           record["hash"], record["issued_at"])


def run(settings: NotarySettings) -> None:
    from confluent_kafka import Consumer, Producer

    if not (settings.near_account_id and settings.near_private_key and settings.near_contract_id):
        raise SystemExit("NEAR_ACCOUNT_ID, NEAR_PRIVATE_KEY and NEAR_CONTRACT_ID are required")
    signer = near.Signer.from_secret(settings.near_account_id, settings.near_private_key)
    rpc = near.Rpc(httpx.Client(base_url=settings.near_rpc_url, timeout=120))
    codec = Codec(SchemaRegistry(settings.schema_registry_url))

    def consumer(group: str, topic: str) -> Consumer:
        c = Consumer({"bootstrap.servers": settings.kafka_bootstrap_servers, "group.id": group,
                      "enable.auto.commit": False, "auto.offset.reset": "earliest"})
        c.subscribe([topic])
        return c

    receipts = consumer(settings.notary_group_id, RECEIPTS)
    requests = consumer(settings.notary_requests_group_id, REQUESTS)
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers, "enable.idempotence": True,
                         "acks": "all"})

    def publisher(topic: str, key: str):
        def publish(value: dict) -> None:
            failed: list[str] = []
            producer.produce(topic, key=value[key].encode(), value=codec.encode(topic, value),
                             headers=tracing.headers(),
                             on_delivery=lambda err, _m: failed.append(str(err)) if err else None)
            producer.flush(30)
            if failed or len(producer):
                raise RuntimeError(f"{topic} not delivered: {failed[:1]}")
        return publish

    def call(method: str, args: dict) -> str:
        return rpc.call(signer, settings.near_contract_id, method, args)

    publish_anchor = publisher(ANCHORS, "batch_id")
    publish_confirmation = publisher(CONFIRMATIONS, "request_id")

    def polled(c: Consumer, timeout: float):
        msg = c.poll(timeout)
        if msg is not None and msg.error() and consuming.skip(msg.error(), log):
            return None
        return msg

    pending: list[batches.Receipt] = []
    since = time.monotonic()
    log.info("Notary writes %s and %s to %s as %s", RECEIPTS, REQUESTS, settings.near_contract_id,
             settings.near_account_id)
    try:
        while True:
            msg = polled(receipts, 0.5)
            if msg is not None:
                pending.append(receipt(codec.decode(RECEIPTS, msg.value())))
            due = time.monotonic() - since >= settings.notary_interval_seconds
            if pending and (len(pending) >= settings.notary_batch_size or due):
                with tracing.span("notary anchor", **{"costguard.receipts": len(pending)}):
                    n = batches.anchor(pending, call, publish_anchor, lambda: datetime.now(UTC),
                                       settings.notary_batch_size)
                receipts.commit(asynchronous=False)
                log.info("Anchored %d receipts in %d batches", len(pending), n)
                pending = []
                since = time.monotonic()
            elif due:
                since = time.monotonic()

            msg = polled(requests, 0.5)
            if msg is not None:
                with tracing.consumed(msg):
                    notarize.handle(codec.decode(REQUESTS, msg.value()), call, publish_confirmation,
                                    lambda: datetime.now(UTC))
                    requests.commit(message=msg, asynchronous=False)
    finally:
        receipts.close()
        requests.close()


def main() -> None:
    logs.configure()
    tracing.setup("costguard-notary")
    try:
        run(NotarySettings())
    finally:
        tracing.shutdown()


if __name__ == "__main__":
    main()
