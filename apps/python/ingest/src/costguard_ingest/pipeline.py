"""The two steps of an import, without Kafka or storage, so both can be tested on plain bytes.

1. parse: read the file, suggest the mapping, total the amounts, list broken rows and values worth a second look.
   The result goes to ingest.results.v1, and the core shows it to people.
2. accept: people confirmed the mapping and fixed the unsure values. Every row with an amount becomes a FOCUS cost
   record (cost.records.v1); vendors, subscriptions, employees and license assignments go to
   ingest.normalized.v1 for the core software and org modules. A bank statement gives PAYMENT records and
   PAYMENT entities instead of invoice lines.

Ids are derived from the stored file (tenant/sha256) and the row number, so a redelivered message writes the
same records again instead of new ones.
"""

from __future__ import annotations

import uuid
from collections.abc import Callable, Iterable
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal

from costguard_common import normalized
from costguard_common import payments as paying
from costguard_common.focus import CostRecord, CostSource
from costguard_ingest import mapping, tables, values

# The currency of an amount the file names none for, when the core did not say the company's (ADR 0072).
DEFAULT_CURRENCY = "RUB"
DATE_FIELDS = ("period_start", "period_end", "last_active")


@dataclass
class Parsed:
    columns: list[str]
    suggested_mapping: dict[str, str]
    rows: int
    total: Decimal
    errors: list[tuple[int, str]] = field(default_factory=list)
    uncertain: list[tuple[int, str, str, float]] = field(default_factory=list)


@dataclass(frozen=True)
class Message:
    topic: str
    key: str
    value: dict


def _by_field(row: dict[str, object], columns_to_fields: dict[str, str]) -> dict[str, object]:
    out: dict[str, object] = {}
    for column, f in columns_to_fields.items():
        if f != "skip" and column in row and f not in out:
            out[f] = row[column]
    return out


Read = Callable[[str, bytes], tables.Table]


def styles(table: tables.Table, columns_to_fields: dict[str, str],
           company: values.Style = values.UNKNOWN) -> dict[str, values.Style]:
    """How each field of the file is read (ADR 0072): as the values of its own column show, else as the company's
    country writes. One program writes all the dates of a file the same way, so its date columns settle the order
    together. A bank statement is written by a program, with a decimal dot and dd.mm.yyyy."""
    if table.kind == "statement":
        return {f: values.EXACT for f in ("amount", *DATE_FIELDS)}
    rows = [_by_field(row, columns_to_fields) for _, row in table.rows]
    found = {"amount": values.amounts_style((r.get("amount") for r in rows), company)}
    dates = values.dates_style((r.get(f) for r in rows for f in DATE_FIELDS), company)
    found.update(dict.fromkeys(DATE_FIELDS, dates))
    return found


def parse(file_name: str, content: bytes, read: Read = tables.read, style: values.Style = values.UNKNOWN,
          currency: str = DEFAULT_CURRENCY) -> Parsed:
    """read is tables.read, or documents.Reader where scans and text invoices are read too. style is how the
    company's country writes numbers and dates, currency its currency."""
    table = read(file_name, content)
    suggested = mapping.suggest(table.columns)
    result = Parsed(table.columns, suggested, len(table.rows), Decimal(0))
    equipment = bool({"inventory_number", "serial_number"} & set(suggested.values()))
    if "amount" not in suggested.values() and not equipment:  # a list of equipment may have no cost
        result.errors.append((1, "No column looks like an amount; choose it in the mapping"))
    read_as = styles(table, suggested, style)
    for number, row in table.rows:
        cells = _by_field(row, suggested)
        if "amount" in cells:
            raw = cells["amount"]
            value, confidence = values.amount(raw, read_as["amount"])
            if value is None:
                result.errors.append((number, f"Amount '{values.text(raw)}' is not a number"))
            else:
                result.total += value
                if confidence < values.CONFIDENT:
                    result.uncertain.append((number, "amount", values.text(raw), confidence))
        for f in DATE_FIELDS:
            if f not in cells or values.text(cells[f]) == "":
                continue
            parsed, confidence = values.day(cells[f], read_as[f])
            if parsed is None:
                result.errors.append((number, f"Date '{values.text(cells[f])}' in {f} is not a date"))
            elif confidence < values.CONFIDENT:
                result.uncertain.append((number, f, values.text(cells[f]), confidence))
        if "currency" in cells and values.text(cells["currency"]):
            code, confidence = values.currency(cells["currency"], currency)
            if code is None or confidence < values.CONFIDENT:
                result.uncertain.append((number, "currency", values.text(cells["currency"]), confidence))
    flagged = {(r, f) for r, f, _, _ in result.uncertain}
    for (number, column), confidence in sorted(table.review.items()):
        f = suggested.get(column, "skip")
        f = column if f == "skip" else f
        if (number, f) not in flagged:
            row = dict(table.rows).get(number, {})
            result.uncertain.append((number, f, values.text(row.get(column)), confidence))
    return result


