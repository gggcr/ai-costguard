"""Backups of PostgreSQL, ClickHouse and the uploaded files, encrypted with age (ADR 0025).

    python tools/backup/backup.py postgres            # nightly, from the backup CronJob
    python tools/backup/backup.py clickhouse
    python tools/backup/backup.py files
    python tools/backup/backup.py restore-postgres --day 2026-11-09 --identity key.txt --target postgresql://...
    python tools/backup/backup.py restore-clickhouse --day 2026-11-09 --identity key.txt --database restored
    python tools/backup/backup.py restore-files --identity key.txt [--prefix <tenant>/]
    python tools/backup/backup.py status              # the newest copy of each kind and its age

Where copies go: BACKUP_TARGET, either s3://<bucket>[/prefix] (S3_ENDPOINT, S3_ACCESS_KEY, S3_SECRET_KEY, S3_REGION)
or file:///<dir> for tests. Every object is encrypted to AGE_RECIPIENT, the public key; only the holder of the
private key (kept outside the cluster) can read a copy. The bucket encrypts once more with KMS and expires copies
after 30 days (deploy/terraform).

Layout:
    postgres/<day>/costguard.dump.age           pg_dump -Fc of the whole database, as costguard_backup
    clickhouse/<day>/<table>.native.age         SELECT * FORMAT Native of each data table
    files/objects/<key>.age                     each uploaded file, mirrored; files/manifest.json says which ETag
"""

from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile
import urllib.parse
import urllib.request
from collections.abc import Callable
from datetime import UTC, date, datetime
from pathlib import Path
from typing import Protocol
from urllib.parse import urlsplit

CLICKHOUSE_TABLES = ("cost_records", "agent_operations")


# --- where copies live ----------------------------------------------------------------------------------------------

class Store(Protocol):
    def list(self, prefix: str) -> dict[str, str]: ...  # key -> etag

    def get(self, key: str, path: Path) -> None: ...

    def put(self, key: str, path: Path) -> None: ...

    def delete(self, key: str) -> None: ...


class DirStore:
    """A directory standing in for a bucket, for tests and for a restore drill on a laptop."""

    def __init__(self, root: str | Path) -> None:
        self.root = Path(root)

    def _path(self, key: str) -> Path:
        path = (self.root / key).resolve()
        if not path.is_relative_to(self.root.resolve()):
            raise ValueError(f"key {key!r} leaves the store")
        return path

    def list(self, prefix: str) -> dict[str, str]:
        out = {}
        base = self.root.resolve()
        if not base.exists():
            return out
        for p in base.rglob("*"):
            if p.is_file():
                key = p.relative_to(base).as_posix()
                if key.startswith(prefix):
                    stat = p.stat()
                    out[key] = f"{stat.st_size}-{stat.st_mtime_ns}"
        return out

    def get(self, key: str, path: Path) -> None:
        shutil.copyfile(self._path(key), path)

    def put(self, key: str, path: Path) -> None:
        target = self._path(key)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(path, target)

    def delete(self, key: str) -> None:
        self._path(key).unlink(missing_ok=True)


class S3Store:
    def __init__(self, bucket: str, prefix: str = "", client=None) -> None:
        if client is None:
            import boto3

            client = boto3.client("s3", endpoint_url=os.environ.get("S3_ENDPOINT"),
                                  region_name=os.environ.get("S3_REGION", "ru-central1"),
                                  aws_access_key_id=os.environ.get("S3_ACCESS_KEY"),
                                  aws_secret_access_key=os.environ.get("S3_SECRET_KEY"))
        self._s3 = client
        self._bucket = bucket
        self._prefix = prefix.strip("/") + "/" if prefix.strip("/") else ""

    def list(self, prefix: str) -> dict[str, str]:
        out = {}
        for page in self._s3.get_paginator("list_objects_v2").paginate(Bucket=self._bucket,
                                                                        Prefix=self._prefix + prefix):
            for o in page.get("Contents", []):
                out[o["Key"][len(self._prefix):]] = o["ETag"].strip('"')
        return out

    def get(self, key: str, path: Path) -> None:
        self._s3.download_file(self._bucket, self._prefix + key, str(path))

    def put(self, key: str, path: Path) -> None:
        self._s3.upload_file(str(path), self._bucket, self._prefix + key)

    def delete(self, key: str) -> None:
        self._s3.delete_object(Bucket=self._bucket, Key=self._prefix + key)


