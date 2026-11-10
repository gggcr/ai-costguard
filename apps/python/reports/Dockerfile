# Image of the costguard-reports package, commands: costguard-forecast, costguard-report.
# Build from the repository root, where the uv workspace and its lock file are:
#   docker build -f apps/python/reports/Dockerfile -t costguard-reports .
# Only this service and its dependencies go into the image (uv sync --package), not the other Python services.
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /usr/local/bin/uv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY libs/python libs/python
COPY apps/python apps/python
RUN uv sync --frozen --no-dev --package costguard-reports --no-editable

FROM python:3.12-slim
# libgomp1 for lightgbm, a dependency of the package (the boosting of ADR 0045 itself is plain Python).
RUN apt-get update && apt-get install -y --no-install-recommends libgomp1 && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home costguard
COPY --from=build /src/.venv /app/.venv
COPY schemas/avro /app/schemas/avro
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 COSTGUARD_SCHEMA_DIR=/app/schemas/avro
USER costguard
CMD ["costguard-forecast"]