_entity_key = normalized.key


def _normalized(tenant: uuid.UUID, file_id: uuid.UUID, kind: str, key: str, payload: dict, at: datetime,
                confidence: float = 1.0) -> Message:
    entity = normalized.Entity(normalized.EntityType(kind), key, payload, confidence)
    return Message(normalized.TOPIC, key, entity.message(tenant, file_id, at))


def accept(tenant: uuid.UUID, file_id: uuid.UUID, storage_key: str, file_name: str, vendor_id: uuid.UUID | None,
           columns_to_fields: dict[str, str], corrections: dict[str, dict[str, str]], content: bytes,
           now: datetime | None = None, read: Read = tables.read,
           intermediaries: Iterable[paying.Intermediary] = (), services: dict[str, str] | None = None,
           style: values.Style = values.UNKNOWN, currency: str = DEFAULT_CURRENCY
           ) -> tuple[list[Message], list[tuple[int, str]]]:
    """Messages to publish, and rows that still could not be written (they are logged, not retried).

    A payment through an intermediary is split into the service and the agent's commission (ADR 0015). style and
    currency are the company's, as in parse."""
    now = now or datetime.now(UTC)
    table = read(file_name, content)
    messages: list[Message] = []
    skipped: list[tuple[int, str]] = []
    seen: set[str] = set()

    def once(m: Message) -> None:
        if m.key not in seen:
            seen.add(m.key)
            messages.append(m)

    payments = table.kind == "statement"
    read_as = styles(table, columns_to_fields, style)

    def style_of_cell(f: str, fixed: dict[str, str]) -> values.Style:
        # what people typed in is written as they write, not as the program that made the statement
        return style if payments and f in fixed else read_as[f]

    source = CostSource.PAYMENT if payments else CostSource.INVOICE_LINE
    invoice_total = Decimal(0)
    invoice_vendor: str | None = None
    invoice_number = ""
    invoice_day: date | None = None
    invoice_currency: str | None = None
    invoice_lines: list[dict] = []
    # A list of equipment (board frame 15): with an inventory or serial number column every row is a device for the
    # assets module, held by the person of the row; its amount is what the device cost, not spending.
    equipment = not payments and bool({"inventory_number", "serial_number"} & set(columns_to_fields.values()))
    for number, row in table.rows:
        cells = _by_field(row, columns_to_fields)
        fixed = corrections.get(str(number), {})
        cells.update(fixed)
        if equipment:
            _device(tenant, file_id, file_name, cells, now, once, skipped, number, style_of_cell("amount", fixed))
            continue
        vendor = values.text(cells.get("vendor"))
        product = values.text(cells.get("product"))
        email = values.text(cells.get("email")).lower()
        employee = values.text(cells.get("employee"))
        department = values.text(cells.get("department"))
        stated_currency, _ = values.currency(cells.get("currency"), currency)
        row_currency = stated_currency or values.currency_in(cells.get("amount"), currency) or currency

        # an INN a person corrected comes back as a correction of its column (an extracted invoice)
        inn = normalized.clean_inn(cells.get("ИНН поставщика") or row.get("ИНН получателя")
                                   or row.get("ИНН поставщика"))
        parts: list[paying.Part] = []
        if payments and vendor and amount_text(cells):
            value, _ = values.amount(cells["amount"], style_of_cell("amount", fixed))
            if value is not None:
                parts = paying.split(vendor, inn, product, value, intermediaries, services)
        # the header of an extracted invoice repeats in every row; a person's correction of it comes by column
        invoice_number = invoice_number or values.text(cells.get("Номер счёта") or row.get("Номер счёта"))
        stated, _ = values.day(cells.get("Дата счёта") or row.get("Дата счёта"))
        invoice_day = invoice_day or stated
        if vendor:
            invoice_vendor = invoice_vendor or vendor
            for name, code in dict.fromkeys((p.vendor, p.inn) for p in parts) if parts else [(vendor, inn)]:
                once(_normalized(tenant, file_id, "VENDOR", normalized.vendor_key(tenant, name, code),
                                 {"name": name, "vendor_id": vendor_id if name == vendor else None, "inn": code},
                                 now))
        paid_on, _ = values.day(cells.get("period_start"), style_of_cell("period_start", fixed))
        for part in parts:
            once(_normalized(tenant, file_id, "PAYMENT",
                             _entity_key(tenant, "payment", storage_key, str(number), *_suffix(part)),
                             {"vendor": part.vendor, "purpose": product or None, "amount": str(part.amount),
                              "currency": row_currency, "date": paid_on.isoformat() if paid_on else None,
                              "inn": part.inn,
                              "intermediary": part.intermediary, "role": part.role, "note": part.note}, now))
        if vendor and product and not payments:
            once(_normalized(tenant, file_id, "SUBSCRIPTION", _entity_key(tenant, "subscription", vendor, product),
                             {"vendor": vendor, "product": product}, now))
        person = email or employee
        if person:
            once(_normalized(tenant, file_id, "EMPLOYEE", normalized.person_key(tenant, email, employee),
                             {"name": employee or None, "email": email or None, "department": department or None},
                             now))
        if person and product and not payments:
            last_active, _ = values.day(cells.get("last_active"), style_of_cell("last_active", fixed))
            once(_normalized(tenant, file_id, "LICENSE_ASSIGNMENT",
                             _entity_key(tenant, "license", vendor, product, person),
                             {"vendor": vendor or None, "product": product, "employee": person,
                              "last_active": last_active}, now))

        if "amount" not in cells or values.text(cells["amount"]) == "":
            continue
        amount, _ = values.amount(cells["amount"], style_of_cell("amount", fixed))
        if amount is None:
            skipped.append((number, "amount is not a number"))
            continue
        start = paid_on
        end, _ = values.day(cells.get("period_end"), style_of_cell("period_end", fixed))
        start = start or end or _file_day(now)
        end = end or start
        if end < start:
            skipped.append((number, "period ends before it starts"))
            continue
        invoice_currency = invoice_currency or row_currency
        if not stated:
            invoice_day = min(invoice_day, start) if invoice_day else start
        shares = [(p.vendor, p.amount, _suffix(p)) for p in parts] or [(vendor or product or "unknown", amount, ())]
        for provider, share, suffix in shares:
            source_id = ":".join((storage_key, str(number), *suffix))
            record = CostRecord(
                id=CostRecord.stable_id(source, source_id), tenant_id=tenant, source=source, source_id=source_id,
                billed_cost=share, effective_cost=share,
                billing_currency=row_currency, charge_period_start=start, charge_period_end=end,
                service_provider_name=provider, department_id=_uuid_or_none(department))
            messages.append(Message("cost.records.v1", str(tenant), record.avro()))
        invoice_total += amount
        invoice_lines.append({"description": product or vendor or "", "amount": str(amount)})

    if invoice_total and not payments:
        once(_normalized(tenant, file_id, "INVOICE", _entity_key(tenant, "invoice", storage_key),
                         {"file_name": file_name, "vendor": invoice_vendor, "vendor_id": vendor_id,
                          "total": str(invoice_total), "number": invoice_number or None,
                          "date": invoice_day.isoformat() if invoice_day else None,
                          "currency": invoice_currency or currency, "lines": invoice_lines}, now))
    return messages, skipped


