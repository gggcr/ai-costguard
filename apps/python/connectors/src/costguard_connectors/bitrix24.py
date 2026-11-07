"""Bitrix24 through an incoming webhook (https://<portal>/rest/<user>/<token>/).

Every user with an e-mail becomes an employee and holds a Bitrix24 seat; LAST_LOGIN is the activity the audit
engine needs to find seats nobody uses. Fired users (ACTIVE = false) are reported too, so the core can see a
seat still paid for someone who left. Bitrix24 is where these companies keep HR, and a portal deactivates a user
when they are fired, so such a user is reported as dismissed: the core then dismisses them and shows what they
still hold.
"""

from __future__ import annotations

import uuid
from datetime import datetime

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

VENDOR = "Битрикс24"
PRODUCT = "Bitrix24"


class Bitrix24:
    name = "bitrix24"

    def __init__(self, client: httpx.Client) -> None:
        """client carries base_url: the webhook address."""
        self._http = client

    def _call(self, method: str, **params) -> list[dict]:
        rows: list[dict] = []
        start = 0
        while True:
            response = self._http.post(f"{method}.json", json={**params, "start": start})
            response.raise_for_status()
            body = response.json()
            if "error" in body:
                raise RuntimeError(f"Bitrix24 {method}: {body['error']} {body.get('error_description', '')}")
            rows += body.get("result", [])
            if "next" not in body:
                return rows
            start = body["next"]

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        departments = {str(d["ID"]): d.get("NAME") for d in self._call("department.get")}
        users = self._call("user.get", FILTER={"USER_TYPE": "employee"}, ADMIN_MODE=True)
        batch = Batch(cursor=cursor)
        vendor = entities.vendor(tenant, VENDOR, source=self.name)
        batch.entities.append(vendor)
        seats = 0
        for u in users:
            name = " ".join(p for p in (u.get("LAST_NAME"), u.get("NAME"), u.get("SECOND_NAME")) if p)
            dept = next((departments.get(str(d)) for d in u.get("UF_DEPARTMENT") or []), None)
            active = u.get("ACTIVE") in (True, "Y", "true", 1)
            person = entities.employee(tenant, name, u.get("EMAIL"), dept, active, source=self.name,
                                       dismissed=not active, external_id=str(u["ID"]),
                                       position=u.get("WORK_POSITION") or None)
            if person.payload["email"] is None and person.payload["name"] is None:
                continue
            batch.entities.append(person)
            last = u.get("LAST_LOGIN")
            last_active = datetime.fromisoformat(last).date() if last else None
            batch.entities.append(entities.license_assignment(tenant, VENDOR, PRODUCT, person, last_active,
                                                              self.name))
            seats += 1
        batch.entities.append(entities.subscription(tenant, VENDOR, PRODUCT, seats, self.name))
        return batch
