"""The gateway reaches the managed Redis with the password Terraform made, and Redis never evicts a budget counter
(docs/runbooks/redis.md)."""

import re
from pathlib import Path

import yaml

ROOT = Path(__file__).resolve().parents[2]


def test_the_gateway_sends_the_password_terraform_puts_in_the_secret():
    gateway = yaml.safe_load((ROOT / "apps/backend/gateway/src/main/resources/application.yaml").read_text("utf-8"))
    password = gateway["spring"]["data"]["redis"]["password"]
    assert password == "${REDIS_PASSWORD:}"
    outputs = (ROOT / "deploy/terraform/yandex/outputs.tf").read_text("utf-8")
    assert re.search(r"REDIS_PASSWORD\s*=\s*random_password\.redis\.result", outputs)
    main = (ROOT / "deploy/terraform/yandex/main.tf").read_text("utf-8")
    assert "password = random_password.redis.result" in main


def test_redis_never_evicts():
    main = (ROOT / "deploy/terraform/yandex/main.tf").read_text("utf-8")
    block = main[main.index('resource "yandex_mdb_redis_cluster" "main"'):]
    block = block[:block.index("\n}\n")]
    assert re.search(r'maxmemory_policy\s*=\s*"NOEVICTION"', block)


def test_every_runbook_is_in_the_index():
    runbooks = ROOT / "docs/runbooks"
    index = (runbooks / "README.md").read_text("utf-8")
    missing = [p.name for p in runbooks.glob("*.md") if p.name != "README.md" and f"]({p.name})" not in index]
    assert missing == []
