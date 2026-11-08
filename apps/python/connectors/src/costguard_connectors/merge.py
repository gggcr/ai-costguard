"""Merge unified API (ADR 0014): one connector for the accounting and HR systems Merge links (QuickBooks, Xero,
NetSuite, BambooHR, Workday, Personio and others).

Merge links each system of a company as its own account: the accounting one and the HR one each have an account
token (X-Account-Token), next to the API key of CostGuard (Authorization: Bearer). Either may be absent.

- Accounting: suppliers (contacts with is_supplier) are vendors; payable invoices (type ACCOUNTS_PAYABLE) are
  invoices and cost records; payments are PAYMENT entities linked to their vendor. Payments are not cost records:
  they pay the invoices already counted, and counting both would double the spend.
- HR: employees with their work e-mail; INACTIVE with a termination date is a dismissal, which only an HR system
  may say.

Lists page with `next` cursors. The connector cursor keeps, per category, the time of this pull, and the next pull
asks only for what changed after it (modified_after).
"""

from __future__ import annotations

import json
import uuid
from datetime import UTC, date, datetime
from decimal import Decimal, InvalidOperation

import httpx

from costguard_common import normalized
from costguard_connectors import entities
from costguard_connectors.contract import Batch

BASE_URL = "https://api.merge.dev"


def _day(text: str | None) -> date | None:
    if not text:
        return None
    try:
        return datetime.fromisoformat(text.replace("Z", "+00:00")).date()
    except ValueError:
        return None


def _money(value: object) -> Decimal | None:
    try:
        return None if value is None else Decimal(str(value))
    except InvalidOperation:
        return None


class Merge:
    name = "merge"

    def __init__(self, client: httpx.Client, accounting_token: str | None = None, hris_token: str | None = None,
                 default_currency: str = "RUB", now=lambda: datetime.now(UTC)) -> None:
        """client carries base_url (BASE_URL) and Authorization: Bearer <Merge API key>."""
        self._http = client
        self._tokens = {"accounting": accounting_token, "hris": hris_token}
        self._currency = default_currency
        self._now = now

    def _all(self, category: str, path: str, params: dict) -> list[dict]:
        rows: list[dict] = []
        cursor = None
        while True:
            response = self._http.get(f"/api/{category}/v1/{path}",
                                      params={**params, "page_size": 100, **({"cursor": cursor} if cursor else {})},
                                      headers={"X-Account-Token": self._tokens[category] or ""})
            response.raise_for_status()
            body = response.json()
            rows += [r for r in body.get("results", []) if not r.get("remote_was_deleted")]
            cursor = body.get("next")
            if not cursor:
                return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        since = json.loads(cursor) if cursor else {}
        started = self._now().isoformat()
        batch = Batch()
        if self._tokens["accounting"]:
            self._accounting(tenant, since.get("accounting"), batch)
            since["accounting"] = started
        if self._tokens["hris"]:
            self._hris(tenant, since.get("hris"), batch)
            since["hris"] = started
        batch.cursor = json.dumps(since, sort_keys=True)
        return batch

    def _changed(self, since: str | None) -> dict:
        return {"modified_after": since} if since else {}

    def _accounting(self, tenant: uuid.UUID, since: str | None, batch: Batch) -> None:
        # vendors in full: an invoice changed today may be of a supplier unchanged for a year
        vendors: dict[str, normalized.Entity] = {}
        for c in self._all("accounting", "contacts", {"is_supplier": "true"}):
            vendor = entities.vendor(tenant, c.get("name") or "", c.get("tax_number"), source=self.name,
                                     external_id=c.get("id"))
            vendors[c["id"]] = vendor
            batch.entities.append(vendor)
        for inv in self._all("accounting", "invoices", {"type": "ACCOUNTS_PAYABLE", **self._changed(since)}):
            vendor = vendors.get(inv.get("contact") if isinstance(inv.get("contact"), str)
                                 else (inv.get("contact") or {}).get("id"))
            total, day = _money(inv.get("total_amount")), _day(inv.get("issue_date"))
            if vendor is None or total is None or day is None or inv.get("status") in ("VOID", "DRAFT"):
                continue
            currency = inv.get("currency") or self._currency
            number = inv.get("number") or inv["id"]
            batch.entities.append(entities.invoice(tenant, number, vendor, total, currency, day, self.name))
            batch.costs.append(entities.cost(tenant, self.name, inv["id"], total, currency, day,
                                             vendor.payload["name"]))
        for pay in self._all("accounting", "payments", self._changed(since)):
            vendor = vendors.get(pay.get("contact") if isinstance(pay.get("contact"), str)
                                 else (pay.get("contact") or {}).get("id"))
            amount, day = _money(pay.get("total_amount")), _day(pay.get("transaction_date"))
            if vendor is None or amount is None:
                continue
            batch.entities.append(normalized.Entity(
                normalized.EntityType.PAYMENT, normalized.key(tenant, "payment", self.name, pay["id"]),
                {"vendor": vendor.payload["name"], "inn": vendor.payload["inn"], "amount": str(amount),
                 "currency": pay.get("currency") or self._currency, "date": day.isoformat() if day else None,
                 "purpose": pay.get("reference") or None, "source": self.name}))

    def _hris(self, tenant: uuid.UUID, since: str | None, batch: Batch) -> None:
        for e in self._all("hris", "employees", self._changed(since)):
            name = e.get("display_full_name") or " ".join(p for p in (e.get("last_name"), e.get("first_name")) if p)
            status = e.get("employment_status")
            dismissed = status == "INACTIVE" and bool(e.get("termination_date"))
            batch.entities.append(entities.employee(
                tenant, name, e.get("work_email") or e.get("personal_email"), None, status != "INACTIVE",
                source=self.name, dismissed=dismissed, external_id=e.get("id"),
                dismissed_on=_day(e.get("termination_date"))))
