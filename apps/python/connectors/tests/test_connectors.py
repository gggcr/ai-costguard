import io
import json
import uuid
from datetime import date
from decimal import Decimal
from email.message import EmailMessage
from urllib.parse import parse_qs, urlparse

import fastavro
import httpx
import pytest

from costguard_common import normalized, schemas
from costguard_connectors import agent, bitrix24, contract, mail, moysklad, onec, yandex360

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")


def fits(message: contract.Message) -> None:
    schema = schemas.parsed(message.topic)
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schema, message.value)
    assert fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schema)


def client(routes, base="http://x", **kw) -> httpx.Client:
    """routes: path -> callable(request) -> json body, or a body."""
    seen: list[httpx.Request] = []

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        for path, body in routes.items():
            if request.url.path.endswith(path):
                value = body(request) if callable(body) else body
                if isinstance(value, httpx.Response):
                    return value
                return httpx.Response(200, json=value)
        return httpx.Response(404, json={"error": request.url.path})

    c = httpx.Client(base_url=base, transport=httpx.MockTransport(handle), **kw)
    c.seen = seen
    return c


def of(batch, kind):
    return [e for e in batch.entities if e.type == kind]


# keys


def test_vendor_keys_agree_across_spellings():
    assert normalized.vendor_key(TENANT, 'ООО "Ромашка"') == normalized.vendor_key(TENANT, "Ромашка ООО")
    assert normalized.vendor_key(TENANT, "ООО «Ромашка»") == normalized.vendor_key(TENANT, "ромашка")
    assert normalized.vendor_key(TENANT, "x", "7700000001") == normalized.vendor_key(TENANT, "y", "77 0000 0001")
    assert normalized.vendor_key(TENANT, "x", "123") == normalized.vendor_key(TENANT, "x")


def test_person_keys_prefer_email():
    assert normalized.person_key(TENANT, " Ivanov@Corp.RU ", "a") == normalized.person_key(TENANT, "ivanov@corp.ru")
    assert normalized.person_key(TENANT, "not-an-email", "Иванов  Иван") == normalized.person_key(TENANT,
                                                                                                    None, "иванов иван")


# 1C

ONEC = {
    "Catalog_Контрагенты": {"value": [
        {"Ref_Key": "v1", "Description": "Ромашка", "НаименованиеПолное": 'ООО "Ромашка"', "ИНН": "7700000001",
         "DeletionMark": False},
        {"Ref_Key": "v2", "Description": "Удалённый", "DeletionMark": True},
    ]},
    "Catalog_Валюты": {"value": [{"Ref_Key": "c1", "Description": "руб.", "Code": "643"}]},
    "Document_ПоступлениеТоваровУслуг": lambda r: {"value": [
        {"Ref_Key": "d1", "Number": "0000-000015 ", "Date": "2026-10-05T00:00:00", "Контрагент_Key": "v1",
         "СуммаДокумента": 12000.5, "ВалютаДокумента_Key": "c1"},
        {"Ref_Key": "d2", "Number": "16", "Date": "2026-10-06T00:00:00", "Контрагент_Key": "unknown",
         "СуммаДокумента": 1},
    ]},
}


def test_onec():
    http = client(ONEC)
    batch = onec.OneC(http).pull(TENANT, "2026-09-30T00:00:00")
    [vendor] = of(batch, normalized.EntityType.VENDOR)
    assert vendor.payload["inn"] == "7700000001"
    [invoice] = of(batch, normalized.EntityType.INVOICE)
    assert invoice.payload["number"] == "0000-000015"
    [cost] = batch.costs
    assert cost.billed_cost == Decimal("12000.500000") and cost.billing_currency == "RUB"
    assert cost.charge_period_start == date(2026, 10, 5)
    assert batch.cursor == "2026-10-06T00:00:00"
    receipts = next(r for r in http.seen if "Document" in r.url.path)
    assert "Date gt datetime'2026-09-30T00:00:00'" in parse_qs(urlparse(str(receipts.url)).query)["$filter"][0]


