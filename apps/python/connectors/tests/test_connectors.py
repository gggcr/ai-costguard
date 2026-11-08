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
from costguard_connectors import agent, bitrix24, contract, entities, intune, jamf, mail, moysklad, onec, yandex360

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
    assert [p.payload["dismissed"] for p in people] == [False, True]  # Bitrix24 keeps HR: deactivated is fired
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
                         "yandex360": {"org_id": "1"}, "jamf": {"url": "https://c.jamfcloud.com", "client_id": "j"},
                         "intune": {"directory_id": "d", "client_id": "i"}},
                        {"ONEC_PASSWORD": "p", "MOYSKLAD_TOKEN": "t", "BITRIX24_WEBHOOK": "https://b/rest/1/x/",
                         "YANDEX360_TOKEN": "y", "JAMF_CLIENT_SECRET": "js", "INTUNE_CLIENT_SECRET": "is"})
    assert [c.name for c in found] == ["1c", "moysklad", "bitrix24", "yandex360", "jamf", "intune"]


# equipment


def test_asset_entity_needs_a_number_and_skips_firmware_placeholders():
    assert entities.asset(TENANT, "x", None, None, "Mac") is None
    assert entities.asset(TENANT, "x", "To be filled by O.E.M.", None, "PC") is None
    device = entities.asset(TENANT, "x", " SN1 ", None, "Mac", "TOASTER", holder_email=" Ivanov@Corp.RU ")
    assert device.payload["serial_number"] == "SN1"
    assert device.payload["type"] == "OTHER"
    assert device.payload["holder_email"] == "ivanov@corp.ru"
    assert device.key == entities.asset(TENANT, "y", "SN1", "INV-1").key  # one device, whoever reports it


def test_jamf():
    tokens: list[dict] = []

    def token(r):
        tokens.append(parse_qs(r.content.decode()))
        return {"access_token": "tok", "expires_in": 1200}

    def computers(r):
        assert r.headers["Authorization"] == "Bearer tok"
        assert parse_qs(r.url.query.decode())["section"] == ["GENERAL", "HARDWARE", "USER_AND_LOCATION"]
        page = int(parse_qs(r.url.query.decode())["page"][0])
        rows = [
            [{"id": "1", "general": {"name": "MBP-Ivanov", "assetTag": "INV-0042"},
              "hardware": {"serialNumber": "C02XYZ", "model": "MacBook Pro (14-inch, 2023)"},
              "userAndLocation": {"email": "ivanov@corp.ru", "realname": "Иванов Иван"}}],
            [{"id": "2", "general": {"name": "Studio"}, "hardware": {"serialNumber": "H7", "model": "Mac Studio"},
              "userAndLocation": {}}],
        ][page]
        return {"totalCount": 2, "results": rows}

    mobile = {"totalCount": 2, "results": [
        {"mobileDeviceId": "7", "general": {"displayName": "iPhone Петрова"},
         "hardware": {"serialNumber": "F2LX", "model": "iPhone 15"},
         "userAndLocation": {"emailAddress": "petrov@corp.ru", "realName": "Петров Пётр"}},
        {"mobileDeviceId": "8", "general": {}, "hardware": {"serialNumber": "", "model": "iPad Air"}},
    ]}
    http = client({"/api/oauth/token": token, "/api/v1/computers-inventory": computers,
                   "/api/v2/mobile-devices/detail": mobile})
    batch = jamf.Jamf(http, "id", "secret").pull(TENANT, None)

    assert tokens == [{"client_id": ["id"], "client_secret": ["secret"], "grant_type": ["client_credentials"]}]
    devices = of(batch, normalized.EntityType.ASSET)
    assert [(d.payload["serial_number"], d.payload["type"]) for d in devices] == [
        ("C02XYZ", "LAPTOP"), ("H7", "OTHER"), ("F2LX", "PHONE")]  # the iPad without a serial is left out
    first = devices[0].payload
    assert (first["inventory_number"], first["holder_email"], first["holder_name"], first["cost"]) == (
        "INV-0042", "ivanov@corp.ru", "Иванов Иван", None)
    assert devices[1].payload["holder_email"] is None
    assert devices[2].payload["holder_name"] == "Петров Пётр"
    for m in contract.messages(TENANT, "jamf", batch):
        fits(m)


def test_intune_reports_company_devices_and_follows_pages():
    def devices(r):
        assert r.headers["Authorization"] == "Bearer g"
        if "skiptoken" in str(r.url):
            return {"value": [
                {"id": "c", "manufacturer": "Dell", "model": "OptiPlex 7010", "serialNumber": "D3",
                 "operatingSystem": "Windows", "managedDeviceOwnerType": "company"},
                {"id": "d", "manufacturer": "Apple", "model": "iPad Pro", "serialNumber": "D4",
                 "operatingSystem": "iOS", "managedDeviceOwnerType": "company"}]}
        assert "serialNumber" in parse_qs(r.url.query.decode())["$select"][0]
        return {"value": [
            {"id": "a", "manufacturer": "Lenovo", "model": "ThinkPad T14 Gen 4", "serialNumber": "PF4A",
             "operatingSystem": "Windows", "emailAddress": "Ivanov@corp.ru", "userDisplayName": "Иванов Иван",
             "managedDeviceOwnerType": "company"},
            {"id": "b", "manufacturer": "Samsung", "model": "Galaxy S24", "serialNumber": "R5",
             "operatingSystem": "Android", "userPrincipalName": "petrov@corp.ru", "managedDeviceOwnerType": "personal"},
        ], "@odata.nextLink": "https://graph.microsoft.com/v1.0/deviceManagement/managedDevices?$skiptoken=2"}

    http = client({"/dir/oauth2/v2.0/token": {"access_token": "g", "expires_in": 3600},
                   "/v1.0/deviceManagement/managedDevices": devices})
    batch = intune.Intune(http, "dir", "app", "secret").pull(TENANT, None)

    found = [(d.payload["serial_number"], d.payload["type"], d.payload["holder_email"])
             for d in of(batch, normalized.EntityType.ASSET)]
    assert found == [("PF4A", "LAPTOP", "ivanov@corp.ru"), ("D3", "OTHER", None), ("D4", "OTHER", None)]
    assert str(http.seen[0].url) == "https://login.microsoftonline.com/dir/oauth2/v2.0/token"
    for m in contract.messages(TENANT, "intune", batch):
        fits(m)


@pytest.mark.parametrize(("system", "model", "kind"), [
    ("iOS", "Apple iPhone 15", "PHONE"), ("Android", "Samsung Galaxy Tab S9", "OTHER"),
    ("Windows", "HP EliteBook 840 G10", "LAPTOP"), ("macOS", "Apple MacBook Air", "LAPTOP"),
    ("Windows", "Dell OptiPlex 7010", "OTHER"), (None, None, "OTHER")])
def test_intune_kinds(system, model, kind):
    assert intune.kind_of(system, model) == kind


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
