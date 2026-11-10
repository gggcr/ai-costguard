"""Suggests which field each column holds. People confirm the mapping in the core before rows are written.

The field names are FocusFields.ALL of the core imports module. A cost export in FOCUS 1.0 (FinOps Open Cost and
Usage Specification: AWS, Azure, Google Cloud and Yandex Cloud write it) maps by itself: BilledCost, BillingCurrency,
ChargePeriodStart, ChargePeriodEnd, ProviderName and ServiceName are synonyms of the fields they are.
"""

from __future__ import annotations

import re

FIELDS = ("vendor", "product", "employee", "email", "amount", "currency", "period_start", "period_end",
          "last_active", "department", "inventory_number", "serial_number", "asset_model", "asset_type", "skip")

_SYNONYMS: dict[str, tuple[str, ...]] = {
    "vendor": ("vendor", "supplier", "provider", "serviceprovidername", "providername", "поставщик", "контрагент",
               "вендор", "продавец", "исполнитель", "получатель"),
    "product": ("product", "service", "servicename", "item", "description", "sku", "plan", "продукт", "услуга",
                "наименование", "товар", "сервис", "тариф", "номенклатура", "описание", "назначение"),
    "employee": ("employee", "user", "name", "fullname", "сотрудник", "пользователь", "фио", "работник"),
    "email": ("email", "e-mail", "mail", "login", "почта", "логин"),
    "amount": ("amount", "sum", "total", "cost", "price", "billedcost", "сумма", "стоимость", "итого", "цена"),
    "currency": ("currency", "curr", "billingcurrency", "валюта"),
    "period_start": ("periodstart", "start", "from", "date", "chargeperiodstart", "датаначала", "начало",
                     "периодс", "дата", "датасчета", "датаоплаты"),
    "period_end": ("periodend", "end", "to", "till", "chargeperiodend", "датаокончания", "окончание", "периодпо",
                   "по"),
    "last_active": ("lastactive", "lastlogin", "lastseen", "lastactivity", "последняяактивность",
                    "последнийвход", "активность"),
    "department": ("department", "costcenter", "dept", "team", "отдел", "подразделение", "цфо", "департамент"),
    # a list of equipment (board frame 15): its rows are devices, not spending
    "inventory_number": ("inventorynumber", "inventory", "assettag", "инвентарныйномер", "инвномер", "инв",
                         "инвентарный"),
    "serial_number": ("serialnumber", "serial", "sn", "серийныйномер", "заводскойномер", "серийный", "серийник"),
    "asset_model": ("model", "devicemodel", "модель", "моделькомпьютера"),
    "asset_type": ("assettype", "devicetype", "типтехники", "видтехники", "типустройства", "видустройства"),
}

# Checked before the loose match, so "Дата окончания" is not taken for a start date.
_ORDER = ("inventory_number", "serial_number", "asset_type", "asset_model", "last_active", "period_end",
          "period_start", "email", "currency", "amount", "department", "vendor", "employee", "product")


def _norm(column: str) -> str:
    return re.sub(r"[\s_\-.,:;()№#\"']", "", column.lower()).replace("ё", "е")


def exact_field(column: str) -> str | None:
    key = _norm(column)
    return next((field for field in _ORDER if key and key in _SYNONYMS[field]), None)


def field_of(column: str) -> str | None:
    key = _norm(column)
    if not key:
        return None
    exact = exact_field(column)
    if exact:
        return exact
    for field in _ORDER:
        if any(len(w) >= 4 and w in key for w in _SYNONYMS[field]):
            return field
    return None


def suggest(columns: list[str]) -> dict[str, str]:
    """Column to field. A column named exactly goes first, then the first column whose name contains a synonym, so
    ServiceName beats ServiceCategory and BilledCost beats ListCost; the other columns are skipped."""
    taken: set[str] = set()
    result: dict[str, str] = {c: "skip" for c in columns}
    for column in columns:
        field = exact_field(column)
        if field is not None and field not in taken:
            taken.add(field)
            result[column] = field
    # A FOCUS export names its columns by the specification: a loose match would only take InvoiceIssuerName for a
    # person or ListCost for the amount.
    if {"billedcost", "chargeperiodstart"} <= {_norm(c) for c in columns}:
        return result
    for column in columns:
        if result[column] != "skip":
            continue
        field = field_of(column)
        if field is not None and field not in taken:
            taken.add(field)
            result[column] = field
    return result
