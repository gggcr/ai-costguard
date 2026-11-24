# The nightly backups (ADR 0025): pg_dump and pg_restore of the same major version as Managed PostgreSQL, age, and
# boto3 for the bucket. Build from the repository root: docker build -f deploy/backup/Dockerfile -t backup .
FROM postgres:17-bookworm
# upgrade: the security fixes Debian released after the base image was built (OpenSSL, GnuTLS, libxml2, Perl);
# the security workflow fails the build on a critical one with a fix (ADR 0027).
RUN apt-get update \
    && apt-get upgrade -y --no-install-recommends \
    && apt-get install -y --no-install-recommends age python3 python3-boto3 ca-certificates \
    && rm -rf /var/lib/apt/lists/*
# gosu of the postgres image starts the server as postgres; no server runs here, and its old Go build carries
# critical CVEs (CVE-2023-24538 and others).
RUN rm -f /usr/local/bin/gosu
RUN useradd --system --no-create-home costguard
COPY tools/backup/backup.py /app/backup.py
USER costguard
ENTRYPOINT ["python3", "/app/backup.py"]