def store_of(url: str) -> Store:
    parts = urlsplit(url)
    if parts.scheme == "file":
        return DirStore(Path(urllib.request.url2pathname(parts.path)))
    if parts.scheme == "s3":
        return S3Store(parts.netloc, parts.path)
    raise ValueError(f"BACKUP_TARGET must be s3://bucket or file:///dir, not {url!r}")


# --- encryption -----------------------------------------------------------------------------------------------------

class Cipher(Protocol):
    def encrypt(self, plain: Path, sealed: Path) -> None: ...

    def decrypt(self, sealed: Path, plain: Path) -> None: ...


class Age:
    """The age binary: encrypts to a public key, decrypts with the private key file."""

    def __init__(self, recipient: str | None = None, identity: str | None = None) -> None:
        self.recipient = recipient
        self.identity = identity

    def encrypt(self, plain: Path, sealed: Path) -> None:
        if not self.recipient:
            raise SystemExit("AGE_RECIPIENT is not set: copies are never written unencrypted")
        subprocess.run(["age", "-r", self.recipient, "-o", str(sealed), str(plain)], check=True)

    def decrypt(self, sealed: Path, plain: Path) -> None:
        if not self.identity:
            raise SystemExit("--identity is required: the private age key that matches AGE_RECIPIENT")
        subprocess.run(["age", "-d", "-i", self.identity, "-o", str(plain), str(sealed)], check=True)


def _upload(store: Store, cipher: Cipher, key: str, plain: Path) -> None:
    sealed = plain.with_name(plain.name + ".age")
    cipher.encrypt(plain, sealed)
    store.put(key, sealed)
    sealed.unlink()


def _download(store: Store, cipher: Cipher, key: str, plain: Path) -> None:
    sealed = plain.with_name(plain.name + ".age")
    store.get(key, sealed)
    cipher.decrypt(sealed, plain)
    sealed.unlink()


def today() -> str:
    return datetime.now(UTC).date().isoformat()


# --- PostgreSQL -----------------------------------------------------------------------------------------------------

def libpq_url(url: str) -> str:
    """jdbc:postgresql://host:6432/db?sslmode=require, as Helm passes DB_URL, to what pg_dump reads."""
    return url.removeprefix("jdbc:")


def backup_postgres(store: Store, cipher: Cipher, url: str, day: str, work: Path,
                    run: Callable[..., object] = subprocess.run) -> str:
    """As costguard_backup: RLS shows that role every row (V21__backup_role.sql), so the dump is whole."""
    dump = work / "costguard.dump"
    run(["pg_dump", "--format=custom", "--enable-row-security", "--no-owner", "--no-privileges",
         f"--file={dump}", libpq_url(url)], check=True)
    key = f"postgres/{day}/costguard.dump.age"
    _upload(store, cipher, key, dump)
    dump.unlink()
    return key


def _psql(url: str, sql: str) -> str:
    return subprocess.run(["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", "-c", sql, libpq_url(url)],
                          check=True, capture_output=True, text=True).stdout


