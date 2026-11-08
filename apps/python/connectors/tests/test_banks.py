"""The bank connector: accounts, card (МИР too), SBP and transfers out, and payments through intermediaries."""

import io
import json
import uuid
from datetime import date
from decimal import Decimal

import fastavro
import httpx
import pytest

from costguard_common import normalized, payments, schemas
from costguard_connectors import banks, contract, tbank

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
AGENT = payments.Intermediary("ООО «Мост Платежей»", "7700000009")

OPERATIONS = [
    {"operationId": "op-1", "operationDate": "2026-11-02T10:00:00Z", "typeOfOperation": "Debit",
     "category": "contragentOutcome", "operationAmount": 12000, "operationCurrencyDigitalCode": "643",
     "payPurpose": "Оплата по счету 77 за ТучкаCRM. НДС не облагается",
     "counterParty": {"name": "ООО «Тучка Софт»", "inn": "7700000002"}},
    {"operationId": "op-2", "operationDate": "2026-11-03T09:00:00Z", "typeOfOperation": "Debit",
     "category": "cardOperation", "operationAmount": -1290.5, "operationCurrencyDigitalCode": "643",
     "description": "Оплата в OBLAKO PLUS", "cardNumber": "220070******1234", "mcc": 4816,
     "merchant": {"name": "OBLAKO PLUS", "city": "Moscow"}},
    {"operationId": "op-3", "operationDate": "2026-11-03T12:00:00Z", "typeOfOperation": "Debit",
     "category": "sbpOutcome", "operationAmount": 700, "operationCurrencyDigitalCode": "643",
     "description": "Перевод по СБП", "payPurpose": "Оплата курьерской доставки",
     "counterParty": {"name": "ИП Василькова Е.В."}},
    {"operationId": "op-4", "operationDate": "2026-11-04T08:00:00Z", "typeOfOperation": "Debit",
     "category": "contragentOutcome", "operationAmount": 5350, "operationCurrencyDigitalCode": "643",
     "payPurpose": "Оплата подписки Notion Team по агентскому договору 15-А, в т.ч. вознаграждение агента 350,00 руб.",
     "counterParty": {"name": "ООО «Мост Платежей»", "inn": "7700000009"}},
    {"operationId": "op-5", "operationDate": "2026-11-04T09:00:00Z", "typeOfOperation": "Credit",
     "category": "income", "operationAmount": 50000, "operationCurrencyDigitalCode": "643",
     "payPurpose": "Оплата от покупателя", "counterParty": {"name": "ООО Ландыш"}},
    {"operationId": "op-6", "operationDate": "2026-11-05T09:00:00Z", "typeOfOperation": "Debit",
     "category": "selfTransferOuter", "operationAmount": 100000, "operationCurrencyDigitalCode": "643",
     "payPurpose": "Перевод собственных средств"},
    {"operationId": "op-7", "operationDate": "2026-11-05T11:00:00Z", "typeOfOperation": "Debit",
     "category": "fee", "operationAmount": 99, "operationCurrencyDigitalCode": "643",
     "description": "Комиссия за обслуживание счета", "counterParty": {"name": "Банк"}},
]


def bank_api(seen):
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        if request.url.path.endswith("/api/v4/bank-accounts"):
            return httpx.Response(200, json=[{"accountNumber": "40702810000000000001", "currency": "643",
                                              "name": "Основной"},
                                             {"accountNumber": "40702840000000000002", "currency": "840"}])
        if request.url.path.endswith("/api/v1/statement"):
            if request.url.params["accountNumber"] != "40702810000000000001":
                return httpx.Response(200, json={"operations": []})
            if request.url.params.get("cursor") == "c2":
                return httpx.Response(200, json={"operations": OPERATIONS[4:]})
            return httpx.Response(200, json={"operations": OPERATIONS[:4], "nextCursor": "c2"})
        return httpx.Response(404)

    return httpx.Client(base_url=tbank.BASE_URL, headers={"Authorization": "Bearer t-token"},
                        transport=httpx.MockTransport(handle))


def pull(seen, cursor=None, **kw):
    connector = banks.BankConnector(tbank.TBank(bank_api(seen), **kw), [AGENT], today=lambda: date(2026, 11, 8))
    return connector.pull(TENANT, cursor)


