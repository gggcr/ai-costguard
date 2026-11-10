import io
import json
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal

import fastavro
import pytest
from openpyxl import Workbook

from costguard_common import schemas
from costguard_ingest import mapping, pipeline, service, storage, tables, values

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
FILE = uuid.UUID("22222222-2222-2222-2222-222222222222")
NOW = datetime(2026, 11, 7, 12, 0, tzinfo=UTC)

CSV = (
    "Поставщик;Услуга;ФИО;E-mail;Сумма;Валюта;Дата начала;Дата окончания;Последний вход\n"
    "Яндекс;Яндекс 360;Иванов И.И.;Ivanov@corp.ru;1 500,50;RUB;01.10.2026;31.10.2026;15.09.2026\n"
    "Яндекс;Яндекс 360;Петров П.П.;petrov@corp.ru;1 5OO;руб;01.10.2026;31.10.2026;\n"
    "Яндекс;Трекер;;;abc;;01.10.2026;31.10.2026;\n"
    "\n"
    "Яндекс;Трекер;;;300;;01.11.2026;01.10.2026;\n"
).encode("cp1251")


def roundtrip(topic: str, record: dict) -> dict:
    """Encodes with the contract schema and reads it back: proves the message fits the topic."""
    schema = schemas.parsed(topic)
    out = io.BytesIO()
    fastavro.schemaless_writer(out, schema, record)
    return fastavro.schemaless_reader(io.BytesIO(out.getvalue()), schema)


# values


@pytest.mark.parametrize("raw, expected, sure", [
    ("1 500,50", Decimal("1500.50"), True),
    ("1.234,56", Decimal("1234.56"), True),
    ("1,234.56", Decimal("1234.56"), True),
    ("2 000 ₽", Decimal("2000"), True),
    ("(100)", Decimal("-100"), True),
    (42, Decimal("42"), True),
    ("1 5OO", Decimal("1500"), False),
    ("1.000.000", Decimal("1000000"), False),
])
def test_amounts(raw, expected, sure):
    value, confidence = values.amount(raw)
    assert value == expected
    assert (confidence >= values.CONFIDENT) is sure


@pytest.mark.parametrize("raw", ["abc", "", None, True, "--"])
def test_not_amounts(raw):
    assert values.amount(raw)[0] is None


def test_dates():
    assert values.day("31.10.2026") == (date(2026, 10, 31), 1.0)
    assert values.day("2026-10-31T10:00:00") == (date(2026, 10, 31), 1.0)
    assert values.day(datetime(2026, 1, 2, 3, 4)) == (date(2026, 1, 2), 1.0)
    assert values.day("25/10/2026") == (date(2026, 10, 25), 1.0)
    ambiguous, confidence = values.day("05/10/2026")
    assert ambiguous == date(2026, 10, 5) and confidence < values.CONFIDENT
    assert values.day("вчера") == (None, 0.0)


def test_currencies():
    assert values.currency("usd") == ("USD", 1.0)
    assert values.currency("руб.")[0] == "RUB"
    assert values.currency("доллары") == (None, 0.0)
    assert values.currency_in("1 500 ₽") == "RUB"
    assert values.currency_in(1500) is None


# mapping


def test_mapping_russian_and_english():
    columns = ["Контрагент", "Наименование", "ФИО", "Почта", "Сумма, руб", "Дата окончания", "Дата начала",
               "Last login", "Отдел", "Комментарий"]
    assert mapping.suggest(columns) == {
        "Контрагент": "vendor", "Наименование": "product", "ФИО": "employee", "Почта": "email",
        "Сумма, руб": "amount", "Дата окончания": "period_end", "Дата начала": "period_start",
        "Last login": "last_active", "Отдел": "department", "Комментарий": "skip",
    }


def test_mapping_gives_each_field_once():
    assert mapping.suggest(["Сумма", "Итого"]) == {"Сумма": "amount", "Итого": "skip"}


def test_mapping_fields_match_the_core():
    assert set(mapping.FIELDS) == {"vendor", "product", "employee", "email", "amount", "currency", "period_start",
                                   "period_end", "last_active", "department", "skip"}


# tables


def test_csv_in_cp1251_with_semicolons():
    table = tables.read("счёт.CSV", CSV)
    assert table.columns[0] == "Поставщик"
    assert [n for n, _ in table.rows] == [2, 3, 4, 6]


