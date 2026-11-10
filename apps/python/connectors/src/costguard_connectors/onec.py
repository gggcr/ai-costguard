"""1С:Бухгалтерия and 1С:УТ through the standard OData interface (/odata/standard.odata).

Counterparties become vendors; posted receipts of goods and services (ПоступлениеТоваровУслуг) become invoices and
cost records. The cursor is the date of the last receipt read, so a pull asks only for newer documents; a
document changed after it was read keeps its id and replaces its record.

Fixed assets of 1С:Бухгалтерия (Catalog_ОсновныеСредства) become devices for the assets module (board frame 15):
the inventory number, the serial (ЗаводскойНомер), the name as the model, the kind guessed from the name, and the
person responsible (МОЛ) from the latest record of МестонахождениеОСБухгалтерскийУчет as the holder. A configuration
without fixed assets (1С:УТ) answers 404 there, and only invoices are read. What an asset cost is not read: 1C keeps
it in accounting registers per period, and the cost of a device is set in CostGuard.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

import httpx

from costguard_common import normalized
from costguard_connectors import entities
from costguard_connectors.contract import Batch

RECEIPTS = "Document_ПоступлениеТоваровУслуг"
FIXED_ASSETS = "Catalog_ОсновныеСредства"
ASSET_PLACES = "InformationRegister_МестонахождениеОСБухгалтерскийУчет"
PEOPLE = "Catalog_ФизическиеЛица"
COUNTERPARTIES = "Catalog_Контрагенты"
CURRENCIES = "Catalog_Валюты"


class OneC:
    name = "1c"

    def __init__(self, client: httpx.Client, default_currency: str = "RUB", page: int = 500) -> None:
        """client carries base_url (…/odata/standard.odata) and the Basic credentials of a 1C user."""
        self._http = client
        self._currency = default_currency
        self._page = page

    def _all(self, entity: str, **params) -> list[dict]:
        rows: list[dict] = []
        skip = 0
        while True:
            response = self._http.get(f"/{entity}", params={"$format": "json", "$top": self._page, "$skip": skip,
                                                            **params})
            response.raise_for_status()
            page = response.json().get("value", [])
            rows += page
            if len(page) < self._page:
                return rows
            skip += self._page

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        batch = Batch(cursor=cursor)
        vendors = {}
        for c in self._all(COUNTERPARTIES, **{"$filter": "IsFolder eq false"}):
            if c.get("DeletionMark"):
                continue
            entity = entities.vendor(tenant, c.get("НаименованиеПолное") or c["Description"], c.get("ИНН"),
                                     source=self.name, kpp=c.get("КПП") or None)
            vendors[c["Ref_Key"]] = entity
            batch.entities.append(entity)
        currencies = {c["Ref_Key"]: c.get("Description") or c.get("Code") for c in self._all(CURRENCIES)}

        params = {"$filter": "Posted eq true", "$orderby": "Date"}
        if cursor:
            params["$filter"] += f" and Date gt datetime'{cursor}'"
        for doc in self._all(RECEIPTS, **params):
            batch.cursor = max(batch.cursor or "", doc["Date"])
            vendor = vendors.get(doc.get("Контрагент_Key"))
            if vendor is None:
                continue
            day = datetime.fromisoformat(doc["Date"]).date()
            total = Decimal(str(doc.get("СуммаДокумента") or 0))
            currency = _iso(currencies.get(doc.get("ВалютаДокумента_Key"))) or self._currency
            number = str(doc.get("Number", "")).strip()
            batch.entities.append(entities.invoice(tenant, number, vendor, total, currency, day, self.name))
            batch.costs.append(entities.cost(tenant, self.name, doc["Ref_Key"], total, currency, day,
                                             vendor.payload["name"]))
        batch.entities += self._assets(tenant)
        return batch

    def _optional(self, entity: str, **params) -> list[dict] | None:
        """None when the configuration has no such entity (404) or does not publish it (400)."""
        try:
            return self._all(entity, **params)
        except httpx.HTTPStatusError as e:
            if e.response.status_code in (400, 404):
                return None
            raise

    def _assets(self, tenant: uuid.UUID) -> list:
        assets = self._optional(FIXED_ASSETS, **{"$filter": "IsFolder eq false"})
        if not assets:
            return []
        holders: dict[str, str] = {}
        places = self._optional(ASSET_PLACES, **{"$orderby": "Period"}) or []
        people = {p["Ref_Key"]: p.get("Description") for p in (self._optional(PEOPLE) or [])} if places else {}
        for place in places:  # in order of the period: the latest record wins
            name = people.get(place.get("МОЛ_Key"))
            if place.get("ОсновноеСредство_Key"):
                holders[place["ОсновноеСредство_Key"]] = name
        out = []
        for a in assets:
            if a.get("DeletionMark"):
                continue
            name = (a.get("НаименованиеПолное") or a.get("Description") or "").strip()
            device = entities.asset(tenant, self.name, a.get("ЗаводскойНомер"),
                                    a.get("ИнвентарныйНомер") or a.get("Code"), name,
                                    normalized.asset_kind(name), holder_name=holders.get(a["Ref_Key"]))
            if device is not None:
                out.append(device)
        return out


def _iso(name: str | None) -> str | None:
    if not name:
        return None
    text = name.strip().lower()
    known = {"руб": "RUB", "rub": "RUB", "643": "RUB", "usd": "USD", "840": "USD", "eur": "EUR", "978": "EUR",
             "cny": "CNY", "156": "CNY"}
    return next((code for sign, code in known.items() if text.startswith(sign)), None)
