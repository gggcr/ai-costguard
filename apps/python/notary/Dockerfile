# Image of the costguard-notary package, commands: costguard-notary.
# Build from the repository root, where the uv workspace and its lock file are:
#   docker build -f apps/python/notary/Dockerfile -t costguard-notary .
# Only this service and its dependencies go into the image (uv sync --package), not the other Python services.
FROM python:3.12-slim AS build
COPY --from=ghcr.io/astral-sh/uv:0.5.11 /uv /usr/local/bin/uv
WORKDIR /src
COPY pyproject.toml uv.lock ./
COPY libs/python libs/python
COPY apps/python apps/python
RUN uv sync --frozen --no-dev --package costguard-notary --no-editable

FROM python:3.12-slim
RUN useradd --system --no-create-home costguard
COPY --from=build /src/.venv /app/.venv
COPY schemas/avro /app/schemas/avro
ENV PATH=/app/.venv/bin:$PATH PYTHONUNBUFFERED=1 COSTGUARD_SCHEMA_DIR=/app/schemas/avro
USER costguard
CMD ["costguard-notary"]
