"""Applies migrations/*.sql to ClickHouse in name order, once each, and records them in schema_migrations.

    CLICKHOUSE_URL=http://localhost:8123 CLICKHOUSE_USER=costguard CLICKHOUSE_PASSWORD=… \
    KAFKA_BROKERS=kafka:29092 SCHEMA_REGISTRY_URL=http://schema-registry:8081 python schemas/clickhouse/apply.py

${NAME} in a migration is replaced by the environment variable NAME; a missing one stops the run. Statements are
separated by a semicolon at the end of a line. A migration that changed after it was applied stops the run too:
write a new one instead.
"""

from __future__ import annotations

import hashlib
import os
import re
import sys
from pathlib import Path

import httpx

MIGRATIONS = Path(__file__).parent / "migrations"


def render(sql: str, env: dict[str, str]) -> str:
    def value(m: re.Match) -> str:
        if m.group(1) not in env:
            raise SystemExit(f"Environment variable {m.group(1)} is required")
        return env[m.group(1)].replace("'", "\'")

    return re.sub(r"\$\{([A-Z_]+)\}", value, sql)


def statements(sql: str) -> list[str]:
    body = "\n".join(line for line in sql.splitlines() if not line.strip().startswith("--"))
    return [s.strip() for s in re.split(r";\s*$", body, flags=re.M) if s.strip()]


def apply(client: httpx.Client, database: str, env: dict[str, str], directory: Path = MIGRATIONS) -> list[str]:
    def run(query: str) -> str:
        r = client.post("/", params={"database": database}, content=query.encode())
        if r.status_code != 200:
            raise RuntimeError(f"ClickHouse refused: {r.text.strip()[:500]}\n{query[:300]}")
        return r.text

    run("CREATE TABLE IF NOT EXISTS schema_migrations (name String, checksum String, applied_at DateTime "
        "DEFAULT now()) ENGINE = MergeTree ORDER BY name")
    done = dict(line.split("\t") for line in run("SELECT name, checksum FROM schema_migrations FORMAT TSV")
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
        run(f"INSERT INTO schema_migrations (name, checksum) VALUES ('{path.name}', '{checksum}')")
        applied.append(path.name)
    return applied


def main() -> None:
    env = dict(os.environ)
    client = httpx.Client(base_url=env.get("CLICKHOUSE_URL", "http://localhost:8123"), timeout=60,
                          auth=(env.get("CLICKHOUSE_USER", "default"), env.get("CLICKHOUSE_PASSWORD", "")))
    names = apply(client, env.get("CLICKHOUSE_DB", "costguard"), env)
    print("Applied: " + (", ".join(names) or "nothing new"), file=sys.stderr)


if __name__ == "__main__":
    main()