def test_xlsx_skips_leading_blank_rows_and_names_empty_headers():
    book = Workbook()
    sheet = book.active
    sheet.append([None, None])
    sheet.append(["Vendor", None, "Amount"])
    sheet.append(["Acme", "x", 10.5])
    buffer = io.BytesIO()
    book.save(buffer)
    table = tables.read("export.xlsx", buffer.getvalue())
    assert table.columns == ["Vendor", "column 2", "Amount"]
    assert table.rows == [(3, {"Vendor": "Acme", "column 2": "x", "Amount": 10.5})]


@pytest.mark.parametrize("name", ["old.xls", "photo.jpg", "data.json"])
def test_unsupported(name):
    with pytest.raises(tables.Unsupported):
        tables.read(name, b"whatever")


def test_broken_xlsx():
    with pytest.raises(tables.Unsupported):
        tables.read("broken.xlsx", b"not a zip")


# parse


def test_parse_totals_and_flags():
    parsed = pipeline.parse("invoice.csv", CSV)
    assert parsed.rows == 4
    assert parsed.total == Decimal("1500.50") + Decimal("1500") + Decimal("300")
    assert parsed.errors == [(4, "Amount 'abc' is not a number")]
    assert (3, "amount", "1 5OO", 0.4) in parsed.uncertain
    assert not any(f == "currency" for _, f, _, _ in parsed.uncertain)  # "руб" is clear enough


def test_parse_without_an_amount_column():
    parsed = pipeline.parse("people.csv", "ФИО;Отдел\nИванов;ИТ\n".encode())
    assert parsed.errors[0][0] == 1
    assert parsed.total == 0


# accept


def accept(corrections=None):
    columns = pipeline.parse("invoice.csv", CSV).suggested_mapping
    return pipeline.accept(TENANT, FILE, f"{TENANT}/abc.csv", "invoice.csv", None, columns, corrections or {}, CSV,
                           now=NOW)


def test_accept_writes_cost_records_and_skips_bad_rows():
    messages, skipped = accept()
    costs = [m.value for m in messages if m.topic == "cost.records.v1"]
    assert [c["billed_cost"] for c in costs] == [Decimal("1500.500000"), Decimal("1500.000000")]
    assert all(c["billing_currency"] == "RUB" for c in costs)
    assert costs[0]["charge_period_start"] == date(2026, 10, 1)
    assert costs[0]["service_provider_name"] == "Яндекс"
    assert skipped == [(4, "amount is not a number"), (6, "period ends before it starts")]


def test_accept_applies_corrections():
    messages, skipped = accept({"4": {"amount": "250"}})
    costs = [m.value for m in messages if m.topic == "cost.records.v1"]
    assert Decimal("250.000000") in [c["billed_cost"] for c in costs]
    assert skipped == [(6, "period ends before it starts")]


def test_accept_is_idempotent():
    first, _ = accept()
    second, _ = accept()
    assert [(m.topic, m.key, m.value.get("id")) for m in first] == [(m.topic, m.key, m.value.get("id"))
                                                                   for m in second]


def test_accept_normalizes_entities_once():
    messages, _ = accept()
    kinds = [m.value["entity_type"] for m in messages if m.topic == "ingest.normalized.v1"]
    assert kinds.count("VENDOR") == 1
    assert kinds.count("SUBSCRIPTION") == 2
    assert kinds.count("EMPLOYEE") == 2
    assert kinds.count("LICENSE_ASSIGNMENT") == 2
    assert kinds.count("INVOICE") == 1
    employee = next(m for m in messages if m.value.get("entity_type") == "EMPLOYEE")
    assert '"email": "ivanov@corp.ru"' in employee.value["payload"]


def invoice_of(messages):
    [entity] = [m.value for m in messages if m.topic == "ingest.normalized.v1" and m.value["entity_type"] == "INVOICE"]
    return json.loads(entity["payload"])


def test_the_invoice_carries_its_day_and_currency_for_the_audit():
    """The audit finds an invoice that came twice by its number, or by its total on the same day (ADR 0032)."""
    invoice = invoice_of(accept()[0])
    assert invoice["total"] == "3000.50"
    assert invoice["date"] == "2026-10-01"  # no invoice date: the earliest period
    assert invoice["currency"] == "RUB"
    assert invoice["number"] is None


