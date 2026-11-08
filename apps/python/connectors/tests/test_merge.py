"""The Merge unified API: suppliers, payable invoices, payments, employees and dismissals."""

import io
import json
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal
from urllib.parse import parse_qs

import fastavro
import httpx

from costguard_common import normalized, schemas
from costguard_connectors import contract, merge

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
NOW = datetime(2026, 11, 8, 6, 0, tzinfo=UTC)

CONTACTS = {"results": [
    {"id": "c1", "name": "Pinewood Analytics Inc", "tax_number": None, "is_supplier": True},
    {"id": "c2", "name": "ООО «Тучка Софт»", "tax_number": "7700000002", "is_supplier": True}], "next": None}
INVOICES = [
    {"results": [{"id": "i1", "type": "ACCOUNTS_PAYABLE", "number": "INV-881", "issue_date": "2026-10-03T00:00:00Z",
                  "contact": "c1", "total_amount": 490.0, "currency": "USD", "status": "OPEN"},
                 {"id": "i2", "type": "ACCOUNTS_PAYABLE", "number": "D-1", "issue_date": "2026-10-04T00:00:00Z",
                  "contact": "c1", "total_amount": 10.0, "currency": "USD", "status": "DRAFT"}],
     "next": "page2"},
    {"results": [{"id": "i3", "type": "ACCOUNTS_PAYABLE", "number": "77", "issue_date": "2026-11-02",
                  "contact": {"id": "c2"}, "total_amount": "12000.00", "currency": None, "status": "PAID"},
                 {"id": "i4", "contact": "c2", "total_amount": 5, "issue_date": "2026-11-02",
                  "remote_was_deleted": True}],
     "next": None}]
PAYMENTS = {"results": [{"id": "p1", "contact": "c2", "total_amount": 12000, "currency": "RUB",
                         "transaction_date": "2026-11-05T00:00:00Z", "reference": "Оплата счёта 77"}], "next": None}
EMPLOYEES = {"results": [
    {"id": "e1", "first_name": "Анна", "last_name": "Иванова", "work_email": "Ivanova@romashka.example",
     "employment_status": "ACTIVE"},
    {"id": "e2", "display_full_name": "Петров Пётр", "work_email": "petrov@romashka.example",
     "employment_status": "INACTIVE", "termination_date": "2026-10-31T00:00:00Z"},
    {"id": "e3", "display_full_name": "Новикова Ольга", "personal_email": "novikova@mail.example",
     "employment_status": "PENDING"}], "next": None}


def gateway(seen):
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        path = request.url.path
        if path.endswith("/accounting/v1/contacts"):
            return httpx.Response(200, json=CONTACTS)
        if path.endswith("/accounting/v1/invoices"):
            return httpx.Response(200, json=INVOICES[1 if request.url.params.get("cursor") == "page2" else 0])
        if path.endswith("/accounting/v1/payments"):
            return httpx.Response(200, json=PAYMENTS)
        if path.endswith("/hris/v1/employees"):
            return httpx.Response(200, json=EMPLOYEES)
        return httpx.Response(404)

    return httpx.Client(base_url=merge.BASE_URL, headers={"Authorization": "Bearer merge-key"},
                        transport=httpx.MockTransport(handle))


def of(batch, kind):
    return [e for e in batch.entities if e.type == kind]


def test_accounting_and_hr_through_one_contract():
    seen = []
    batch = merge.Merge(gateway(seen), "acc-token", "hris-token", now=lambda: NOW).pull(TENANT, None)

    assert [v.payload["inn"] for v in of(batch, normalized.EntityType.VENDOR)] == [None, "7700000002"]
    invoices = of(batch, normalized.EntityType.INVOICE)
    assert [(i.payload["number"], i.payload["currency"]) for i in invoices] == [("INV-881", "USD"), ("77", "RUB")]
    assert [(c.billed_cost, c.billing_currency, c.charge_period_start) for c in batch.costs] == [
        (Decimal("490.0"), "USD", date(2026, 10, 3)), (Decimal("12000.00"), "RUB", date(2026, 11, 2))]
    [payment] = of(batch, normalized.EntityType.PAYMENT)
    assert payment.payload["vendor"] == "ООО «Тучка Софт»" and payment.payload["inn"] == "7700000002"
    assert len(batch.costs) == 2  # payments are not counted twice

    people = {p.payload["email"]: p.payload for p in of(batch, normalized.EntityType.EMPLOYEE)}
    assert people["ivanova@romashka.example"]["active"] is True
    assert people["petrov@romashka.example"]["dismissed"] is True
    assert people["novikova@mail.example"]["dismissed"] is False

    tokens = {r.url.path.split("/")[2]: r.headers["x-account-token"] for r in seen}
    assert tokens == {"accounting": "acc-token", "hris": "hris-token"}
    assert all(r.headers["authorization"] == "Bearer merge-key" for r in seen)
    assert json.loads(batch.cursor) == {"accounting": NOW.isoformat(), "hris": NOW.isoformat()}
    for m in contract.messages(TENANT, "merge", batch, now=NOW):
        out = io.BytesIO()
        fastavro.schemaless_writer(out, schemas.parsed(m.topic), m.value)


def test_the_next_pull_asks_for_changes_only_and_keeps_vendors_whole():
    seen = []
    cursor = json.dumps({"accounting": "2026-11-01T00:00:00+00:00", "hris": "2026-11-01T00:00:00+00:00"})
    merge.Merge(gateway(seen), "acc-token", "hris-token", now=lambda: NOW).pull(TENANT, cursor)
    queries = {r.url.path.rsplit("/", 1)[-1]: parse_qs(r.url.query.decode()) for r in seen}
    assert "modified_after" not in queries["contacts"]
    for name in ("invoices", "payments", "employees"):
        assert queries[name]["modified_after"] == ["2026-11-01T00:00:00+00:00"]


def test_a_company_with_only_an_hr_system():
    seen = []
    batch = merge.Merge(gateway(seen), None, "hris-token", now=lambda: NOW).pull(TENANT, None)
    assert all("/hris/" in r.url.path for r in seen)
    assert of(batch, normalized.EntityType.VENDOR) == [] and json.loads(batch.cursor) == {"hris": NOW.isoformat()}
