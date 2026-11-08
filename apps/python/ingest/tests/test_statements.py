"""MT940, CAMT.053 and OFX give the same table and the same records as a 1C statement."""

import uuid
from datetime import UTC, date, datetime
from decimal import Decimal
from pathlib import Path

import pytest

from costguard_ingest import camt053, mapping, mt940, ofx, pipeline, statements, tables

FIXTURES = Path(__file__).parent / "fixtures"
TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
FILE = uuid.UUID("22222222-2222-2222-2222-222222222222")
NOW = datetime(2026, 11, 7, 12, 0, tzinfo=UTC)


def fixture(name: str) -> bytes:
    return (FIXTURES / name).read_bytes()


@pytest.mark.parametrize("name,kind", [
    ("statement.mt940", "mt940"), ("statement.camt053.xml", "camt053"), ("statement.ofx", "ofx"),
    ("statement-v2.ofx", "ofx"),
])
def test_the_format_is_found_by_content(name, kind):
    assert statements.kind(fixture(name)) == kind


def test_other_files_are_not_statements():
    assert statements.kind(b"Vendor;Amount\nAcme;100\n") is None
    assert statements.kind(b"<?xml version='1.0'?><Invoice/>") is None


def test_mt940_debits_with_three_kinds_of_details():
    rows = [p.line() for p in mt940.payments(fixture("statement.mt940"))]
    assert rows == [
        ["ООО Тучка Софт", "Оплата по счету 77 за ТучкаCRM, ноябрь 2026. Получатель: ООО Тучка Софт; ИНН 7700000002",
         "12000.00", "02.11.2026", "1045", "7700000002", "RUB"],
        ["ООО Облако Плюс", "Хостинг за ноябрьдоговор 12-Х", "3500.50", "04.11.2026", "1046", "", "RUB"],
        ["ИП Василькова Е.В.", "Ремонт принтера", "990.00", "05.11.2026", "", "770000000004", "RUB"],
    ]


def test_mt940_without_block_wrapper_and_continuation_lines():
    content = (b":20:X\n:25:40702810000000000001\n:60F:C261031USD10,00\n:61:261102D5,NTRFREF7\n"
               b":86:Payee: Acme Tools\nLicense renewal\n:62F:C261102USD5,00\n")
    [p] = mt940.payments(content)
    assert (p.payee, p.amount, p.currency, p.number, p.day) == ("Acme Tools", "5", "USD", "REF7", "02.11.2026")
    assert "License renewal" in p.purpose


def test_camt053_booked_debits_and_batch_bookings():
    rows = [p.line() for p in camt053.payments(fixture("statement.camt053.xml"))]
    assert rows == [
        ['ООО "Тучка Софт"', "Оплата по счету 77 за ТучкаCRM, ноябрь 2026", "12000.00", "02.11.2026", "1045",
         "7700000002", "RUB"],
        ["Cloudlet Hosting GmbH", "Server rent November", "200.00", "04.11.2026", "B-1", "", "EUR"],
        ["Mailbird Ltd", "Newsletter plan", "100.00", "04.11.2026", "B-2", "", "EUR"],
    ]


def test_camt053_with_a_doctype_is_refused():
    evil = b'<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaa">]><Document><BkToCstmrStmt/></Document>'
    with pytest.raises(ValueError, match="DOCTYPE"):
        camt053.payments(evil)


def test_ofx_sgml_and_xml():
    assert [p.line() for p in ofx.payments(fixture("statement.ofx"))] == [
        ["ООО Тучка Софт", "Оплата по счету 77 за ТучкаCRM", "12000.00", "02.11.2026", "1045", "", "RUB"],
        ["ООО Облако Плюс", "Хостинг за ноябрь", "3500.50", "04.11.2026", "T1047", "", "RUB"],
    ]
    assert [p.line() for p in ofx.payments(fixture("statement-v2.ofx"))] == [
        ["Pinewood Analytics Inc", "Team plan, November", "49.00", "02.11.2026", "X1", "", "USD"],
        ["Quillpad Ltd", "Notes app", "20.00", "03.11.2026", "X2", "", "EUR"],
    ]


