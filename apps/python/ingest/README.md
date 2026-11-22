# costguard-ingest

The ingest pipeline: reads the files people and connectors upload, suggests the column mapping and writes cost
records in FOCUS. Two commands live in this package.

## costguard-ingest

A long-running Kafka consumer (`service.py`).

| In | Out |
|---|---|
| `ingest.files.v1` — a file was uploaded to the core | `ingest.results.v1` — what was read, the suggested mapping, broken and unsure rows |
| `ingest.accepted.v1` — a person (or `costguard-focus-pull --accept`) confirmed the mapping | `cost.records.v1` — FOCUS cost records; `ingest.normalized.v1` — vendors, people, devices for the core |

- Tables: Excel, CSV, Parquet (`tables.py`); FOCUS 1.0 exports map themselves by column names (`mapping.py`,
  [ADR 0042](../../../docs/adr/0042-focus-scim-products-activity.md)).
- A table with inventory or serial numbers is a list of equipment: rows become devices with a holder
  ([ADR 0041](../../../docs/adr/0041-equipment-from-excel-and-1c.md)).
- PDFs, scans and photos: text by `pdfplumber`, OCR by Tesseract ([ADR 0012](../../../docs/adr/0012-ocr-tesseract.md)),
  invoice fields by an LLM through the gateway ([ADR 0013](../../../docs/adr/0013-llm-invoice-extraction.md)).
- Bank statements: 1C client-bank, MT940, CAMT.053, OFX (`statements.py`); payments through intermediaries
  ([ADR 0015](../../../docs/adr/0015-bank-connector-intermediaries.md)).
- Amounts and dates are read as the company's country writes them, unless the column shows its own way (`values.py`,
  [ADR 0072](../../../docs/adr/0072-company-currency-and-country.md)): `1,500` is fifteen hundred in the United States,
  `03/04/2026` is March 4 there. A value only the country could settle, of a company that did not say its country,
  goes to people.

The offset is committed only after every output is acknowledged, so a crash replays the input.

## costguard-focus-pull

A job, not a service (`bucket.py`, [ADR 0046](../../../docs/adr/0046-focus-from-bucket.md)): takes new FOCUS exports
of AWS, Azure, Google Cloud and Yandex Cloud from a bucket of the company and uploads them to the core through
`POST /api/v1/imports`. Airflow runs it every hour (`infra/airflow/dags/focus_bucket_hourly.py`).

```sh
CORE_URL=… CORE_TOKEN=… FOCUS_BUCKET_ACCESS_KEY=… FOCUS_BUCKET_SECRET_KEY=… \
    costguard-focus-pull --bucket exports --prefix focus/ --state focus-bucket.json --accept
```

## Run

```sh
uv run costguard-ingest                                        # from the repository root
docker build -f apps/python/ingest/Dockerfile -t costguard-ingest .
```

The image has Tesseract with Russian and English, and `schemas/clickhouse`: the ClickHouse migrations job of the
Helm chart runs `python /app/schemas/clickhouse/apply.py` from it.

| Variable | Default | |
|---|---|---|
| `KAFKA_BOOTSTRAP_SERVERS`, `SCHEMA_REGISTRY_URL` | `localhost:9092`, `http://localhost:8085` | |
| `S3_ENDPOINT`, `S3_REGION`, `S3_ACCESS_KEY`, `S3_SECRET_KEY`, `INGEST_S3_BUCKET` | local SeaweedFS, `costguard-imports` | where the core keeps uploaded files |
| `INGEST_STORAGE_DIR` | empty | read files from this folder instead of S3 (local runs) |
| `INGEST_GROUP_ID` | `ingest-pipeline` | |
| `INGEST_OCR_ENABLED`, `INGEST_TESSERACT`, `INGEST_OCR_LANGUAGES` | on, `tesseract`, `rus+eng` | |
| `INGEST_LLM_API_KEY`, `INGEST_LLM_MODEL` | empty | the LLM extraction is on when both are set; the key is a virtual key of the gateway |
| `INGEST_LLM_GATEWAY_URL`, `INGEST_LLM_PROVIDER`, `INGEST_LLM_FOLDER_ID`, `INGEST_LLM_CONFIDENCE`, `INGEST_LLM_TIMEOUT_SECONDS` | `http://localhost:8081`, `openai`, … | |
| `INGEST_PAYMENT_INTERMEDIARIES`, `INGEST_PAYMENT_SERVICES` | empty | payment agents and extra services to look for in purposes |


Traces (ADR 0050): with `COSTGUARD_TRACING=true` and `OTEL_EXPORTER_OTLP_TRACES_ENDPOINT` the service sends spans to the
OpenTelemetry Collector; the chart sets both with `tracing.enabled`.

## Tests

```sh
uv run pytest apps/python/ingest       # test_ocr.py needs tesseract on PATH
```