def paid(batch):
    return [(e.payload["vendor"], e.payload["amount"], e.payload["kind"], e.payload["role"])
            for e in batch.entities if e.type == normalized.EntityType.PAYMENT]


def test_outgoing_operations_become_payments_and_costs():
    seen = []
    batch = pull(seen)
    assert paid(batch) == [
        ("ООО «Тучка Софт»", "12000", "TRANSFER", "payment"),
        ("OBLAKO PLUS", "1290.5", "CARD", "payment"),
        ("ИП Василькова Е.В.", "700", "SBP", "payment"),
        ("Notion", "5000.00", "TRANSFER", "service"),
        ("ООО «Мост Платежей»", "350.00", "TRANSFER", "commission"),
        ("Банк", "99", "FEE", "payment"),
    ]
    assert sum(c.billed_cost for c in batch.costs) == Decimal("19439.50")
    card = next(e for e in batch.entities if e.payload.get("kind") == "CARD")
    assert card.payload["card_network"] == "MIR" and card.payload["mcc"] == "4816"
    assert all(r.headers["authorization"] == "Bearer t-token" for r in seen)
    for m in contract.messages(TENANT, "tbank", batch):
        out = io.BytesIO()
        fastavro.schemaless_writer(out, schemas.parsed(m.topic), m.value)


def test_the_service_behind_an_intermediary_is_a_vendor_and_the_commission_is_separate():
    batch = pull([])
    service = next(e for e in batch.entities if e.type == normalized.EntityType.PAYMENT
                   and e.payload["role"] == "service")
    assert service.payload["intermediary"] == "ООО «Мост Платежей»" and service.payload["inn"] is None
    vendors = {e.payload["name"]: e.key for e in batch.entities if e.type == normalized.EntityType.VENDOR}
    assert vendors["Notion"] == normalized.vendor_key(TENANT, "Notion")
    assert vendors["ООО «Мост Платежей»"].endswith("inn:7700000009")
    costs = {c.source_id: (c.service_provider_name, c.billed_cost) for c in batch.costs}
    assert costs["tbank:op-4"] == ("Notion", Decimal("5000.00"))
    assert costs["tbank:op-4:commission"] == ("ООО «Мост Платежей»", Decimal("350.00"))


def test_pages_cursor_and_lookback():
    seen = []
    batch = pull(seen)
    statements = [r for r in seen if r.url.path.endswith("/statement")]
    assert statements[0].url.params["from"] == "2026-08-10T00:00:00Z"
    assert any(r.url.params.get("cursor") == "c2" for r in statements)
    assert json.loads(batch.cursor) == {"40702810000000000001": "2026-11-05", "40702840000000000002": "2026-08-10"}

    again = []
    pull(again, batch.cursor)
    first = next(r for r in again if r.url.path.endswith("/statement"))
    assert first.url.params["from"] == "2026-11-05T00:00:00Z"


def test_only_the_accounts_asked_for():
    seen = []
    pull(seen, accounts=["40702840000000000002"])
    numbers = {r.url.params["accountNumber"] for r in seen if r.url.path.endswith("/statement")}
    assert numbers == {"40702840000000000002"}


@pytest.mark.parametrize("card,network", [("2200 7012 3456 7890", "MIR"), ("220400******0001", "MIR"),
                                          ("427600******1111", "VISA"), ("546900******2222", "MASTERCARD"),
                                          ("222100******3333", "MASTERCARD"), ("6250 94** **** 4444", "UNIONPAY"),
                                          (None, None), ("12", None)])
def test_card_networks(card, network):
    assert banks.card_network(card) == network


@pytest.mark.parametrize("op,kind", [
    ({"category": "cardOperation"}, banks.Kind.CARD), ({"merchant": {"name": "X"}}, banks.Kind.CARD),
    ({"category": "sbpOutcome"}, banks.Kind.SBP), ({"description": "Перевод по СБП"}, banks.Kind.SBP),
    ({"category": "fee"}, banks.Kind.FEE), ({"category": "selfTransferInner"}, banks.Kind.OWN),
    ({"category": "contragentOutcome", "counterParty": {}}, banks.Kind.TRANSFER), ({}, banks.Kind.OTHER)])
def test_kinds(op, kind):
    assert tbank.kind_of(op) == kind
