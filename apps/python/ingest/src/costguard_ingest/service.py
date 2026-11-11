"""The Kafka loop: ingest.files.v1 and ingest.accepted.v1 in; ingest.results.v1, cost.records.v1 and
ingest.normalized.v1 out.

The offset is committed only after every output message is acknowledged, so a crash replays the input; the
outputs carry stable keys and ids, and the core ignores a result for a file that already moved on.
"""

from __future__ import annotations

import functools
import logging
import uuid
from datetime import UTC, datetime

import httpx

from costguard_common import consuming, llm, logs, tracing
from costguard_common import payments as paying
from costguard_common.registry import SchemaRegistry
from costguard_common.settings import Settings
from costguard_common.wire import Codec
from costguard_ingest import documents, examples, extraction, ocr, pipeline, storage, tables, values

log = logging.getLogger("costguard.ingest")

FILES = "ingest.files.v1"
ACCEPTED = "ingest.accepted.v1"
RESULTS = "ingest.results.v1"


class IngestSettings(Settings):
    ingest_group_id: str = "ingest-pipeline"
    ingest_storage_dir: str = ""
    ingest_s3_bucket: str = "costguard-imports"
    # OCR of scans and photos (ADR 0012); the program must be on PATH or named here.
    ingest_ocr_enabled: bool = True
    ingest_tesseract: str = "tesseract"
    ingest_ocr_languages: str = ocr.LANGUAGES
    # Invoice extraction from text (ADR 0013): on when the key and the model are set. The key is a virtual key of
    # CostGuard's own agent; the call goes through the gateway, which meters and limits it.
    ingest_llm_gateway_url: str = "http://localhost:8081"
    ingest_llm_api_key: str = ""
    ingest_llm_provider: str = "openai"
    ingest_llm_model: str = ""
    ingest_llm_folder_id: str = ""
    ingest_llm_confidence: float = values.CONFIDENT
    ingest_llm_timeout_seconds: float = 120
    # Payments through intermediaries (ADR 0015): INNs or names of agents, comma separated, and services to look for
    # in purposes besides the built-in ones, as "alias=Vendor;alias=Vendor".
    ingest_payment_intermediaries: str = ""
    ingest_payment_services: str = ""

    def intermediaries(self) -> list[paying.Intermediary]:
        return paying.intermediaries_of(self.ingest_payment_intermediaries)

    def services(self) -> dict[str, str]:
        pairs = (item.split("=", 1) for item in self.ingest_payment_services.split(";") if "=" in item)
        return {alias.strip(): vendor.strip() for alias, vendor in pairs if alias.strip() and vendor.strip()}

    @property
    def extraction_on(self) -> bool:
        return bool(self.ingest_llm_api_key and self.ingest_llm_model)


GOAL = "Извлечь реквизиты, позиции и суммы счёта из загруженного документа для учёта расходов"


def model(settings: IngestSettings, client: httpx.Client | None = None) -> llm.GatewayLlm:
    return llm.GatewayLlm(client or httpx.Client(base_url=settings.ingest_llm_gateway_url,
                                                 timeout=settings.ingest_llm_timeout_seconds),
                          settings.ingest_llm_api_key, settings.ingest_llm_provider, settings.ingest_llm_model,
                          goal=GOAL, folder_id=settings.ingest_llm_folder_id)