def test_onec_pages():
    calls = []

    def page(r):
        skip = int(r.url.params["$skip"])
        calls.append(skip)
        return {"value": [{"Ref_Key": f"v{skip + i}", "Description": f"V{skip + i}"} for i in range(2 if skip < 4
                                                                                                     else 1)]}

    batch = onec.OneC(client({"Catalog_Контрагенты": page, "Catalog_Валюты": {"value": []},
                              "Document_ПоступлениеТоваровУслуг": {"value": []}}), page=2).pull(TENANT, None)
    assert calls == [0, 2, 4]
    assert len(of(batch, normalized.EntityType.VENDOR)) == 5


# МойСклад


def test_moysklad():
    http = client({
        "/entity/counterparty": {"meta": {"size": 1}, "rows": [
            {"id": "a1", "name": "Ромашка", "legalTitle": "ООО «Ромашка»", "inn": "7700000001"}]},
        "/entity/supply": {"meta": {"size": 2}, "rows": [
            {"id": "s1", "name": "00015", "moment": "2026-10-05 12:00:00.000", "sum": 1200050, "applicable": True,
             "updated": "2026-10-05 12:01:00.000",
             "agent": {"meta": {"href": "https://api.moysklad.ru/api/remap/1.2/entity/counterparty/a1"}}},
            {"id": "s2", "name": "draft", "moment": "2026-10-06 12:00:00.000", "sum": 1, "applicable": False,
             "agent": {"meta": {"href": ".../a1"}}},
        ]},
    })
    batch = moysklad.MoySklad(http).pull(TENANT, None)
    [cost] = batch.costs
    assert cost.billed_cost == Decimal("12000.500000")
    assert batch.cursor == "2026-10-05 12:01:00.000"
    # the same supplier from 1C lands on the same key
    onec_vendor = of(onec.OneC(client(ONEC)).pull(TENANT, None), normalized.EntityType.VENDOR)[0]
    assert of(batch, normalized.EntityType.VENDOR)[0].key == onec_vendor.key


# Bitrix24


def test_bitrix24():
    def users(r):
        start = json.loads(r.content)["start"]
        if start == 0:
            return {"result": [{"ID": "1", "NAME": "Иван", "LAST_NAME": "Иванов", "EMAIL": "ivanov@corp.ru",
                                "ACTIVE": True, "UF_DEPARTMENT": [5], "LAST_LOGIN": "2026-08-01T10:00:00+03:00"}],
                    "next": 1, "total": 2}
        return {"result": [{"ID": "2", "NAME": "Пётр", "EMAIL": "petrov@corp.ru", "ACTIVE": False}], "total": 2}

    batch = bitrix24.Bitrix24(client({"department.get.json": {"result": [{"ID": "5", "NAME": "ИТ"}]},
                                      "user.get.json": users})).pull(TENANT, None)
    people = of(batch, normalized.EntityType.EMPLOYEE)
    assert [p.payload["active"] for p in people] == [True, False]
    assert people[0].payload["department"] == "ИТ"
    licenses = of(batch, normalized.EntityType.LICENSE_ASSIGNMENT)
    assert licenses[0].payload["last_active"] == date(2026, 8, 1)
    assert of(batch, normalized.EntityType.SUBSCRIPTION)[0].payload["seats"] == 2


def test_bitrix24_error():
    with pytest.raises(RuntimeError, match="insufficient_scope"):
        bitrix24.Bitrix24(client({"department.get.json": {"error": "insufficient_scope"}})).pull(TENANT, None)


# Яндекс 360


