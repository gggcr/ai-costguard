# One image for every Python service; the command picks the service:
#   costguard-ingest | costguard-anomaly | costguard-notary | costguard-connector-agent | costguard-report
# Build from the repository root: docker build -f apps/python/Dockerfile -t costguard-python .
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /usr/local/bin/uv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY libs/python libs/python
COPY apps/python apps/python
COPY schemas/avro schemas/avro
COPY schemas/clickhouse schemas/clickhouse
RUN uv sync --frozen --no-dev --all-packages --no-editable

FROM python:3.12-slim
RUN useradd --system --no-create-home costguard
COPY --from=build /src/.venv /app/.venv
COPY --from=build /src/schemas /app/schemas
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 COSTGUARD_SCHEMA_DIR=/app/schemas/avro
USER costguard
CMD ["costguard-ingest"]
