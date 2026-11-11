"""1С:Зарплата и управление персоналом (ЗУП 3.x) through the standard OData interface: payslips to «Мой кабинет».

What a company paid each person comes from the posted pay statements (ведомости на выплату): to a bank, to cards
and through the cash desk. Each statement has a date (the pay day), a month (ПериодРегистрации), a way of paying
(СпособВыплаты: «Аванс», «Зарплата за месяц», «Отпуск»...) and a table of people with the sum to pay (КВыплате).
A person is a Catalog_Сотрудники row; the core finds the employee by that full name (ADR 0054).

Pay is personal data the core keeps away from Kafka and ClickHouse, so this connector does not publish: it posts
the payments to the core, POST /api/v1/payroll/payments, with the token of the connector service account. The core
replaces a payment of the same person, kind, month and day, so reading a statement again is harmless; every run
reads the statements of the last `days` days.

The entity and field names are those of ЗУП 3.1 published over OData; a base with other names needs them here.
"""

from __future__ import annotations

from datetime import date, datetime, timedelta
from decimal import Decimal

import httpx

STATEMENTS = (
    "Document_ВедомостьНаВыплатуЗарплатыВБанк",
    "Document_ВедомостьНаВыплатуЗарплатыНаСчета",
    "Document_ВедомостьНаВыплатуЗарплатыВКассу",
)
EMPLOYEES = "Catalog_Сотрудники"
WAYS = "Catalog_СпособыВыплатыЗарплаты"
TABLE = "Зарплата"


def kind_of(way: str) -> str:
    """The kind of payment by the name of the way of paying."""
    w = (way or "").lower()
    if "аванс" in w:
        return "advance"
    if "отпуск" in w:
        return "vacation_pay"
    if "прем" in w:
        return "bonus"
    if "зарплат" in w or "межрасчет" in w or not w:
        return "salary"
    return "other"


def _date(value: str | None) -> date | None:
    if not value or value.startswith("0001-01-01"):
        return None
    return datetime.fromisoformat(value[:19]).date()


class ZupPayroll:
    name = "onec_zup"

    def __init__(self, odata: httpx.Client, core: httpx.Client, days: int = 62, page: int = 500,
                 today: date | None = None) -> None:
        """odata carries base_url (…/odata/standard.odata) and the Basic credentials of a ЗУП user; core the base
        url and the bearer token of the core."""
        self._odata = odata
        self._core = core
        self._days = days
        self._page = page
        self._today = today

    def _all(self, entity: str, **params) -> list[dict]:
        rows: list[dict] = []
        skip = 0
        while True:
            response = self._odata.get(f"/{entity}", params={"$format": "json", "$top": self._page, "$skip": skip,
                                                             **params})
            if response.status_code == 404:  # a base without this kind of statement
                return rows
            response.raise_for_status()
            page = response.json().get("value", [])
            rows += page
            if len(page) < self._page:
                return rows
            skip += self._page

    def payments(self) -> list[dict]:
        since = (self._today or date.today()) - timedelta(days=self._days)
        people = {p["Ref_Key"]: p.get("Description", "") for p in self._all(EMPLOYEES)}
        ways = {w["Ref_Key"]: w.get("Description", "") for w in self._all(WAYS)}
        out: list[dict] = []
        posted_since = f"Posted eq true and Date ge datetime'{since.isoformat()}T00:00:00'"
        for entity in STATEMENTS:
            docs = self._all(entity, **{"$filter": posted_since})
            for doc in docs:
                if doc.get("DeletionMark"):
                    continue
                pay_day = _date(doc.get("ДатаВыплаты")) or _date(doc.get("Date"))
                month = _date(doc.get("ПериодРегистрации")) or pay_day
                if pay_day is None or month is None:
                    continue
                kind = kind_of(ways.get(doc.get("СпособВыплаты_Key", ""), ""))
                for line in doc.get(TABLE, []):
                    name = people.get(line.get("Сотрудник_Key", ""), "")
                    amount = Decimal(str(line.get("КВыплате", 0) or 0))
                    if not name or amount <= 0:
                        continue
                    out.append({"employeeName": name, "kind": kind, "period": month.replace(day=1).isoformat(),
                                "payDate": pay_day.isoformat(), "amount": str(amount),
                                "externalKey": f"{doc['Ref_Key']}:{line.get('Сотрудник_Key', '')}"})
        return out

    def collect(self) -> int:
        """Reads the statements and posts the payments to the core; returns how many the core stored."""
        payments = self.payments()
        if not payments:
            return 0
        stored = 0
        for start in range(0, len(payments), 1000):
            r = self._core.post("/api/v1/payroll/payments",
                                json={"source": "1c-zup", "payments": payments[start:start + 1000]})
            r.raise_for_status()
            stored += int(r.json().get("stored", 0))
        return stored
