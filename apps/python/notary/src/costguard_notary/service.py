"""The Kafka loop: journal.receipts.v1 in, a NEAR transaction per batch, journal.anchors.v1 out.

Receipts collect until NOTARY_BATCH_SIZE of them or NOTARY_INTERVAL_SECONDS passed; then every batch is anchored
and published, and only then are the offsets committed. A crash in between replays the receipts and builds the
same batches again.
"""

from __future__ import annotations

import logging
import time
import uuid
from datetime import UTC, datetime

import httpx

from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec
from costguard_notary import batches, near

log = logging.getLogger("costguard.notary")

RECEIPTS = "journal.receipts.v1"
ANCHORS = "journal.anchors.v1"


class NotarySettings(Settings):
    near_rpc_url: str = "https://rpc.testnet.near.org"
    near_account_id: str = ""
    near_private_key: str = ""
    near_contract_id: str = ""
    notary_batch_size: int = 1000
    notary_interval_seconds: int = 600
    notary_group_id: str = "notary"


def receipt(record: dict) -> batches.Receipt:
    return batches.Receipt(uuid.UUID(str(record["receipt_id"])), uuid.UUID(str(record["tenant_id"])),
                           record["hash"], record["issued_at"])


def run(settings: NotarySettings) -> None:
    from confluent_kafka import Consumer, KafkaException, Producer

    if not (settings.near_account_id and settings.near_private_key and settings.near_contract_id):
        raise SystemExit("NEAR_ACCOUNT_ID, NEAR_PRIVATE_KEY and NEAR_CONTRACT_ID are required")
    signer = near.Signer.from_secret(settings.near_account_id, settings.near_private_key)
    rpc = near.Rpc(httpx.Client(base_url=settings.near_rpc_url, timeout=120))
    codec = Codec(SchemaRegistry(settings.schema_registry_url))
    consumer = Consumer({"bootstrap.servers": settings.kafka_bootstrap_servers, "group.id": settings.notary_group_id,
                         "enable.auto.commit": False, "auto.offset.reset": "earliest"})
    producer = Producer({"bootstrap.servers": settings.kafka_bootstrap_servers, "enable.idempotence": True,
                         "acks": "all"})

    def publish(value: dict) -> None:
        failed: list[str] = []
        producer.produce(ANCHORS, key=value["batch_id"].encode(), value=codec.encode(ANCHORS, value),
                         on_delivery=lambda err, _m: failed.append(str(err)) if err else None)
        producer.flush(30)
        if failed or len(producer):
            raise RuntimeError(f"Anchor not delivered: {failed[:1]}")

    consumer.subscribe([RECEIPTS])
    pending: list[batches.Receipt] = []
    since = time.monotonic()
    log.info("Notary anchors %s in %s as %s", RECEIPTS, settings.near_contract_id, settings.near_account_id)
    try:
        while True:
            msg = consumer.poll(1.0)
            if msg is not None:
                if msg.error():
                    raise KafkaException(msg.error())
                pending.append(receipt(codec.decode(RECEIPTS, msg.value())))
            due = time.monotonic() - since >= settings.notary_interval_seconds
            if pending and (len(pending) >= settings.notary_batch_size or due):
                n = batches.anchor(pending, lambda m, a: rpc.call(signer, settings.near_contract_id, m, a), publish,
                                   lambda: datetime.now(UTC), settings.notary_batch_size)
                consumer.commit(asynchronous=False)
                log.info("Anchored %d receipts in %d batches", len(pending), n)
                pending = []
                since = time.monotonic()
            elif due:
                since = time.monotonic()
    finally:
        consumer.close()


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    run(NotarySettings())


if __name__ == "__main__":
    main()