def reader(settings: IngestSettings, files: storage.Files | None = None,
           model_: llm.Llm | None = None) -> documents.Reader:
    engine = None
    if settings.ingest_ocr_enabled:
        engine = ocr.Tesseract(settings.ingest_tesseract, settings.ingest_ocr_languages)
    from_text = None
    if settings.extraction_on or model_ is not None:
        known = examples.Examples(files) if files is not None else None
        from_text = extraction.TextReader(extraction.Extractor(model_ or model(settings), known), files,
                                          settings.ingest_llm_confidence)
    return documents.Reader(engine, from_text)


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

    def __init__(self, files: storage.Files, read: documents.Reader | None = None,
                 intermediaries: list[paying.Intermediary] | None = None,
                 services: dict[str, str] | None = None) -> None:
        self._files = files
        self._reader = read
        self._intermediaries = intermediaries or []
        self._services = services or {}

    def _read(self, tenant: object, key: str) -> pipeline.Read:
        if self._reader is None:
            return tables.read
        return functools.partial(self._reader, tenant=uuid.UUID(str(tenant)), key=key)

    def on_file(self, file: dict) -> list[pipeline.Message]:
        key = str(file["file_id"])
        try:
            parsed = pipeline.parse(file["file_name"], self._files.get(file["s3_key"]),
                                    self._read(file["tenant_id"], file["s3_key"]))
        except tables.Unsupported as e:
            return [pipeline.Message(RESULTS, key, result_of(file, None, str(e)))]
        except FileNotFoundError:
            return [pipeline.Message(RESULTS, key, result_of(file, None, "The file is not in storage"))]
        return [pipeline.Message(RESULTS, key, result_of(file, parsed, None))]

    def on_accepted(self, accepted: dict) -> list[pipeline.Message]:
        """Rows of an accepted file. A file that cannot be read writes nothing and is logged: reading the message
        again would fail the same way and stop every file behind it."""
        vendor = accepted.get("vendor_id")
        try:
            messages, skipped = pipeline.accept(
                uuid.UUID(str(accepted["tenant_id"])), uuid.UUID(str(accepted["file_id"])), accepted["s3_key"],
                accepted["file_name"], None if vendor is None else uuid.UUID(str(vendor)), accepted["mapping"],
                accepted["corrections"], self._files.get(accepted["s3_key"]),
                read=self._read(accepted["tenant_id"], accepted["s3_key"]), intermediaries=self._intermediaries,
                services=self._services)
        except (tables.Unsupported, FileNotFoundError) as e:
            log.error("Accepted file %s not written: %s", accepted["file_id"], e or "not in storage")
            return []
        for row, reason in skipped:
            log.warning("File %s row %s not written: %s", accepted["file_id"], row, reason)
        if self._reader is not None and self._reader.from_text is not None and extraction.learn(
                self._files, examples.Examples(self._files), uuid.UUID(str(accepted["tenant_id"])),
                accepted["s3_key"], accepted["mapping"], accepted["corrections"]):
            log.info("File %s: corrections kept as an example for its vendor", accepted["file_id"])
        return messages


def run(settings: IngestSettings) -> None:
    from confluent_kafka import Consumer, Producer

    files: storage.Files = (storage.LocalFiles(settings.ingest_storage_dir) if settings.ingest_storage_dir
                            else storage.S3Files(settings.s3_endpoint, settings.s3_access_key,
                                                 settings.s3_secret_key, settings.ingest_s3_bucket,
                                                 settings.s3_region))
    handler = Handler(files, reader(settings, files), settings.intermediaries(), settings.services())
    if not settings.extraction_on:
        log.info("Invoice extraction from text is off: INGEST_LLM_API_KEY and INGEST_LLM_MODEL are not set")
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
                if consuming.skip(msg.error(), log):
                    continue
            with tracing.consumed(msg) as span:
                record = codec.decode(msg.topic(), msg.value())
                out = handler.on_file(record) if msg.topic() == FILES else handler.on_accepted(record)
                span.set_attribute("costguard.outputs", len(out))
                failed: list[str] = []

                def delivered(err, _m, failed=failed):
                    if err:
                        failed.append(str(err))

                for m in out:
                    producer.produce(m.topic, key=m.key.encode(), value=codec.encode(m.topic, m.value),
                                     headers=tracing.headers(), on_delivery=delivered)
                producer.flush(30)
                if failed or len(producer):
                    raise RuntimeError(f"Output not delivered, the input will be read again: {failed[:3]}")
                consumer.commit(message=msg, asynchronous=False)
    finally:
        consumer.close()


def main() -> None:
    logs.configure()
    tracing.setup("costguard-ingest")
    try:
        run(IngestSettings())
    finally:
        tracing.shutdown()


if __name__ == "__main__":
    main()
