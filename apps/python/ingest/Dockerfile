# Image of the costguard-ingest package, commands: costguard-ingest, costguard-focus-pull.
# Build from the repository root, where the uv workspace and its lock file are:
#   docker build -f apps/python/ingest/Dockerfile -t costguard-ingest .
# Only this service and its dependencies go into the image (uv sync --package), not the other Python services.
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /usr/local/bin/uv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY libs/python libs/python
COPY apps/python apps/python
RUN uv sync --frozen --no-dev --package costguard-ingest --no-editable

FROM python:3.12-slim
# OCR of scans (ADR 0012).
RUN apt-get update && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-rus tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home costguard
COPY --from=build /src/.venv /app/.venv
COPY schemas/avro /app/schemas/avro
# The ClickHouse migrations job of the Helm chart runs python /app/schemas/clickhouse/apply.py from this image.
COPY schemas/clickhouse /app/schemas/clickhouse
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 COSTGUARD_SCHEMA_DIR=/app/schemas/avro
USER costguard
CMD ["costguard-ingest"]
