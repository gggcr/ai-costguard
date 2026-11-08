"""Microsoft Entra ID through Microsoft Graph: users with their Microsoft licenses and last sign-in, and sign-ins
to apps behind Entra single sign-on.

An app registration with the application permissions User.Read.All, Directory.Read.All and AuditLog.Read.All
gets a token by client credentials (as for Intune). /subscribedSkus gives the paid licenses and their seats;
/users with signInActivity gives who holds which and when they last signed in (signInActivity needs Entra ID P1);
/auditLogs/signIns gives successful sign-ins per app since the cursor.
"""

from __future__ import annotations

import time
import uuid
from datetime import datetime

import httpx

from costguard_connectors import entities, signins
from costguard_connectors.contract import Batch

GRAPH = "https://graph.microsoft.com"
LOGIN = "https://login.microsoftonline.com"
VENDOR = "Microsoft"
USER_FIELDS = ("id", "displayName", "mail", "userPrincipalName", "accountEnabled", "department",
               "assignedLicenses", "signInActivity")


class Entra:
    name = "entra"

    def __init__(self, client: httpx.Client, directory_id: str, client_id: str, client_secret: str,
                 apps: signins.Apps | None = None, graph: str = GRAPH, login: str = LOGIN) -> None:
        self._http = client
        self._directory = directory_id
        self._id = client_id
        self._secret = client_secret
        self._apps = apps or signins.Apps.of(None)
        self._graph = graph.rstrip("/")
        self._login = login.rstrip("/")
        self._token: str | None = None
        self._expires = 0.0

    def _auth(self) -> dict[str, str]:
        if self._token is None or time.monotonic() > self._expires - 60:
            response = self._http.post(f"{self._login}/{self._directory}/oauth2/v2.0/token", data={
                "client_id": self._id, "client_secret": self._secret, "grant_type": "client_credentials",
                "scope": f"{self._graph}/.default"})
            response.raise_for_status()
            body = response.json()
            self._token = body["access_token"]
            self._expires = time.monotonic() + float(body.get("expires_in", 300))
        return {"Authorization": f"Bearer {self._token}"}

    def _all(self, path: str, params: dict | None) -> list[dict]:
        rows: list[dict] = []
        url: str | None = f"{self._graph}{path}"
        while url:
            response = self._http.get(url, params=params, headers=self._auth())
            response.raise_for_status()
            body = response.json()
            rows += body.get("value", [])
            url, params = body.get("@odata.nextLink"), None
        return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        skus = {s["skuId"]: s for s in self._all("/v1.0/subscribedSkus", None)}
        users = self._all("/v1.0/users", {"$select": ",".join(USER_FIELDS), "$top": 999})
        accounts, held = [], []
        for u in users:
            activity = u.get("signInActivity") or {}
            last = signins.moment(activity.get("lastSuccessfulSignInDateTime")
                                  or activity.get("lastSignInDateTime"))
            email = u.get("mail") or u.get("userPrincipalName")
            accounts.append(signins.Account(email, u.get("displayName") or "", bool(u.get("accountEnabled", True)),
                                            last, u.get("department"), str(u.get("id"))))
            held.append((email, [lic.get("skuId") for lic in u.get("assignedLicenses") or []], last,
                         bool(u.get("accountEnabled", True))))
        start = signins.since(cursor).isoformat().replace("+00:00", "Z")
        logins: dict[tuple[str, str], datetime] = {}
        latest: datetime | None = None
        for s in self._all("/v1.0/auditLogs/signIns", {"$filter": f"createdDateTime ge {start} and status/errorCode "
                                                                  "eq 0", "$top": 999}):
            seen = signins.keep_latest(logins, s.get("userPrincipalName"), s.get("appDisplayName"),
                                       signins.moment(s.get("createdDateTime")))
            if seen is not None and (latest is None or seen > latest):
                latest = seen
        out = signins.batch(tenant, self.name, accounts, logins, self._apps, None, cursor, latest)
        people = {e.payload.get("email"): e for e in out.entities if e.type.value == "EMPLOYEE"}
        for sku in skus.values():
            seats = (sku.get("prepaidUnits") or {}).get("enabled")
            out.entities.append(entities.subscription(tenant, VENDOR, sku.get("skuPartNumber") or sku["skuId"],
                                                      seats, self.name))
        for email, assigned, last, enabled in held:
            person = people.get((email or "").lower())
            if person is None or not enabled:
                continue
            for sku_id in assigned:
                sku = skus.get(sku_id)
                if sku is not None:
                    out.entities.append(entities.license_assignment(
                        tenant, VENDOR, sku.get("skuPartNumber") or sku_id, person,
                        last.date() if last else None, self.name))
        return out
