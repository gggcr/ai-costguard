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
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from decimal import Decimal

from costguard_common import normalized
from costguard_common.focus import CostRecord, CostSource
from costguard_ingest import mapping, tables, values

DEFAULT_CURRENCY = "RUB"


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


def parse(file_name: str, content: bytes) -> Parsed:
    table = tables.read(file_name, content)
    suggested = mapping.suggest(table.columns)
    result = Parsed(table.columns, suggested, len(table.rows), Decimal(0))
    if "amount" not in suggested.values():
        result.errors.append((1, "No column looks like an amount; choose it in the mapping"))
    for number, row in table.rows:
        cells = _by_field(row, suggested)
        if "amount" in cells:
            raw = cells["amount"]
            value, confidence = values.amount(raw)
            if value is None:
                result.errors.append((number, f"Amount '{values.text(raw)}' is not a number"))
            else:
                result.total += value
                if confidence < values.CONFIDENT:
                    result.uncertain.append((number, "amount", values.text(raw), confidence))
        for f in ("period_start", "period_end", "last_active"):
            if f not in cells or values.text(cells[f]) == "":
                continue
            parsed, confidence = values.day(cells[f])
            if parsed is None:
                result.errors.append((number, f"Date '{values.text(cells[f])}' in {f} is not a date"))
            elif confidence < values.CONFIDENT:
                result.uncertain.append((number, f, values.text(cells[f]), confidence))
        if "currency" in cells and values.text(cells["currency"]):
            code, confidence = values.currency(cells["currency"])
            if code is None or confidence < values.CONFIDENT:
                result.uncertain.append((number, "currency", values.text(cells["currency"]), confidence))
    return result


_entity_key = normalized.key


def _normalized(tenant: uuid.UUID, file_id: uuid.UUID, kind: str, key: str, payload: dict, at: datetime,
                confidence: float = 1.0) -> Message:
    entity = normalized.Entity(normalized.EntityType(kind), key, payload, confidence)
    return Message(normalized.TOPIC, key, entity.message(tenant, file_id, at))


def accept(tenant: uuid.UUID, file_id: uuid.UUID, storage_key: str, file_name: str, vendor_id: uuid.UUID | None,
           columns_to_fields: dict[str, str], corrections: dict[str, dict[str, str]], content: bytes,
           now: datetime | None = None) -> tuple[list[Message], list[tuple[int, str]]]:
    """Messages to publish, and rows that still could not be written (they are logged, not retried)."""
    now = now or datetime.now(UTC)
    table = tables.read(file_name, content)
    messages: list[Message] = []
    skipped: list[tuple[int, str]] = []
    seen: set[str] = set()

    def once(m: Message) -> None:
        if m.key not in seen:
            seen.add(m.key)
            messages.append(m)

    payments = table.kind == "statement"
    source = CostSource.PAYMENT if payments else CostSource.INVOICE_LINE
    invoice_total = Decimal(0)
    invoice_vendor: str | None = None
    for number, row in table.rows:
        cells = _by_field(row, columns_to_fields)
        cells.update(corrections.get(str(number), {}))
        vendor = values.text(cells.get("vendor"))
        product = values.text(cells.get("product"))
        email = values.text(cells.get("email")).lower()
        employee = values.text(cells.get("employee"))
        department = values.text(cells.get("department"))

        if vendor:
            invoice_vendor = invoice_vendor or vendor
            inn = normalized.clean_inn(row.get("ИНН получателя"))
            once(_normalized(tenant, file_id, "VENDOR", normalized.vendor_key(tenant, vendor, inn),
                             {"name": vendor, "vendor_id": vendor_id, "inn": inn}, now))
        if payments and vendor and amount_text(cells):
            once(_normalized(tenant, file_id, "PAYMENT", _entity_key(tenant, "payment", storage_key, str(number)),
                             {"vendor": vendor, "purpose": product or None, "amount": amount_text(cells),
                              "date": values.text(cells.get("period_start")) or None,
                              "inn": values.text(row.get("ИНН получателя")) or None}, now))
        if vendor and product and not payments:
            once(_normalized(tenant, file_id, "SUBSCRIPTION", _entity_key(tenant, "subscription", vendor, product),
                             {"vendor": vendor, "product": product}, now))
        person = email or employee
        if person:
            once(_normalized(tenant, file_id, "EMPLOYEE", normalized.person_key(tenant, email, employee),
                             {"name": employee or None, "email": email or None, "department": department or None},
                             now))
        if person and product and not payments:
            last_active, _ = values.day(cells.get("last_active"))
            once(_normalized(tenant, file_id, "LICENSE_ASSIGNMENT",
                             _entity_key(tenant, "license", vendor, product, person),
                             {"vendor": vendor or None, "product": product, "employee": person,
                              "last_active": last_active}, now))

        if "amount" not in cells or values.text(cells["amount"]) == "":
            continue
        amount, _ = values.amount(cells["amount"])
        if amount is None:
            skipped.append((number, "amount is not a number"))
            continue
        start, _ = values.day(cells.get("period_start"))
        end, _ = values.day(cells.get("period_end"))
        start = start or end or _file_day(now)
        end = end or start
        if end < start:
            skipped.append((number, "period ends before it starts"))
            continue
        code, _ = values.currency(cells.get("currency"))
        code = code or values.currency_in(cells["amount"]) or DEFAULT_CURRENCY
        provider = vendor or product or "unknown"
        source_id = f"{storage_key}:{number}"
        record = CostRecord(
            id=CostRecord.stable_id(source, source_id), tenant_id=tenant, source=source, source_id=source_id,
            billed_cost=amount, effective_cost=amount,
            billing_currency=code, charge_period_start=start, charge_period_end=end,
            service_provider_name=provider, department_id=_uuid_or_none(department))
        messages.append(Message("cost.records.v1", str(tenant), record.avro()))
        invoice_total += amount

    if invoice_total and not payments:
        once(_normalized(tenant, file_id, "INVOICE", _entity_key(tenant, "invoice", storage_key),
                         {"file_name": file_name, "vendor": invoice_vendor, "vendor_id": vendor_id,
                          "total": str(invoice_total)}, now))
    return messages, skipped


def amount_text(cells: dict[str, object]) -> str:
    return values.text(cells.get("amount"))


def _file_day(now: datetime) -> date:
    return now.date()


def _uuid_or_none(text: str) -> uuid.UUID | None:
    try:
        return uuid.UUID(text) if text else None
    except ValueError:
        return None
