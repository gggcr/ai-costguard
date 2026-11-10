"""Any service with a SCIM 2.0 API (RFC 7644): who has an account there, so who holds a seat.

Level 1 of the integrations on the board: a SaaS that provisions users over SCIM (Slack, Zoom, Notion, Miro, Figma,
GitHub, Atlassian…) lists them at /Users, and that list is the licenses of its subscription. A tenant config names
each service once:

    [[scim]]
    vendor = "Notion"
    product = "Notion Plus"
    url = "https://api.notion.com/scim/v2"
    token_env = "SCIM_NOTION_TOKEN"     # the bearer token, from the environment

Active users become employees (matched by e-mail in the core) and license assignments of that product; an account
switched off (active = false) is a person without a seat there, not a dismissal: SCIM says nothing about
employment. SCIM keeps no last sign-in, so the licenses carry no activity; sign-ins come from the identity provider.
Pages of 100 by startIndex, as the RFC says.
"""

from __future__ import annotations

import uuid

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

PAGE = 100


class Scim:
    def __init__(self, client: httpx.Client, vendor: str, product: str) -> None:
        """client carries base_url (…/scim/v2) and the bearer token of the service."""
        self._http = client
        self.vendor = vendor
        self.product = product
        self.name = f"scim:{vendor.strip().lower()}:{product.strip().lower()}"

    def _users(self) -> list[dict]:
        users: list[dict] = []
        start = 1
        while True:
            response = self._http.get("/Users", params={"startIndex": start, "count": PAGE},
                                      headers={"Accept": "application/scim+json, application/json"})
            response.raise_for_status()
            body = response.json()
            page = body.get("Resources") or []
            users += page
            total = int(body.get("totalResults", len(users)))
            if not page or len(users) >= total:
                return users
            start += len(page)

    @staticmethod
    def _email(user: dict) -> str | None:
        emails = user.get("emails") or []
        primary = next((e for e in emails if e.get("primary")), emails[0] if emails else None)
        value = (primary or {}).get("value") or user.get("userName")
        return value if value and "@" in value else None

    @staticmethod
    def _name(user: dict) -> str:
        name = user.get("name") or {}
        return (name.get("formatted") or " ".join(p for p in (name.get("familyName"), name.get("givenName")) if p)
                or user.get("displayName") or "")

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        batch = Batch(cursor=cursor)
        source = self.name
        batch.entities.append(entities.vendor(tenant, self.vendor, source=source))
        batch.entities.append(entities.subscription(tenant, self.vendor, self.product, source=source))
        for user in self._users():
            email = self._email(user)
            if email is None:
                continue  # a service account or a group mailbox has no person to tie a seat to
            active = bool(user.get("active", True))
            person = entities.employee(tenant, self._name(user), email, active=active, source=source)
            batch.entities.append(person)
            if active:
                batch.entities.append(entities.license_assignment(tenant, self.vendor, self.product, person, None,
                                                                  source=source))
        return batch
