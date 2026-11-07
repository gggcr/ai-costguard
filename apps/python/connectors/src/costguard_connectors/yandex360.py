"""Яндекс 360 for business: the directory API gives users and departments, the audit log gives logins.

Every enabled user holds a Яндекс 360 seat. The last login comes from the mail audit log; without the
ya360_security:audit_log_mail scope the pull still works and reports no activity.
"""

from __future__ import annotations

import uuid
from datetime import datetime

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

BASE_URL = "https://api360.yandex.net"
VENDOR = "Яндекс"
PRODUCT = "Яндекс 360"


class Yandex360:
    name = "yandex360"

    def __init__(self, client: httpx.Client, org_id: str) -> None:
        """client carries base_url (BASE_URL) and the header Authorization: OAuth <token>."""
        self._http = client
        self._org = org_id

    def _pages(self, path: str, key: str) -> list[dict]:
        rows: list[dict] = []
        page = 1
        while True:
            response = self._http.get(path, params={"page": page, "perPage": 100})
            response.raise_for_status()
            body = response.json()
            rows += body.get(key, [])
            if page >= body.get("pages", 1):
                return rows
            page += 1

    def _logins(self) -> dict[str, datetime]:
        latest: dict[str, datetime] = {}
        token = None
        while True:
            params = {"pageSize": 100, **({"pageToken": token} if token else {})}
            response = self._http.get(f"/security/v1/org/{self._org}/audit_log/mail", params=params)
            if response.status_code in (401, 403):
                return latest
            response.raise_for_status()
            body = response.json()
            for event in body.get("events", []):
                uid, at = str(event.get("userUid", "")), event.get("date")
                if uid and at:
                    moment = datetime.fromisoformat(at.replace("Z", "+00:00"))
                    if uid not in latest or moment > latest[uid]:
                        latest[uid] = moment
            token = body.get("nextPageToken")
            if not token:
                return latest

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        base = f"/directory/v1/org/{self._org}"
        departments = {str(d["id"]): d.get("name") for d in self._pages(f"{base}/departments", "departments")}
        logins = self._logins()
        batch = Batch(cursor=cursor)
        batch.entities.append(entities.vendor(tenant, VENDOR, source=self.name))
        seats = 0
        for u in self._pages(f"{base}/users", "users"):
            if u.get("isRobot"):
                continue
            n = u.get("name") or {}
            name = " ".join(p for p in (n.get("last"), n.get("first"), n.get("middle")) if p)
            person = entities.employee(tenant, name, u.get("email"), departments.get(str(u.get("departmentId"))),
                                       bool(u.get("isEnabled", True)), source=self.name, external_id=str(u["id"]))
            batch.entities.append(person)
            if u.get("isEnabled", True):
                login = logins.get(str(u["id"]))
                batch.entities.append(entities.license_assignment(tenant, VENDOR, PRODUCT, person,
                                                                  login.date() if login else None, self.name))
                seats += 1
        batch.entities.append(entities.subscription(tenant, VENDOR, PRODUCT, seats, self.name))
        return batch