@pytest.mark.parametrize("name", ["statement.mt940", "statement.camt053.xml", "statement.ofx", "statement-v2.ofx"])
def test_every_format_maps_like_a_1c_statement(name):
    table = tables.read(name, fixture(name))
    assert table.kind == "statement"
    assert table.columns == statements.COLUMNS
    suggested = mapping.suggest(table.columns)
    assert suggested["Получатель"] == "vendor" and suggested["Назначение платежа"] == "product"
    assert suggested["Сумма"] == "amount" and suggested["Валюта"] == "currency"


def test_a_camt053_statement_becomes_payment_records_in_its_currency():
    content = fixture("statement.camt053.xml")
    columns = pipeline.parse("statement.xml", content).suggested_mapping
    messages, skipped = pipeline.accept(TENANT, FILE, "k", "statement.xml", None, columns, {}, content, now=NOW)
    assert skipped == []
    costs = [m.value for m in messages if m.topic == "cost.records.v1"]
    assert [(c["source"], c["billed_cost"], c["billing_currency"], c["charge_period_start"]) for c in costs] == [
        ("PAYMENT", Decimal("12000.000000"), "RUB", date(2026, 11, 2)),
        ("PAYMENT", Decimal("200.000000"), "EUR", date(2026, 11, 4)),
        ("PAYMENT", Decimal("100.000000"), "EUR", date(2026, 11, 4)),
    ]
    payments = [m for m in messages if m.value.get("entity_type") == "PAYMENT"]
    assert len(payments) == 3 and "7700000002" in payments[0].value["payload"]


def test_a_1c_statement_still_reads_as_roubles():
    content = ("1CClientBankExchange\nРасчСчет=1\nСекцияДокумент=Платежное поручение\nНомер=1\nДата=05.10.2026\n"
               "Сумма=10.00\nПлательщикСчет=1\nПолучатель1=ООО Ландыш\nКонецДокумента\n").encode("cp1251")
    columns = pipeline.parse("1c.txt", content).suggested_mapping
    messages, _ = pipeline.accept(TENANT, FILE, "k", "1c.txt", None, columns, {}, content, now=NOW)
    [cost] = [m.value for m in messages if m.topic == "cost.records.v1"]
    assert cost["billing_currency"] == "RUB"


def test_a_statement_payment_through_an_intermediary_counts_as_the_service():
    from costguard_common import payments

    content = ("1CClientBankExchange\nРасчСчет=1\nСекцияДокумент=Платежное поручение\nНомер=9\nДата=04.11.2026\n"
               "Сумма=5350.00\nПлательщикСчет=1\nПолучатель1=ООО Мост Платежей\nПолучательИНН=7700000009\n"
               "НазначениеПлатежа=Оплата подписки Notion Team, в т.ч. вознаграждение агента 350,00 руб.\n"
               "КонецДокумента\n").encode("cp1251")
    columns = pipeline.parse("1c.txt", content).suggested_mapping
    messages, _ = pipeline.accept(TENANT, FILE, "k", "1c.txt", None, columns, {}, content, now=NOW,
                                  intermediaries=[payments.Intermediary("Мост Платежей", "7700000009")])
    costs = {m.value["source_id"]: (m.value["service_provider_name"], m.value["billed_cost"])
             for m in messages if m.topic == "cost.records.v1"}
    assert costs == {"k:2": ("Notion", Decimal("5000.000000")),
                     "k:2:commission": ("ООО Мост Платежей", Decimal("350.000000"))}
    paid = [m.value for m in messages if m.value.get("entity_type") == "PAYMENT"]
    assert len(paid) == 2 and '"intermediary": "ООО Мост Платежей"' in paid[0]["payload"]
    total = pipeline.parse("1c.txt", content).total
    assert total == Decimal("5350.00")
