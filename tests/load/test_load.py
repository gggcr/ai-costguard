"""The pieces of the load test that run without the stack: seeded messages, the Redis check, the k6 script."""

import importlib.util
import io
import json
import shutil
import socket
import subprocess
import threading
import uuid
from datetime import UTC, datetime
from decimal import Decimal
from pathlib import Path

import fastavro
import pytest

from costguard_common import schemas

HERE = Path(__file__).parent
spec = importlib.util.spec_from_file_location("load", HERE / "load.py")
load = importlib.util.module_from_spec(spec)
spec.loader.exec_module(load)

TENANT, AGENT, COMPANY, BUDGET = (uuid.UUID(int=i) for i in range(1, 5))


def fits(topic, value):
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schemas.parsed(topic), value)
    return fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schemas.parsed(topic))


def test_the_seeded_agent_and_budgets_fit_their_topics():
    state = fits("agents.state.v1", load.state(TENANT, AGENT, "cg_live_x", datetime.now(UTC)))
    assert state["suspended"] is False and state["mandate_version"] == 1
    company, agent = (fits("budgets.limits.v1", r) for r in load.limits(TENANT, AGENT, COMPANY, BUDGET,
                                                                         Decimal("500000"), 1))
    assert (company["level"], company["parent_budget_id"]) == ("COMPANY", None)
    assert (agent["level"], str(agent["ref_id"]), str(agent["parent_budget_id"])) == ("AGENT", str(AGENT),
                                                                                    str(COMPANY))
    assert agent["limit_amount"] == Decimal("500000.000000") and company["limit_amount"] > agent["limit_amount"]


def test_the_key_hash_is_the_gateways():
    import hashlib

    state = load.state(TENANT, AGENT, "cg_live_x", datetime.now(UTC))
    assert state["virtual_key_hashes"] == [hashlib.sha256(b"cg_live_x").hexdigest()]
    assert load.budget_key(str(TENANT), str(BUDGET)) == f"cg:{{{TENANT}}}:b:{BUDGET}"


@pytest.mark.parametrize("spent,reserved,settled,ok", [
    ("499990000000", "10000000", 499990.0, True),   # exactly at the limit
    ("500000000000", "1", None, False),              # one micro-rouble over
    ("100", "0", 600000.0, False),                   # k6 settled more than the limit
])
def test_the_verdict(spent, reserved, settled, ok):
    problems = load.verdict("500000000000", spent, reserved, settled)
    assert (problems == []) is ok


def test_no_budget_in_redis_is_a_failure():
    assert "not in Redis" in load.verdict(None, None, None, None)[0]


def test_hmget_speaks_resp():
    server = socket.socket()
    server.bind(("127.0.0.1", 0))
    server.listen(1)
    seen = []

    def serve():
        conn, _ = server.accept()
        with conn:
            seen.append(conn.recv(4096))
            conn.sendall(b"*3\r\n$12\r\n500000000000\r\n$4\r\n1234\r\n$-1\r\n")

    threading.Thread(target=serve, daemon=True).start()
    values = load.redis_hmget("127.0.0.1", server.getsockname()[1], "cg:{t}:b:b", ["limit", "spent", "reserved"])
    server.close()
    assert values == ["500000000000", "1234", None]
    assert seen[0].startswith(b"*5\r\n$5\r\nHMGET\r\n$10\r\ncg:{t}:b:b\r\n")


def test_check_reads_the_k6_summary(tmp_path, monkeypatch):
    summary = tmp_path / "summary.json"
    summary.write_text(json.dumps({"metrics": {"settled_amount": {"count": 1000.5}}}), "utf-8")
    monkeypatch.setattr(load, "redis_hmget", lambda *a: ["500000000000", "1000500000", "0"])
    load.main(["check", "--tenant", str(TENANT), "--budget", str(BUDGET), "--summary", str(summary)])
    monkeypatch.setattr(load, "redis_hmget", lambda *a: ["500000000000", "500000000001", "0"])
    with pytest.raises(SystemExit):
        load.main(["check", "--tenant", str(TENANT), "--budget", str(BUDGET), "--summary", str(summary)])


def test_the_k6_script_parses_and_guards_the_limit():
    script = (HERE / "spend.js").read_text("utf-8")
    assert "settled_amount: [`count<=${LIMIT}`]" in script and "unexpected: ['count==0']" in script
    assert "constant-arrival-rate" in script
    node = shutil.which("node")
    if node is None:
        pytest.skip("node is not installed here")
    probe = ("const vm = require('vm'); new vm.SourceTextModule(require('fs').readFileSync(process.argv[1], 'utf8'));")
    done = subprocess.run([node, "--experimental-vm-modules", "-e", probe, str(HERE / "spend.js")],
                          capture_output=True, text=True)
    assert done.returncode == 0, done.stderr
