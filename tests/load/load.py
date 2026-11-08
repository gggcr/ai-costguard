"""Seeding and checking the gateway load test (tests/load/spend.js).

    python tests/load/load.py seed  --limit 500000      # an agent with a hard limit, through Kafka
    k6 run tests/load/spend.js ...                      # the variables seed printed
    python tests/load/load.py check --summary summary.json

`seed` publishes what the core would publish for one agent: its state with a virtual key (agents.state.v1) and
a company and an agent budget (budgets.limits.v1), then waits until the gateway knows the key. `check` reads the
agent's budget from Redis after the run: spent plus reserved must never be above the limit, whatever k6 saw. It
speaks RESP over a socket itself, so it needs no Redis client.
"""

from __future__ import annotations

import argparse
import json
import os
import secrets
import socket
import sys
import time
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

MICRO = Decimal(1_000_000)


def state(tenant: uuid.UUID, agent: uuid.UUID, secret: str, now: datetime) -> dict:
    import hashlib

    return {"tenant_id": str(tenant), "agent_id": str(agent),
            "virtual_key_hashes": [hashlib.sha256(secret.encode()).hexdigest()], "suspended": False,
            "mandate_version": 1, "mandate_expression": "cost <= 100000.0",
            "approval_threshold": Decimal("100000.000000"), "updated_at": now}


def limits(tenant: uuid.UUID, agent: uuid.UUID, company: uuid.UUID, budget: uuid.UUID, limit: Decimal,
           version: int) -> list[dict]:
    def one(budget_id, parent, level, ref, amount):
        return {"tenant_id": str(tenant), "budget_id": str(budget_id),
                "parent_budget_id": None if parent is None else str(parent), "level": level,
                "ref_id": None if ref is None else str(ref), "limit_amount": amount.quantize(Decimal("0.000001")),
                "currency": "RUB", "correction": Decimal("0.000000"), "period_start": date(2026, 1, 1),
                "period_end": date(2099, 12, 31), "version": version}

    return [one(company, None, "COMPANY", None, limit * 100), one(budget, company, "AGENT", agent, limit)]


def budget_key(tenant: str, budget: str) -> str:
    return f"cg:{{{tenant}}}:b:{budget}"


def redis_hmget(host: str, port: int, key: str, fields: list[str], password: str | None = None) -> list[str | None]:
    def command(*parts: str) -> bytes:
        out = f"*{len(parts)}\r\n".encode()
        for p in parts:
            raw = p.encode()
            out += f"${len(raw)}\r\n".encode() + raw + b"\r\n"
        return out

    with socket.create_connection((host, port), timeout=10) as s:
        reader = s.makefile("rb")
        if password:
            s.sendall(command("AUTH", password))
            reader.readline()
        s.sendall(command("HMGET", key, *fields))
        header = reader.readline()
        if not header.startswith(b"*"):
            raise RuntimeError(f"Redis answered {header!r}")
        values = []
        for _ in range(int(header[1:])):
            size = int(reader.readline()[1:])
            if size < 0:
                values.append(None)
                continue
            values.append(reader.read(size + 2)[:-2].decode())
        return values


def verdict(limit: str | None, spent: str | None, reserved: str | None, settled: float | None) -> list[str]:
    """What is wrong with the budget after the run; empty when the hard limit held."""
    if limit is None:
        return ["The agent's budget is not in Redis: was the gateway seeded?"]
    cap, used, held = (Decimal(v or 0) / MICRO for v in (limit, spent, reserved))
    problems = []
    if used + held > cap:
        problems.append(f"Over the hard limit: spent {used} + reserved {held} > limit {cap}")
    if settled is not None and Decimal(str(settled)) > cap:
        problems.append(f"k6 settled {settled}, above the limit {cap}")
    return problems


def seed(args) -> None:
    import httpx
    from confluent_kafka import Producer

    from costguard_common.registry import SchemaRegistry
    from costguard_common.wire import Codec

    tenant, agent, company, budget = (uuid.uuid4() for _ in range(4))
    secret = "cg_live_load_" + secrets.token_hex(16)
    codec = Codec(SchemaRegistry(args.schema_registry))
    producer = Producer({"bootstrap.servers": args.kafka, "acks": "all"})
    now = datetime.now(UTC)
    producer.produce("agents.state.v1", key=str(agent).encode(),
                     value=codec.encode("agents.state.v1", state(tenant, agent, secret, now)))
    for record in limits(tenant, agent, company, budget, Decimal(str(args.limit)), 1):
        producer.produce("budgets.limits.v1", key=record["budget_id"].encode(),
                         value=codec.encode("budgets.limits.v1", record))
    producer.flush(30)
    with httpx.Client(base_url=args.gateway, timeout=10) as http:
        deadline = time.monotonic() + 120
        while True:
            probe = str(uuid.uuid4())
            r = http.post("/v1/spend/authorize", json={"vendor": "Нагрузка", "kind": "paid_api", "amount": 0},
                          headers={"Authorization": f"Bearer {secret}", "Idempotency-Key": probe})
            if r.status_code == 200:
                http.post("/v1/spend/release", headers={"Authorization": f"Bearer {secret}"},
                          json={"authorizationId": probe})
                break
            if time.monotonic() > deadline:
                raise SystemExit(f"The gateway does not know the agent yet: {r.status_code} {r.text[:200]}")
            time.sleep(2)
    out = {"LOAD_SECRET": secret, "LOAD_TENANT": str(tenant), "LOAD_BUDGET": str(budget),
           "LOAD_LIMIT": str(args.limit)}
    if args.env_file:
        with open(args.env_file, "a", encoding="utf-8") as f:
            f.writelines(f"{k}={v}\n" for k, v in out.items())
    print(json.dumps(out))


def check(args) -> None:
    limit, spent, reserved = redis_hmget(args.redis_host, args.redis_port,
                                         budget_key(args.tenant, args.budget), ["limit", "spent", "reserved"],
                                         os.environ.get("REDIS_PASSWORD"))
    settled = None
    if args.summary:
        with open(args.summary, encoding="utf-8") as f:
            settled = json.load(f)["metrics"].get("settled_amount", {}).get("count")
    problems = verdict(limit, spent, reserved, settled)
    print(json.dumps({"limit": limit, "spent": spent, "reserved": reserved, "k6_settled": settled}))
    if problems:
        print("\n".join(problems), file=sys.stderr)
        raise SystemExit(1)
    print("The hard limit held")


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="load.py")
    sub = parser.add_subparsers(dest="step", required=True)
    s = sub.add_parser("seed")
    s.add_argument("--limit", type=float, default=500_000)
    s.add_argument("--kafka", default=os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"))
    s.add_argument("--schema-registry", default=os.environ.get("SCHEMA_REGISTRY_URL", "http://localhost:8085"))
    s.add_argument("--gateway", default=os.environ.get("GATEWAY_URL", "http://localhost:8081"))
    s.add_argument("--env-file", default=os.environ.get("GITHUB_ENV"))
    c = sub.add_parser("check")
    c.add_argument("--tenant", default=os.environ.get("LOAD_TENANT"))
    c.add_argument("--budget", default=os.environ.get("LOAD_BUDGET"))
    c.add_argument("--redis-host", default=os.environ.get("REDIS_HOST", "localhost"))
    c.add_argument("--redis-port", type=int, default=int(os.environ.get("REDIS_PORT", "6379")))
    c.add_argument("--summary")
    args = parser.parse_args(argv)
    seed(args) if args.step == "seed" else check(args)


if __name__ == "__main__":
    main()