def test_an_extracted_invoice_keeps_its_number_and_date():
    content = ("Поставщик;Номер счёта;Дата счёта;Наименование;Сумма\n"
               "ООО Атлассиан;A-17;05.11.2026;Jira, 3 места;3000\n"
               "ООО Атлассиан;A-17;05.11.2026;Confluence;1000\n").encode("cp1251")
    columns = pipeline.parse("a17.csv", content).suggested_mapping
    messages, _ = pipeline.accept(TENANT, FILE, f"{TENANT}/a17.csv", "a17.csv", None, columns, {}, content, now=NOW)
    invoice = invoice_of(messages)
    assert (invoice["number"], invoice["date"], invoice["total"]) == ("A-17", "2026-11-05", "4000")


def test_every_output_fits_its_schema():
    messages, _ = accept()
    for m in messages:
        assert roundtrip(m.topic, m.value)


# service


class MemoryFiles:
    def __init__(self, content: dict[str, bytes]):
        self._content = content

    def get(self, key: str) -> bytes:
        if key not in self._content:
            raise FileNotFoundError(key)
        return self._content[key]


def file_event(key: str, name: str) -> dict:
    return {"file_id": str(FILE), "tenant_id": str(TENANT), "s3_key": key, "sha256": "abc", "file_name": name,
            "content_type": "text/csv", "source_template_id": None, "uploaded_at": NOW}


def test_handler_reports_a_parsed_file():
    handler = service.Handler(MemoryFiles({"k": CSV}))
    [result] = handler.on_file(file_event("k", "invoice.csv"))
    assert result.topic == "ingest.results.v1" and result.key == str(FILE)
    decoded = roundtrip(result.topic, result.value)
    assert decoded["status"] == "PARSED"
    assert decoded["rows"] == 4
    assert decoded["total"] == Decimal("3300.500000")
    assert decoded["errors"] == [{"row": 4, "message": "Amount 'abc' is not a number"}]


@pytest.mark.parametrize("files, name, reason", [
    ({}, "invoice.csv", "The file is not in storage"),
    ({"k": b"\x89PNG"}, "scan.png", "OCR"),
])
def test_handler_reports_failures(files, name, reason):
    [result] = service.Handler(MemoryFiles(files)).on_file(file_event("k", name))
    decoded = roundtrip(result.topic, result.value)
    assert decoded["status"] == "FAILED"
    assert reason in decoded["failure"]


def test_handler_accepts():
    handler = service.Handler(MemoryFiles({"k": CSV}))
    out = handler.on_accepted({
        "file_id": str(FILE), "tenant_id": str(TENANT), "s3_key": "k", "file_name": "invoice.csv",
        "vendor_id": str(uuid.uuid4()), "mapping": pipeline.parse("invoice.csv", CSV).suggested_mapping,
        "corrections": {}, "accepted_at": NOW})
    assert {m.topic for m in out} == {"cost.records.v1", "ingest.normalized.v1"}


# storage


def test_local_files_stay_inside_their_directory(tmp_path):
    (tmp_path / "t").mkdir()
    (tmp_path / "t" / "a.csv").write_bytes(b"x")
    files = storage.LocalFiles(tmp_path)
    assert files.get("t/a.csv") == b"x"
    with pytest.raises(ValueError):
        files.get("../outside.csv")


# bank statements

STATEMENT = """1CClientBankExchange
ВерсияФормата=1.03
Кодировка=Windows
РасчСчет=40702810000000000001
СекцияДокумент=Платежное поручение
Номер=15
Дата=05.10.2026
Сумма=12000.00
ПлательщикСчет=40702810000000000001
Получатель1=ООО "Битрикс"
ПолучательИНН=7700000001
НазначениеПлатежа=Оплата Битрикс24 за октябрь
КонецДокумента
СекцияДокумент=Платежное поручение
Номер=16
Дата=06.10.2026
Сумма=5000.00
ПлательщикСчет=40702810999999999999
Получатель1=ООО "Наша компания"
НазначениеПлатежа=Оплата от клиента
КонецДокумента
КонецФайла
""".encode("cp1251")


