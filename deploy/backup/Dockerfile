# The nightly backups (ADR 0025): pg_dump and pg_restore of the same major version as Managed PostgreSQL, age, and
# boto3 for the bucket. Build from the repository root: docker build -f deploy/backup/Dockerfile -t backup .
FROM postgres:17.2-bookworm
RUN apt-get update \
    && apt-get install -y --no-install-recommends age python3 python3-boto3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --system --no-create-home costguard
COPY tools/backup/backup.py /app/backup.py
USER costguard
ENTRYPOINT ["python3", "/app/backup.py"]
