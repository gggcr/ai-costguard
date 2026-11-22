import importlib.util
import json
import sys
from datetime import date
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


def load(path):
    spec = importlib.util.spec_from_file_location(path.stem, path)
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module  # dataclasses look their module up
    spec.loader.exec_module(module)
    return module


codegen = load(ROOT / "tools/codegen/openapi_ts.py")
compat = load(ROOT / "tools/codegen/avro_compat.py")
seed = load(ROOT / "tools/seed/seed.py")


def test_generated_types_are_up_to_date():
    assert codegen.main(["--check"]) == 0


def test_types_of_a_schema():
    schema = {"type": "object", "required": ["id"], "properties": {
        "id": {"type": "string", "format": "uuid"}, "tags": {"type": "array", "items": {"type": "string"}},
        "status": {"type": "string", "enum": ["new", "done"]}, "owner": {"$ref": "#/components/schemas/User"},
        "extra": {"type": "object", "additionalProperties": {"type": "integer"}}}}
    text = codegen.ts(schema)
    assert "id: string;  // uuid" in text and "tags?: Array<string>;" in text
    assert 'status?: "new" | "done";' in text and "owner?: User;" in text and "extra?: Record<string, number>;" in text


def test_anchors_schema_change_is_compatible():
    new = json.loads((ROOT / "schemas/avro/journal.anchors.v1.avsc").read_text("utf-8"))
    old = {**new, "fields": [f for f in new["fields"] if f["name"] != "tenant_id"]}
    compat.check(old, new)


def test_a_field_without_default_is_caught():
    old = {"type": "record", "name": "R", "fields": [{"name": "a", "type": "string"}]}
    new = {"type": "record", "name": "R", "fields": [{"name": "a", "type": "string"}, {"name": "b", "type": "int"}]}
    from fastavro._read_common import SchemaResolutionError

    with pytest.raises(SchemaResolutionError):
        compat.check(old, new)


def test_seed_is_stable_and_names_the_demo_tenant():
    first, again = seed.build(date(2026, 11, 7)), seed.build(date(2026, 11, 7))
    assert first == again
    assert str(seed.TENANT) in first and "on conflict do nothing" in first
    users = json.loads((ROOT / "infra/keycloak/costguard-users-0.json").read_text("utf-8"))
    assert users["users"][0]["attributes"]["tenant_id"] == [str(seed.TENANT)]
    # the American company (ADR 0074): its owner and employee sign in with the e-mails the seed gives them
    us = {u["email"]: u for u in users["users"] if u["attributes"]["tenant_id"] == [str(seed.TENANT_US)]}
    assert set(us) == {"alex.morgan@brightline.example", "emily.carter@brightline.example"}
    assert "'USD', 'US', 'America/New_York', 'en'" in first and all(e in first for e in us)


def test_the_demo_user_stays_local():
    """infra/keycloak/costguard-users-0.json (demo / demo) is for the local compose stack only."""
    demo = "costguard-users-0"
    assert any(demo in p.name for p in (ROOT / "infra/keycloak").iterdir())
    for path in (ROOT / "deploy").rglob("*"):
        if path.is_file() and path.suffix in {".yaml", ".yml", ".tpl", ".tf", ".json", ".example"}:
            text = path.read_text("utf-8")
            assert demo not in text and "infra/keycloak" not in text, path
    for dockerfile in (ROOT / "apps").rglob("Dockerfile*"):
        text = dockerfile.read_text("utf-8")
        assert "infra" not in text and "COPY . " not in text, dockerfile
    compose = (ROOT / "infra/local/docker-compose.yml").read_text("utf-8")
    assert "../keycloak:/opt/keycloak/data/import" in compose