def _device(tenant: uuid.UUID, file_id: uuid.UUID, file_name: str, cells: dict[str, object], now: datetime,
            once: Callable[[Message], None], skipped: list[tuple[int, str]], number: int,
            style: values.Style = values.UNKNOWN) -> None:
    """One row of a list of equipment: the ASSET entity, keyed as the connectors key it, and the person who holds it."""
    serial = values.text(cells.get("serial_number")) or None
    inventory = values.text(cells.get("inventory_number")) or None
    if serial is None and inventory is None:
        skipped.append((number, "no inventory or serial number"))
        return
    model = values.text(cells.get("asset_model")) or values.text(cells.get("product")) or None
    cost = None
    if amount_text(cells):
        cost, _ = values.amount(cells["amount"], style)
    email = values.text(cells.get("email")).lower() or None
    employee = values.text(cells.get("employee")) or None
    if email or employee:
        once(_normalized(tenant, file_id, "EMPLOYEE", normalized.person_key(tenant, email, employee or ""),
                         {"name": employee, "email": email,
                          "department": values.text(cells.get("department")) or None}, now))
    once(_normalized(tenant, file_id, "ASSET", normalized.key(tenant, "asset", serial or inventory),
                     {"serial_number": serial, "inventory_number": inventory, "model": model,
                      "type": normalized.asset_kind(cells.get("asset_type"), model),
                      "cost": None if cost is None else str(cost), "holder_email": normalized.clean_email(email),
                      "holder_name": employee, "source": "excel", "file_name": file_name}, now))


def _suffix(part: paying.Part) -> tuple[str, ...]:
    """The commission is a record of its own; the service keeps the key the whole payment had."""
    return ("commission",) if part.role == "commission" else ()


def amount_text(cells: dict[str, object]) -> str:
    return values.text(cells.get("amount"))


def _file_day(now: datetime) -> date:
    return now.date()


def _uuid_or_none(text: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(text) if text else None
    except ValueError:
        return None
