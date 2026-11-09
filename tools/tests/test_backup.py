"""Backups and restores (ADR 0025).

The first tests run anywhere: a directory stands in for the bucket and a toy cipher for age. The restore drills need
the real tools and run in CI (`backup-restore` job of .github/workflows/python.yml):

- PostgreSQL: BACKUP_TEST_PG_ADMIN (a superuser URL), pg_dump, pg_restore, psql and age on PATH. The drill applies the
  migrations, loads the seed, dumps as costguard_backup, restores into an empty database as the application role and
  compares every table row by row.
- ClickHouse: CLICKHOUSE_TEST_URL and age.
"""

from __future__ import annotations

import importlib.util
import os
import re
import shutil
import subprocess
import sys
import urllib.parse
import urllib.request
import uuid
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location("costguard_backup", ROOT / "tools/backup/backup.py")
backup = importlib.util.module_from_spec(spec)
sys.modules["costguard_backup"] = backup
spec.loader.exec_module(backup)


class Toy:
    """Not encryption: a reversible change that shows the stored copy is not the plain file."""

    def encrypt(self, plain: Path, sealed: Path) -> None:
        sealed.write_bytes(b"SEALED" + plain.read_bytes()[::-1])

    def decrypt(self, sealed: Path, plain: Path) -> None:
        data = sealed.read_bytes()
        assert data.startswith(b"SEALED")
        plain.write_bytes(data[6:][::-1])


def test_files_are_mirrored_encrypted_and_follow_deletions(tmp_path):
    source = backup.DirStore(tmp_path / "imports")
    copies = backup.DirStore(tmp_path / "backups")
    work = tmp_path / "work"
    work.mkdir()
    tenant = uuid.uuid4()
    for name, body in (("a" * 64, b"invoice one"), ("b" * 64, b"invoice two")):
        p = tmp_path / "f"
        p.write_bytes(body)
        source.put(f"{tenant}/{name}", p)

    assert backup.backup_files(source, copies, Toy(), work) == {"copied": 2, "removed": 0, "kept": 0}
    stored = copies.root / f"files/objects/{tenant}/{'a' * 64}.age"
    assert stored.read_bytes() != b"invoice one" and b"invoice one" not in stored.read_bytes()
    assert backup.backup_files(source, copies, Toy(), work) == {"copied": 0, "removed": 0, "kept": 2}

    # An erased company's file goes from the mirror too; the bucket keeps the old version 30 days.
    source.delete(f"{tenant}/{'b' * 64}")
    assert backup.backup_files(source, copies, Toy(), work)["removed"] == 1
    assert not (copies.root / f"files/objects/{tenant}/{'b' * 64}.age").exists()

    restored = backup.DirStore(tmp_path / "restored")
    assert backup.restore_files(copies, restored, Toy(), work, prefix=f"{tenant}/") == 1
    assert (restored.root / f"{tenant}/{'a' * 64}").read_bytes() == b"invoice one"


def test_a_postgres_dump_is_taken_with_rls_rules_for_the_backup_role_and_never_stored_plain(tmp_path):
    copies = backup.DirStore(tmp_path / "backups")
    seen = []

    def run(args, check):
        seen.append(args)
        Path(next(a for a in args if a.startswith("--file=")).removeprefix("--file=")).write_bytes(b"PGDMP...")

    key = backup.backup_postgres(copies, Toy(), "jdbc:postgresql://db:6432/costguard?sslmode=require", "2026-11-09",
                                 tmp_path, run=run)
    assert key == "postgres/2026-11-09/costguard.dump.age"
    assert "--enable-row-security" in seen[0]
    assert seen[0][-1] == "postgresql://db:6432/costguard?sslmode=require"
    assert (copies.root / key).read_bytes().startswith(b"SEALED")
    assert not list(tmp_path.glob("*.dump"))
    assert backup.newest(copies) == {"postgres": "2026-11-09", "clickhouse": None, "files": None}


def test_age_refuses_to_write_without_a_recipient(tmp_path):
    p = tmp_path / "x"
    p.write_bytes(b"x")
    with pytest.raises(SystemExit, match="AGE_RECIPIENT"):
        backup.Age(recipient="").encrypt(p, tmp_path / "x.age")
    with pytest.raises(SystemExit, match="identity"):
        backup.Age().decrypt(p, tmp_path / "y")


def test_targets():
    assert isinstance(backup.store_of("file:///tmp/b"), backup.DirStore)
    with pytest.raises(ValueError):
        backup.store_of("ftp://x")


# --- drills with the real tools ---------------------------------------------------------------------------------

def _age_key(tmp_path: Path) -> tuple[str, str]:
    key = tmp_path / "age.key"
    subprocess.run(["age-keygen", "-o", str(key)], check=True, capture_output=True)
    recipient = re.search(r"public key: (age1\w+)", key.read_text()).group(1)
    return recipient, str(key)


PG_TOOLS = all(shutil.which(t) for t in ("pg_dump", "pg_restore", "psql", "age", "age-keygen"))


@pytest.mark.skipif(not (os.environ.get("BACKUP_TEST_PG_ADMIN") and PG_TOOLS),
                    reason="needs BACKUP_TEST_PG_ADMIN, pg_dump, pg_restore, psql and age")
