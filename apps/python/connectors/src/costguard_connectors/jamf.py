"""Jamf Pro: Macs, iPhones and iPads the company manages, and who each is assigned to.

An API client (Settings → API roles and clients) with the privileges "Read Computers" and "Read Mobile Devices"
gets a token from /api/oauth/token. Computers come from /api/v1/computers-inventory, mobile devices from
/api/v2/mobile-devices/detail, a page of 100 at a time.

The holder is the user in "User and Location"; a device without one is reported without a holder, and the core
then keeps whoever holds it. The asset tag is the inventory number when it is filled in. Jamf's purchase price
is not reported: its currency is not known, and the cost of a device is accounting's.
"""

from __future__ import annotations

import time
import uuid

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

PAGE = 100
SECTIONS = ("GENERAL", "HARDWARE", "USER_AND_LOCATION")


def _first(d: dict, *names: str) -> str | None:
    for n in names:
        value = d.get(n)
        if value not in (None, ""):
            return str(value)
    return None


class Jamf:
    name = "jamf"

    def __init__(self, client: httpx.Client, client_id: str, client_secret: str) -> None:
        """client carries base_url: https://<company>.jamfcloud.com or the on-premises address."""
        self._http = client
        self._id = client_id
        self._secret = client_secret
        self._token: str | None = None
        self._expires = 0.0

    def _auth(self) -> dict[str, str]:
        if self._token is None or time.monotonic() > self._expires - 30:
            response = self._http.post("/api/oauth/token", data={
                "client_id": self._id, "client_secret": self._secret, "grant_type": "client_credentials"})
            response.raise_for_status()
            body = response.json()
            self._token = body["access_token"]
            self._expires = time.monotonic() + float(body.get("expires_in", 60))
        return {"Authorization": f"Bearer {self._token}"}

    def _pages(self, path: str) -> list[dict]:
        rows: list[dict] = []
        page = 0
        while True:
            params = [("section", s) for s in SECTIONS] + [("page", page), ("page-size", PAGE)]
            response = self._http.get(path, params=params, headers=self._auth())
            response.raise_for_status()
            body = response.json()
            found = body.get("results", [])
            rows += found
            if not found or len(rows) >= int(body.get("totalCount", len(rows))):
                return rows
            page += 1

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        batch = Batch(cursor=cursor)
        for c in self._pages("/api/v1/computers-inventory"):
            general, hardware, user = c.get("general") or {}, c.get("hardware") or {}, c.get("userAndLocation") or {}
            model = _first(hardware, "model", "modelIdentifier") or _first(general, "name")
            kind = "LAPTOP" if model and "macbook" in model.lower() else "OTHER"
            self._add(batch, tenant, hardware, general, user, model, kind, f"computer:{c.get('id')}")
        for m in self._pages("/api/v2/mobile-devices/detail"):
            general, hardware, user = m.get("general") or {}, m.get("hardware") or {}, m.get("userAndLocation") or {}
            model = _first(hardware, "model", "modelIdentifier") or _first(general, "displayName")
            kind = "PHONE" if model and "iphone" in model.lower() else "OTHER"
            self._add(batch, tenant, hardware, general, user, model, kind,
                      f"mobile:{m.get('mobileDeviceId') or m.get('id')}")
        return batch

    def _add(self, batch: Batch, tenant: uuid.UUID, hardware: dict, general: dict, user: dict, model: str | None,
             kind: str, external_id: str) -> None:
        device = entities.asset(
            tenant, self.name, _first(hardware, "serialNumber"), _first(general, "assetTag"), model, kind,
            holder_email=_first(user, "email", "emailAddress"),
            holder_name=_first(user, "realname", "realName"), external_id=external_id)
        if device is not None:
            batch.entities.append(device)
