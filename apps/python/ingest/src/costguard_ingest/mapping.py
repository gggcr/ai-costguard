"""Suggests which field each column holds. People confirm the mapping in the core before rows are written.

The field names are FocusFields.ALL of the core imports module.
"""

from __future__ import annotations

import re

FIELDS = ("vendor", "product", "employee", "email", "amount", "currency", "period_start", "period_end",
          "last_active", "department", "inventory_number", "serial_number", "asset_model", "asset_type", "skip")

_SYNONYMS: dict[str, tuple[str, ...]] = {
    "vendor": ("vendor", "supplier", "provider", "serviceprovidername", "поставщик", "контрагент", "вендор",
               "продавец", "исполнитель", "получатель"),
    "product": ("product", "service", "item", "description", "sku", "plan", "продукт", "услуга", "наименование",
                "товар", "сервис", "тариф", "номенклатура", "описание", "назначение"),
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


def field_of(column: str) -> str | None:
    key = _norm(column)
    if not key:
        return None
    for field in _ORDER:
        if key in _SYNONYMS[field]:
            return field
    for field in _ORDER:
        if any(len(w) >= 4 and w in key for w in _SYNONYMS[field]):
            return field
    return None


def suggest(columns: list[str]) -> dict[str, str]:
    """Column to field. A field goes to the first column that names it; other columns are skipped."""
    taken: set[str] = set()
    result: dict[str, str] = {}
    for column in columns:
        field = field_of(column)
        if field is None or field in taken:
            result[column] = "skip"
            continue
        taken.add(field)
        result[column] = field
    return result