def test_statement_keeps_only_outgoing_payments():
    table = tables.read("выписка.txt", STATEMENT)
    assert table.kind == "statement"
    assert len(table.rows) == 1
    assert mapping.suggest(table.columns)["Получатель"] == "vendor"
    assert mapping.suggest(table.columns)["Назначение платежа"] == "product"


def test_statement_becomes_payments():
    columns = pipeline.parse("выписка.txt", STATEMENT).suggested_mapping
    messages, skipped = pipeline.accept(TENANT, FILE, "k", "выписка.txt", None, columns, {}, STATEMENT, now=NOW)
    assert skipped == []
    [cost] = [m.value for m in messages if m.topic == "cost.records.v1"]
    assert cost["source"] == "PAYMENT"
    assert cost["billed_cost"] == Decimal("12000.000000")
    assert cost["charge_period_start"] == date(2026, 10, 5)
    kinds = {m.value["entity_type"] for m in messages if m.topic == "ingest.normalized.v1"}
    assert kinds == {"VENDOR", "PAYMENT"}
    payment = next(m for m in messages if m.value.get("entity_type") == "PAYMENT")
    assert "7700000001" in payment.value["payload"]
    for m in messages:
        assert roundtrip(m.topic, m.value)


# PDF


def pdf_with_table(rows: list[list[str]]) -> bytes:
    """A one-page PDF with a ruled table, written by hand so the test needs no PDF library."""
    ops, width, height, top = [], 120, 20, 700
    for r, row in enumerate(rows):
        for c, cell in enumerate(row):
            x, y = 50 + c * width, top - r * height
            ops.append(f"{x} {y} {width} {height} re S")
            ops.append(f"BT /F1 10 Tf {x + 4} {y + 6} Td ({cell}) Tj ET")
    stream = "\n".join(ops).encode()
    objects = [
        b"<< /Type /Catalog /Pages 2 0 R >>",
        b"<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        b"<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Contents 4 0 R "
        b"/Resources << /Font << /F1 5 0 R >> >> >>",
        b"<< /Length " + str(len(stream)).encode() + b" >>\nstream\n" + stream + b"\nendstream",
        b"<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    ]
    out, offsets = io.BytesIO(), []
    out.write(b"%PDF-1.4\n")
    for i, body in enumerate(objects, start=1):
        offsets.append(out.tell())
        out.write(f"{i} 0 obj\n".encode() + body + b"\nendobj\n")
    xref = out.tell()
    out.write(f"xref\n0 {len(objects) + 1}\n0000000000 65535 f \n".encode())
    for o in offsets:
        out.write(f"{o:010d} 00000 n \n".encode())
    out.write(f"trailer\n<< /Size {len(objects) + 1} /Root 1 0 R >>\nstartxref\n{xref}\n%%EOF\n".encode())
    return out.getvalue()


def test_pdf_table():
    content = pdf_with_table([["Vendor", "Service", "Amount"], ["Acme", "CRM", "1500.00"], ["Acme", "Mail", "300"]])
    parsed = pipeline.parse("invoice.pdf", content)
    assert parsed.columns == ["Vendor", "Service", "Amount"]
    assert parsed.rows == 2
    assert parsed.total == Decimal("1800.00")


def test_pdf_without_text_is_a_scan():
    with pytest.raises(tables.Unsupported, match="scan"):
        tables.read("scan.pdf", pdf_with_table([]))


def test_broken_pdf():
    with pytest.raises(tables.Unsupported):
        tables.read("broken.pdf", b"%PDF-1.4 garbage")


def test_an_accepted_file_that_cannot_be_read_writes_nothing_instead_of_failing(tmp_path):
    (tmp_path / "t").mkdir()
    (tmp_path / "t" / "a.bin").write_bytes(b"\x00\x01")
    handler = service.Handler(storage.LocalFiles(tmp_path))
    accepted = {"tenant_id": str(uuid.uuid4()), "file_id": str(uuid.uuid4()), "s3_key": "t/a.bin",
                "file_name": "a.bin", "vendor_id": None, "mapping": {}, "corrections": {}}
    assert handler.on_accepted(accepted) == []
    assert handler.on_accepted({**accepted, "s3_key": "t/missing.csv", "file_name": "missing.csv"}) == []