def restore_postgres_dump(dump: Path, url: str) -> None:
    """Into an empty database, as the role that will own it (the application role), no superuser needed.

    Under forced RLS even the owner cannot COPY into a table, and the triggers that register tenants would collide
    with the rows of the tables they fill. So the tables are created first, RLS is relaxed and user triggers are
    off while the data loads, and both come back before the policies and indexes."""
    base = ["pg_restore", "--no-owner", "--no-privileges", "--exit-on-error", f"--dbname={libpq_url(url)}"]
    subprocess.run([*base, "--section=pre-data", str(dump)], check=True)
    forced = [t for t in _psql(url, """
        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind = 'r' and c.relforcerowsecurity order by 1""").split() if t]
    tables = [t for t in _psql(url, """
        select c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
        where n.nspname = 'public' and c.relkind = 'r' order by 1""").split() if t]
    relax = [f'alter table "{t}" no force row level security;' for t in forced]
    relax += [f'alter table "{t}" disable trigger user;' for t in tables]
    _psql(url, "".join(relax) or "select 1")
    subprocess.run([*base, "--section=data", str(dump)], check=True)
    back = [f'alter table "{t}" enable trigger user;' for t in tables]
    back += [f'alter table "{t}" force row level security;' for t in forced]
    _psql(url, "".join(back) or "select 1")
    subprocess.run([*base, "--section=post-data", str(dump)], check=True)


def restore_postgres(store: Store, cipher: Cipher, day: str, url: str, work: Path) -> None:
    dump = work / "costguard.dump"
    _download(store, cipher, f"postgres/{day}/costguard.dump.age", dump)
    restore_postgres_dump(dump, url)


# --- ClickHouse -----------------------------------------------------------------------------------------------------

def _clickhouse(url: str, sql: str, database: str, body: Path | None = None, out: Path | None = None) -> None:
    query = urllib.parse.urlencode({"database": database, "query": sql})
    data = body.read_bytes() if body is not None else None
    request = urllib.request.Request(f"{url.rstrip('/')}/?{query}", data=data, method="POST", headers={
        "X-ClickHouse-User": os.environ.get("CLICKHOUSE_USER", "default"),
        "X-ClickHouse-Key": os.environ.get("CLICKHOUSE_PASSWORD", "")})
    with urllib.request.urlopen(request, timeout=3600) as response:
        if out is not None:
            with out.open("wb") as f:
                shutil.copyfileobj(response, f)
        else:
            response.read()


def backup_clickhouse(store: Store, cipher: Cipher, url: str, database: str, day: str, work: Path,
                      tables: tuple[str, ...] = CLICKHOUSE_TABLES) -> list[str]:
    """The data tables only: the Kafka queues hold nothing and the views come back with the migrations."""
    keys = []
    for table in tables:
        native = work / f"{table}.native"
        _clickhouse(url, f"SELECT * FROM {table} FORMAT Native", database, out=native)
        key = f"clickhouse/{day}/{table}.native.age"
        _upload(store, cipher, key, native)
        native.unlink()
        keys.append(key)
    return keys


def restore_clickhouse(store: Store, cipher: Cipher, url: str, database: str, day: str, work: Path,
                       tables: tuple[str, ...] = CLICKHOUSE_TABLES) -> None:
    """Into tables that exist and are empty: run schemas/clickhouse/apply.py on the target first."""
    for table in tables:
        native = work / f"{table}.native"
        _download(store, cipher, f"clickhouse/{day}/{table}.native.age", native)
        _clickhouse(url, f"INSERT INTO {table} FORMAT Native", database, body=native)
        native.unlink()


# --- files ------------------------------------------------------------------------------------------------------

MANIFEST = "files/manifest.json"


def _manifest(store: Store, work: Path) -> dict[str, str]:
    if MANIFEST not in store.list(MANIFEST):
        return {}
    path = work / "manifest.json"
    store.get(MANIFEST, path)
    return json.loads(path.read_text("utf-8"))


def backup_files(source: Store, store: Store, cipher: Cipher, work: Path) -> dict[str, int]:
    """Mirrors the uploads: a new or changed file is encrypted and copied, a deleted one is deleted. The bucket
    keeps the previous version of what was replaced or deleted for 30 days, so an erasure (ADR 0021) reaches the
    copies within that time and a mistake can still be undone within it."""
    known = _manifest(store, work)
    current = source.list("")
    copied = 0
    for key, etag in sorted(current.items()):
        if known.get(key) == etag:
            continue
        plain = work / "object"
        source.get(key, plain)
        _upload(store, cipher, f"files/objects/{key}.age", plain)
        plain.unlink()
        copied += 1
    removed = 0
    for key in sorted(set(known) - set(current)):
        store.delete(f"files/objects/{key}.age")
        removed += 1
    path = work / "manifest.json"
    path.write_text(json.dumps(current, sort_keys=True), "utf-8")
    store.put(MANIFEST, path)
    return {"copied": copied, "removed": removed, "kept": len(current) - copied}


