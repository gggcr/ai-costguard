"""FOCUS cost exports from a bucket of the company, on a schedule (ADR 0046).

AWS (Data Exports), Azure (Cost Management exports), Google Cloud and Yandex Cloud write FOCUS files into an object
store of the company every day. costguard-focus-pull reads the new ones and uploads them to the core like a person
would, through POST /api/v1/imports: the core keeps the file, sends it to the ingest pipeline and shows it in
«Импорт». So a file goes the same way whoever brought it, and the core's sha256 check stops a file read twice.

    CORE_URL=https://core… CORE_TOKEN=… FOCUS_BUCKET_ACCESS_KEY=… FOCUS_BUCKET_SECRET_KEY=… \\
        costguard-focus-pull --endpoint https://storage.yandexcloud.net --bucket exports --prefix focus/ \\
        --state /var/lib/costguard/acme/focus-bucket.json

A FOCUS file needs nobody to map it: its columns are named by the specification and map exactly (mapping.py). With
--accept such a file is confirmed by the job itself, once the pipeline has read it, when nothing in it needs a person:
no broken rows, no unsure values, an amount column. Anything else waits in «Импорт» for a person, as an upload does.

Which objects were read (key and ETag) is kept in the state file, so an hourly run downloads only what is new or
changed. .csv, .csv.gz, .xlsx and .parquet are read; a compressed CSV is unpacked before upload, Parquet goes as it
is (it is compressed inside).
"""

from __future__ import annotations

import argparse
import gzip
import json
import logging
import os
import time
from collections.abc import Iterator
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta
from pathlib import Path
from typing import Protocol

import httpx

from costguard_ingest import mapping

log = logging.getLogger(__name__)
READ = (".csv", ".csv.gz", ".xlsx", ".parquet")
MAX_BYTES = 50 * 1024 * 1024  # what the core takes in one upload (nginx client_max_body_size)


@dataclass(frozen=True)
class Object:
    key: str
    etag: str
    size: int
    modified: datetime


class Bucket(Protocol):
    def objects(self, prefix: str) -> Iterator[Object]: ...

    def read(self, key: str) -> bytes: ...


class S3Bucket:
    """Any S3-compatible store: AWS S3, Yandex Object Storage, MinIO, Azure through a gateway."""

    def __init__(self, endpoint: str | None, bucket: str, access_key: str, secret_key: str,
                 region: str = "ru-central1") -> None:
        import boto3

        self._s3 = boto3.client("s3", endpoint_url=endpoint, region_name=region, aws_access_key_id=access_key,
                                aws_secret_access_key=secret_key)
        self._bucket = bucket

    def objects(self, prefix: str) -> Iterator[Object]:
        for page in self._s3.get_paginator("list_objects_v2").paginate(Bucket=self._bucket, Prefix=prefix):
            for o in page.get("Contents", []):
                yield Object(o["Key"], o["ETag"].strip('"'), int(o["Size"]), o["LastModified"])

    def read(self, key: str) -> bytes:
        return self._s3.get_object(Bucket=self._bucket, Key=key)["Body"].read()


@dataclass
class State:
    """Key to the ETag it had when it was read."""
    seen: dict[str, str] = field(default_factory=dict)

    @staticmethod
    def load(path: Path) -> State:
        try:
            return State(dict(json.loads(path.read_text("utf-8")).get("seen", {})))
        except (OSError, ValueError):
            return State()

    def save(self, path: Path) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(".tmp")
        tmp.write_text(json.dumps({"seen": self.seen}, ensure_ascii=False, indent=1), "utf-8")
        tmp.replace(path)  # a run killed halfway leaves the old state, not half of a new one


@dataclass
class Summary:
    uploaded: list[str] = field(default_factory=list)
    known: list[str] = field(default_factory=list)      # the core had the file already
    skipped: list[tuple[str, str]] = field(default_factory=list)
    failed: list[tuple[str, str]] = field(default_factory=list)
    imports: dict[str, str] = field(default_factory=dict)  # key to import id
    accepted: list[str] = field(default_factory=list)
    waiting: list[tuple[str, str]] = field(default_factory=list)  # left to a person, and why


def file_name(key: str) -> str:
    """The name the import shows: the last part of the key, a .gz taken off."""
    name = key.rsplit("/", 1)[-1]
    return name[:-3] if name.lower().endswith(".csv.gz") else name


def content_of(key: str, raw: bytes) -> bytes:
    return gzip.decompress(raw) if key.lower().endswith(".gz") else raw


def new_objects(bucket: Bucket, prefix: str, state: State, since: datetime, summary: Summary) -> list[Object]:
    out = []
    for o in bucket.objects(prefix):
        lower = o.key.lower()
        if o.key.endswith("/") or state.seen.get(o.key) == o.etag:
            continue
        if o.modified < since:
            continue  # older than the window: the first run does not load years of exports
        if not lower.endswith(READ):
            continue  # manifests, checksums, other files of the export
        elif o.size > MAX_BYTES * 20:  # even compressed, too much for one import
            summary.skipped.append((o.key, f"{o.size} bytes is too big for one import"))
            state.seen[o.key] = o.etag
        else:
            out.append(o)
    return sorted(out, key=lambda o: (o.modified, o.key))


