"""Okta: users and their last login from the Users API, and sign-ins to apps (user.authentication.sso) from the
System Log.

An API token of a read-only admin goes in the header `Authorization: SSWS <token>`; both APIs page with the
`Link: <…>; rel="next"` header. Every active user holds an Okta seat.
"""

from __future__ import annotations

import uuid
from datetime import datetime

import httpx

from costguard_connectors import signins
from costguard_connectors.contract import Batch

OWN = ("Okta", "Okta")
ACTIVE = {"ACTIVE", "PASSWORD_EXPIRED", "LOCKED_OUT", "RECOVERY"}


class Okta:
    name = "okta"

    def __init__(self, client: httpx.Client, apps: signins.Apps | None = None) -> None:
        """client carries base_url (https://<org>.okta.com) and the SSWS token header."""
        self._http = client
        self._apps = apps or signins.Apps.of(None)

    def _pages(self, path: str, params: dict) -> list[dict]:
        rows: list[dict] = []
        url: str | None = path
        query: dict | None = params
        while url:
            response = self._http.get(url, params=query)
            response.raise_for_status()
            rows += response.json()
            url, query = response.links.get("next", {}).get("url"), None  # the next link carries its own query
        return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        accounts = []
        for u in self._pages("/api/v1/users", {"limit": 200}):
            profile = u.get("profile") or {}
            name = " ".join(p for p in (profile.get("lastName"), profile.get("firstName")) if p)
            accounts.append(signins.Account(profile.get("email") or profile.get("login"), name,
                                            u.get("status") in ACTIVE, signins.moment(u.get("lastLogin")),
                                            profile.get("department"), str(u.get("id"))))
        logins: dict[tuple[str, str], datetime] = {}
        latest: datetime | None = None
        events = self._pages("/api/v1/logs", {
            "since": signins.since(cursor).isoformat().replace("+00:00", "Z"), "limit": 1000,
            "filter": 'eventType eq "user.authentication.sso" and outcome.result eq "SUCCESS"'})
        for event in events:
            app = next((t.get("displayName") for t in event.get("target") or [] if t.get("type") == "AppInstance"),
                       None)
            seen = signins.keep_latest(logins, (event.get("actor") or {}).get("alternateId"), app,
                                       signins.moment(event.get("published")))
            if seen is not None and (latest is None or seen > latest):
                latest = seen
        return signins.batch(tenant, self.name, accounts, logins, self._apps, OWN, cursor, latest)