@pytest.mark.parametrize("audit_allowed", [True, False])
def test_yandex360(audit_allowed):
    audit = ({"events": [{"userUid": "u1", "date": "2026-10-01T09:00:00Z"},
                         {"userUid": "u1", "date": "2026-10-03T09:00:00Z"}]}
             if audit_allowed else httpx.Response(403, json={"message": "no scope"}))
    batch = yandex360.Yandex360(client({
        "/departments": {"departments": [{"id": 1, "name": "Продажи"}], "pages": 1},
        "/users": lambda r: {"users": [
            {"id": "u1", "email": "a@corp.ru", "name": {"first": "Анна", "last": "Смирнова"}, "departmentId": 1,
             "isEnabled": True},
            {"id": "u2", "email": "b@corp.ru", "name": {"first": "Борис"}, "isEnabled": False},
            {"id": "u3", "email": "robot@corp.ru", "isRobot": True},
        ], "pages": 1},
        "/audit_log/mail": audit,
    }), "42").pull(TENANT, None)
    people = of(batch, normalized.EntityType.EMPLOYEE)
    assert [p.payload["email"] for p in people] == ["a@corp.ru", "b@corp.ru"]
    [seat] = of(batch, normalized.EntityType.LICENSE_ASSIGNMENT)
    assert seat.payload["last_active"] == (date(2026, 10, 3) if audit_allowed else None)


# contract


def test_messages_fit_the_schemas_and_dedupe():
    batch = onec.OneC(client(ONEC)).pull(TENANT, None)
    batch.entities.append(batch.entities[0])
    out = contract.messages(TENANT, "1c", batch)
    assert len([m for m in out if m.topic == normalized.TOPIC]) == 2
    for m in out:
        fits(m)
    assert {m.value["file_id"] for m in out if m.topic == normalized.TOPIC} == {str(contract.source_id(TENANT,
                                                                                                         "1c"))}


def test_cursor_moves_only_after_publish(tmp_path):
    state = agent.State(tmp_path / "state.json")
    connector = onec.OneC(client(ONEC))

    def broken(_):
        raise RuntimeError("kafka is down")

    failures = agent.once([connector], TENANT, state, broken)
    assert "1c" in failures and state.get("1c") is None
    published = []
    assert agent.once([connector], TENANT, state, published.extend) == {}
    assert agent.State(tmp_path / "state.json").get("1c") == "2026-10-06T00:00:00"
    assert published


def test_build_from_config():
    found = agent.build({"onec": {"url": "http://1c/odata", "user": "u"}, "moysklad": {}, "bitrix24": {},
                         "yandex360": {"org_id": "1"}},
                        {"ONEC_PASSWORD": "p", "MOYSKLAD_TOKEN": "t", "BITRIX24_WEBHOOK": "https://b/rest/1/x/",
                         "YANDEX360_TOKEN": "y"})
    assert [c.name for c in found] == ["1c", "moysklad", "bitrix24", "yandex360"]


# mail


def letter(*files: tuple[str, bytes]) -> bytes:
    m = EmailMessage()
    m["Subject"] = "Счёт"
    m.set_content("Счёт во вложении")
    for name, content in files:
        m.add_attachment(content, maintype="application", subtype="octet-stream", filename=name)
    return m.as_bytes()


class FakeMailbox:
    def __init__(self, letters):
        self.letters = letters
        self.seen = []

    def unseen(self):
        return [(u, r) for u, r in self.letters if u not in self.seen]

    def mark_seen(self, uid):
        self.seen.append(uid)


def test_mail_uploads_readable_attachments():
    box = FakeMailbox([("1", letter(("счёт.pdf", b"%PDF"), ("logo.png", b"img"))), ("2", letter())])
    core = client({"/api/v1/imports": lambda r: httpx.Response(201, json={})})
    assert mail.MailInvoices(box, core).collect() == 1
    assert box.seen == ["1", "2"]
    [upload] = core.seen
    assert "счёт.pdf".encode() in upload.content or b"filename*=" in upload.content


def test_mail_keeps_a_letter_the_core_refused():
    box = FakeMailbox([("1", letter(("a.csv", b"x;y")))])
    core = client({"/api/v1/imports": lambda r: httpx.Response(503, text="down")})
    assert mail.MailInvoices(box, core).collect() == 0
    assert box.seen == []