def test_postgres_restores_into_an_empty_database_with_every_row_and_its_rls(tmp_path):
    admin = os.environ["BACKUP_TEST_PG_ADMIN"]
    parts = urllib.parse.urlsplit(admin)
    host = f"{parts.hostname}:{parts.port or 5432}"

    def psql(url, sql=None, file=None, stdin=None):
        args = ["psql", "-X", "-q", "-At", "-v", "ON_ERROR_STOP=1", url]
        args += ["-c", sql] if sql else ["-f", str(file)] if file else []
        return subprocess.run(args, check=True, capture_output=True, text=True, input=stdin).stdout

    psql(admin, """do $$ begin
        if not exists (select 1 from pg_roles where rolname = 'costguard') then
            create role costguard login password 'costguard' nosuperuser nobypassrls; end if;
        if not exists (select 1 from pg_roles where rolname = 'costguard_backup') then
            create role costguard_backup login password 'backup' nosuperuser nobypassrls; end if;
        end $$""")
    for db in ("backup_source", "backup_restored"):
        psql(admin, f"drop database if exists {db}")
        psql(admin, f"create database {db} owner costguard")
    app = lambda db: f"postgresql://costguard:costguard@{host}/{db}"  # noqa: E731
    su = lambda db: f"postgresql://{parts.username}:{parts.password}@{host}/{db}"  # noqa: E731

    migrations = sorted((ROOT / "apps/backend/core/src/main/resources/db/migration").glob("V*.sql"),
                        key=lambda p: int(p.name[1:].split("__")[0]))
    for m in migrations:
        psql(app("backup_source"), file=m)
    seed = subprocess.run([sys.executable, str(ROOT / "tools/seed/seed.py")], check=True, capture_output=True,
                          text=True).stdout
    psql(su("backup_source"), stdin=seed)
    psql(app("backup_source"), file=ROOT / "tools/backup/grant-backup-role.sql")

    recipient, identity = _age_key(tmp_path)
    copies = backup.DirStore(tmp_path / "backups")
    work = tmp_path / "work"
    work.mkdir()
    backup.backup_postgres(copies, backup.Age(recipient=recipient),
                           f"postgresql://costguard_backup:backup@{host}/backup_source", "2026-11-09", work)
    backup.restore_postgres(copies, backup.Age(identity=identity), "2026-11-09", app("backup_restored"), work)

    tables = psql(su("backup_source"), "select tablename from pg_tables where schemaname = 'public' order by 1").split()
    assert "employee" in tables and "receipt" in tables
    digest = "select count(*) || ':' || coalesce(md5(string_agg(t::text, '|' order by t::text)), '') from {} t"
    for table in tables:
        before = psql(su("backup_source"), digest.format(f'"{table}"'))
        after = psql(su("backup_restored"), digest.format(f'"{table}"'))
        assert before == after, table
    assert int(psql(su("backup_source"), "select count(*) from employee").strip()) > 0

    meta = """select string_agg(c.relname || ':' || c.relrowsecurity || c.relforcerowsecurity, ',' order by c.relname)
              from pg_class c join pg_namespace n on n.oid = c.relnamespace where n.nspname = 'public'
              and c.relkind = 'r'"""
    assert psql(su("backup_source"), meta) == psql(su("backup_restored"), meta)
    policies = "select count(*) from pg_policies where schemaname = 'public'"
    assert psql(su("backup_source"), policies) == psql(su("backup_restored"), policies)
    disabled = "select count(*) from pg_trigger where not tgisinternal and tgenabled = 'D'"
    assert psql(su("backup_restored"), disabled).strip() == "0"
    # Isolation came back with the data: the application role sees no company without binding one.
    assert psql(app("backup_restored"), "select count(*) from employee").strip() == "0"


@pytest.mark.skipif(not (os.environ.get("CLICKHOUSE_TEST_URL") and shutil.which("age") and shutil.which("age-keygen")),
                    reason="needs CLICKHOUSE_TEST_URL and age")
def test_clickhouse_tables_restore_into_another_database(tmp_path):
    url = os.environ["CLICKHOUSE_TEST_URL"]

    def ch(sql, database="default"):
        q = urllib.parse.urlencode({"database": database, "query": sql})
        with urllib.request.urlopen(urllib.request.Request(f"{url}/?{q}", method="POST")) as r:
            return r.read().decode()

    table = "cost_records"
    columns = "(id UUID, tenant_id UUID, billed_cost Decimal(18, 6)) ENGINE = MergeTree ORDER BY id"
    for db in ("backup_src", "backup_dst"):
        ch(f"DROP DATABASE IF EXISTS {db}")
        ch(f"CREATE DATABASE {db}")
        ch(f"CREATE TABLE {table} {columns}", db)
    ch(f"INSERT INTO {table} SELECT generateUUIDv4(), generateUUIDv4(), number / 3 FROM numbers(1000)", "backup_src")

    recipient, identity = _age_key(tmp_path)
    copies = backup.DirStore(tmp_path / "backups")
    work = tmp_path / "work"
    work.mkdir()
    backup.backup_clickhouse(copies, backup.Age(recipient=recipient), url, "backup_src", "2026-11-09", work,
                             tables=(table,))
    backup.restore_clickhouse(copies, backup.Age(identity=identity), url, "backup_dst", "2026-11-09", work,
                              tables=(table,))
    check = f"SELECT count(), sum(billed_cost), groupBitXor(cityHash64(id)) FROM {table}"
    assert ch(check, "backup_src") == ch(check, "backup_dst")
    assert ch(f"SELECT count() FROM {table}", "backup_dst").strip() == "1000"
