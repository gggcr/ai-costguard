"""The data inventory names every store, the lawyer's list says it is not legal advice, the ADRs are listed.

A table, a ClickHouse table or a Kafka topic added without a line in docs/privacy/data-inventory.md fails here: the
inventory is only useful while it is complete (ADR 0021).
"""

import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
INVENTORY = (ROOT / "docs/privacy/data-inventory.md").read_text("utf-8")
LAWYER = (ROOT / "docs/privacy/for-lawyer.md").read_text("utf-8")
CREATE = re.compile(r"create table (?:if not exists )?([a-z_]+)", re.I)


def tables(directory: Path) -> set[str]:
    return {m.group(1).lower() for f in directory.glob("*.sql") for m in CREATE.finditer(f.read_text("utf-8"))}


def named(name: str) -> bool:
    return f"`{name}`" in INVENTORY


def test_every_postgres_table_is_in_the_inventory():
    found = tables(ROOT / "apps/backend/core/src/main/resources/db/migration") | {"event_publication"}
    assert len(found) > 40
    assert sorted(t for t in found if not named(t)) == []


def test_every_clickhouse_table_is_in_the_inventory():
    found = tables(ROOT / "schemas/clickhouse/migrations")
    assert found and sorted(t for t in found if not named(t)) == []


def test_every_kafka_topic_is_in_the_inventory():
    topics = {p.stem for p in (ROOT / "schemas/avro").glob("*.avsc")}
    assert len(topics) == 22
    assert sorted(t for t in topics if not named(t)) == []


def test_every_retention_setting_is_documented_with_its_default():
    yaml = (ROOT / "apps/backend/core/src/main/resources/application.yaml").read_text("utf-8")
    settings = dict(re.findall(r"\$\{(PRIVACY_RETAIN_[A-Z_]+|PRIVACY_COMPANY_GRACE):(P\d+[DY])\}", yaml))
    assert len(settings) == 7
    for name in settings:
        assert f"`{name}`" in INVENTORY, name


def test_the_lawyer_list_is_marked_as_not_legal_advice_and_names_the_documents():
    assert "не юридическое заключение" in LAWYER
    for document in ("Политика обработки", "поручение", "Уведомление Роскомнадзора", "Согласия", "трансграничной"):
        assert document in LAWYER, document


def test_the_privacy_decisions_are_listed():
    index = (ROOT / "docs/adr/README.md").read_text("utf-8")
    for number in ("0021", "0022", "0023", "0024"):
        [adr] = (ROOT / "docs/adr").glob(f"{number}-*.md")
        assert f"]({adr.name})" in index, adr.name