def upload(core: httpx.Client, name: str, content: bytes) -> tuple[str, bool]:
    """Import id, and whether the core had this file already (200 instead of 201)."""
    lower = name.lower()
    kind = ("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" if lower.endswith(".xlsx")
            else "application/vnd.apache.parquet" if lower.endswith(".parquet") else "text/csv")
    r = core.post("/api/v1/imports", files={"file": (name, content, kind)})
    r.raise_for_status()
    return r.json()["id"], r.status_code == 200


def parsed(core: httpx.Client, import_id: str, timeout: float = 300, pause: float = 2.0,
           sleep=time.sleep) -> dict:
    """The import once the pipeline has read it (status past uploaded), or as it is when the time is up."""
    deadline = time.monotonic() + timeout
    while True:
        r = core.get(f"/api/v1/imports/{import_id}")
        r.raise_for_status()
        job = r.json()
        if job.get("status") != "uploaded" or time.monotonic() >= deadline:
            return job
        sleep(pause)


def why_not(job: dict) -> str | None:
    """Why the job may not confirm this import itself; None when it may."""
    if job.get("status") != "mapping":
        return f"status {job.get('status')}" + (f": {job['failure']}" if job.get("failure") else "")
    if not mapping.is_focus(job.get("columns") or []):
        return "not a FOCUS file: the columns need a person"
    if job.get("errors"):
        return f"{len(job['errors'])} rows with errors"
    if any(not t.get("resolved") for t in job.get("reviewTasks") or []):
        return "values the pipeline was unsure about"
    if "amount" not in (job.get("mapping") or {}).values():
        return "no amount column"
    return None


def accept(core: httpx.Client, job: dict) -> dict:
    r = core.put(f"/api/v1/imports/{job['id']}/mapping", json={"mapping": job["mapping"], "saveTemplate": False})
    r.raise_for_status()
    return r.json()


def confirm(core: httpx.Client, summary: Summary, wait: float = 300, sleep=time.sleep) -> None:
    """Confirms the FOCUS files that went up in this run and need nobody."""
    for key in summary.uploaded:
        try:
            job = parsed(core, summary.imports[key], wait, sleep=sleep)
            reason = why_not(job)
            if reason is None:
                accept(core, job)
                summary.accepted.append(key)
            else:
                summary.waiting.append((key, reason))
        except httpx.HTTPError as e:
            summary.waiting.append((key, f"not confirmed: {e}"))


def pull(bucket: Bucket, core: httpx.Client, prefix: str, state: State, since: datetime) -> Summary:
    summary = Summary()
    for o in new_objects(bucket, prefix, state, since, summary):
        name = file_name(o.key)
        try:
            content = content_of(o.key, bucket.read(o.key))
        except (OSError, EOFError, gzip.BadGzipFile) as e:
            summary.failed.append((o.key, f"not read: {e}"))
            state.seen[o.key] = o.etag  # a broken file stays broken; a new ETag is read again
            continue
        if len(content) > MAX_BYTES:
            summary.skipped.append((o.key, f"{len(content)} bytes unpacked, more than one import takes"))
            state.seen[o.key] = o.etag
            continue
        try:
            import_id, known = upload(core, name, content)
        except httpx.HTTPError as e:
            # not marked as seen: the next run tries again
            summary.failed.append((o.key, f"upload: {e}"))
            continue
        (summary.known if known else summary.uploaded).append(o.key)
        summary.imports[o.key] = import_id
        state.seen[o.key] = o.etag
    return summary


def main(argv: list[str] | None = None) -> None:
    logging.basicConfig(level=logging.INFO)
    parser = argparse.ArgumentParser(prog="costguard-focus-pull")
    parser.add_argument("--endpoint", help="S3 endpoint; AWS when left out")
    parser.add_argument("--region", default="ru-central1")
    parser.add_argument("--bucket", required=True)
    parser.add_argument("--prefix", default="")
    parser.add_argument("--state", type=Path, required=True, help="file with the objects already read")
    parser.add_argument("--days", type=int, default=7, help="objects older than this are not read")
    parser.add_argument("--accept", action="store_true", help="confirm FOCUS files that need nobody")
    args = parser.parse_args(argv)
    bucket = S3Bucket(args.endpoint, args.bucket, os.environ["FOCUS_BUCKET_ACCESS_KEY"],
                      os.environ["FOCUS_BUCKET_SECRET_KEY"], args.region)
    core = httpx.Client(base_url=os.environ["CORE_URL"], timeout=120,
                        headers={"Authorization": f"Bearer {os.environ['CORE_TOKEN']}"})
    state = State.load(args.state)
    started = time.monotonic()
    summary = pull(bucket, core, args.prefix, state, datetime.now(UTC) - timedelta(days=args.days))
    state.save(args.state)
    if args.accept:
        confirm(core, summary)
    print(json.dumps({"uploaded": summary.uploaded, "accepted": summary.accepted, "waiting": summary.waiting,
                      "known": summary.known, "skipped": summary.skipped,
                      "failed": summary.failed, "seconds": round(time.monotonic() - started, 1)},
                     ensure_ascii=False))
    if summary.failed:
        raise SystemExit(1)  # Airflow shows the run red; what went up stays up


if __name__ == "__main__":
    main()
