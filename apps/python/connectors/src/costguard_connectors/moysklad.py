"""МойСклад JSON API 1.2: counterparties become vendors, supplies (приёмки) become invoices and cost records.

Sums in МойСклад are in kopecks. The cursor is the `updated` time of the last supply read.
"""

from __future__ import annotations

import uuid
from datetime import datetime
from decimal import Decimal

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

BASE_URL = "https://api.moysklad.ru/api/remap/1.2"


class MoySklad:
    name = "moysklad"

    def __init__(self, client: httpx.Client, page: int = 1000) -> None:
        """client carries base_url (BASE_URL) and the Bearer token."""
        self._http = client
        self._page = page

    def _all(self, path: str, **params) -> list[dict]:
        rows: list[dict] = []
        offset = 0
        while True:
            response = self._http.get(path, params={"limit": self._page, "offset": offset, **params})
            response.raise_for_status()
            body = response.json()
            rows += body.get("rows", [])
            offset += self._page
            if offset >= body.get("meta", {}).get("size", 0):
                return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        batch = Batch(cursor=cursor)
        vendors = {}
        for c in self._all("/entity/counterparty"):
            if c.get("archived"):
                continue
            entity = entities.vendor(tenant, c.get("legalTitle") or c["name"], c.get("inn"), source=self.name,
                                     email=c.get("email"))
            vendors[c["id"]] = entity
            batch.entities.append(entity)

        params = {"order": "updated,asc"}
        if cursor:
            params["filter"] = f"updated>{cursor}"
        for supply in self._all("/entity/supply", **params):
            batch.cursor = max(batch.cursor or "", supply.get("updated", ""))
            if not supply.get("applicable", True):
                continue
            agent = supply.get("agent", {}).get("meta", {}).get("href", "").rsplit("/", 1)[-1]
            vendor = vendors.get(agent)
            if vendor is None:
                continue
            day = datetime.fromisoformat(supply["moment"].replace(" ", "T")).date()
            total = Decimal(supply.get("sum", 0)) / 100
            currency = supply.get("rate", {}).get("currency", {}).get("isoCode") or "RUB"
            batch.entities.append(entities.invoice(tenant, supply.get("name", supply["id"]), vendor, total, currency,
                                                   day, self.name))
            batch.costs.append(entities.cost(tenant, self.name, supply["id"], total, currency, day,
                                             vendor.payload["name"]))
        return batch