def restore_files(store: Store, target: Store, cipher: Cipher, work: Path, prefix: str = "") -> int:
    restored = 0
    for key in sorted(store.list(f"files/objects/{prefix}")):
        plain = work / "object"
        _download(store, cipher, key, plain)
        target.put(key.removeprefix("files/objects/").removesuffix(".age"), plain)
        plain.unlink()
        restored += 1
    return restored


# --- status ------------------------------------------------------------------------------------------------------

def newest(store: Store) -> dict[str, str | None]:
    """The newest day with a copy, per kind: what the runbook checks before it restores."""
    out: dict[str, str | None] = {}
    for kind in ("postgres", "clickhouse"):
        days = sorted({k.split("/")[1] for k in store.list(f"{kind}/") if k.count("/") >= 2})
        out[kind] = days[-1] if days else None
    out["files"] = "mirrored" if store.list(MANIFEST) else None
    return out


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="costguard-backup", description=__doc__.split("\n\n")[0])
    sub = parser.add_subparsers(dest="command", required=True)
    for name in ("postgres", "clickhouse", "files", "status"):
        sub.add_parser(name)
    rp = sub.add_parser("restore-postgres")
    rp.add_argument("--day", default=today())
    rp.add_argument("--identity", required=True)
    rp.add_argument("--target", required=True, help="libpq URL of an empty database owned by the application role")
    rc = sub.add_parser("restore-clickhouse")
    rc.add_argument("--day", default=today())
    rc.add_argument("--identity", required=True)
    rc.add_argument("--database", default=os.environ.get("CLICKHOUSE_DATABASE", "costguard"))
    rf = sub.add_parser("restore-files")
    rf.add_argument("--identity", required=True)
    rf.add_argument("--prefix", default="", help="only these files, for example <tenant id>/")
    rf.add_argument("--to", default=os.environ.get("BACKUP_FILES_SOURCE"), help="where the files go back")
    args = parser.parse_args(argv)

    store = store_of(os.environ["BACKUP_TARGET"])
    cipher = Age(recipient=os.environ.get("AGE_RECIPIENT"), identity=getattr(args, "identity", None))
    with tempfile.TemporaryDirectory(prefix="costguard-backup-") as d:
        work = Path(d)
        if args.command == "postgres":
            print(backup_postgres(store, cipher, os.environ["BACKUP_DB_URL"], today(), work))
        elif args.command == "clickhouse":
            print(*backup_clickhouse(store, cipher, os.environ["CLICKHOUSE_URL"],
                                     os.environ.get("CLICKHOUSE_DATABASE", "costguard"), today(), work), sep="\n")
        elif args.command == "files":
            print(json.dumps(backup_files(store_of(os.environ["BACKUP_FILES_SOURCE"]), store, cipher, work)))
        elif args.command == "status":
            print(json.dumps(newest(store), indent=2))
        elif args.command == "restore-postgres":
            date.fromisoformat(args.day)
            restore_postgres(store, cipher, args.day, args.target, work)
        elif args.command == "restore-clickhouse":
            date.fromisoformat(args.day)
            restore_clickhouse(store, cipher, os.environ["CLICKHOUSE_URL"], args.database, args.day, work)
        elif args.command == "restore-files":
            if not args.to:
                parser.error("--to or BACKUP_FILES_SOURCE: the bucket or directory the files go back to")
            print(restore_files(store, store_of(args.to), cipher, work, args.prefix))
    return 0


if __name__ == "__main__":
    sys.exit(main())
