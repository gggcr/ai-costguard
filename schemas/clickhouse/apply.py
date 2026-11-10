"""Applies migrations/*.sql to ClickHouse in name order, once each, and records them in schema_migrations.

    CLICKHOUSE_URL=http://localhost:8123 CLICKHOUSE_USER=costguard CLICKHOUSE_PASSWORD=… \
    KAFKA_BROKERS=kafka:29092 SCHEMA_REGISTRY_URL=http://schema-registry:8081 python schemas/clickhouse/apply.py

${NAME} in a migration is replaced by the environment variable NAME; a missing one stops the run. Statements are
separated by a semicolon at the end of a line. A migration that changed after it was applied stops the run too:
write a new one instead.

With CLICKHOUSE_ACCESS_MANAGEMENT=1 it then applies access/*.sql the same way, recorded in schema_access: roles,
users and row policies (ADR 0035). They need a user who may manage access, which a managed ClickHouse grants only
with SQL user management switched on; without the variable they are left alone. ${CLICKHOUSE_DB} in them is the
database of the migrations.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys
from pathlib import Path

import httpx

MIGRATIONS = Path(__file__).parent / "migrations"
ACCESS = Path(__file__).parent / "access"


def render(sql: str, env: dict[str, str]) -> str:
    def value(m: re.Match) -> str:
        if m.group(1) not in env:
            raise SystemExit(f"Environment variable {m.group(1)} is required")
        return env[m.group(1)].replace("'", "\'")

    return re.sub(r"\$\{([A-Z_]+)\}", value, sql)


def statements(sql: str) -> list[str]:
    body = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))
    return [s.strip() for s in re.split(r";\s*$", body, flags=re.M) if s.strip()]


def apply(client: httpx.Client, database: str, env: dict[str, str], directory: Path = MIGRATIONS,
          table: str = "schema_migrations") -> list[str]:
    def run(query: str) -> str:
        r = client.post("/", params={"database": database}, content=query.encode())
        if r.status_code != 200:
            raise RuntimeError(f"ClickHouse refused: {r.text.strip()[:500]}\n{query[:300]}")
        return r.text

    run(f"CREATE TABLE IF NOT EXISTS {table} (name String, checksum String, applied_at DateTime "
        "DEFAULT now()) ENGINE = MergeTree ORDER BY name")
    done = dict(line.split("\t") for line in run(f"SELECT name, checksum FROM {table} FORMAT TSV")
                .splitlines() if line)
    applied = []
    for path in sorted(directory.glob("*.sql")):
        text = path.read_text("utf-8")
        checksum = hashlib.sha256(text.encode()).hexdigest()
        if path.name in done:
            if done[path.name] != checksum:
                raise SystemExit(f"{path.name} changed after it was applied; write a new migration")
            continue
        for statement in statements(render(text, env)):
            run(statement)
        run(f"INSERT INTO {table} (name, checksum) VALUES ('{path.name}', '{checksum}')")
        applied.append(path.name)
    return applied


def apply_access(client: httpx.Client, database: str, env: dict[str, str], directory: Path = ACCESS) -> list[str]:
    """Roles, users and row policies; the user of `client` must be allowed to manage access."""
    return apply(client, database, {**env, "CLICKHOUSE_DB": database}, directory, "schema_access")


def main() -> None:
    env = dict(os.environ)
    client = httpx.Client(base_url=env.get("CLICKHOUSE_URL", "http://localhost:8123"), timeout=60,
                          auth=(env.get("CLICKHOUSE_USER", "default"), env.get("CLICKHOUSE_PASSWORD", "")))
    database = env.get("CLICKHOUSE_DB", "costguard")
    names = apply(client, database, env)
    print("Applied: " + (", ".join(names) or "nothing new"), file=sys.stderr)
    if env.get("CLICKHOUSE_ACCESS_MANAGEMENT") == "1":
        names = apply_access(client, database, env)
        print("Access: " + (", ".join(names) or "nothing new"), file=sys.stderr)


if __name__ == "__main__":
    main()
