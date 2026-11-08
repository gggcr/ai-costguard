"""What identity providers (Google Workspace, Okta, Microsoft Entra ID) say about people and services, as entities.

An identity provider knows every account and when it last signed in to each service behind single sign-on. For
the core that is a LICENSE_ASSIGNMENT per person and service with last_active: the «paid but not used» finding
(license.unused) compares it with the paid seats. The core binds an assignment only to a subscription it already
has, with a free seat, so a service nobody pays for creates no license.

A service is named as the provider names the app. `apps` in the connector config maps an app to the vendor and
product of the subscription ("Slack" = ["Slack Technologies", "Slack Business+"]), and `ignore` drops apps that
cost nothing (the company portal, internal tools).

The cursor is the time of the latest sign-in seen, so the next pull reads only newer events; a person who did not
sign in since keeps the last_active the core already has.
"""

from __future__ import annotations

import json
import uuid
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta

from costguard_common import normalized
from costguard_connectors import entities
from costguard_connectors.contract import Batch

LOOKBACK_DAYS = 90


@dataclass(frozen=True)
class Account:
    email: str | None
    name: str
    active: bool
    last_login: datetime | None = None
    department: str | None = None
    external_id: str | None = None


@dataclass(frozen=True)
class Apps:
    """Config of a connector: app name to [vendor, product], and apps to leave out."""

    names: dict[str, tuple[str, str]]
    ignore: frozenset[str]

    @staticmethod
    def of(config: dict | None) -> Apps:
        config = config or {}
        names = {k.lower(): (v[0], v[1] if len(v) > 1 else v[0]) for k, v in (config.get("apps") or {}).items()}
        return Apps(names, frozenset(a.lower() for a in config.get("ignore") or []))

    def product(self, app: str) -> tuple[str, str] | None:
        key = " ".join(app.split()).lower()
        if not key or key in self.ignore:
            return None
        return self.names.get(key, (" ".join(app.split()), " ".join(app.split())))


def moment(text: str | None) -> datetime | None:
    if not text:
        return None
    try:
        value = datetime.fromisoformat(text.replace("Z", "+00:00"))
    except ValueError:
        return None
    if value.year < 1980:  # Google reports 1970-01-01 for "never"
        return None
    return value if value.tzinfo else value.replace(tzinfo=UTC)


def since(cursor: str | None, now: datetime | None = None) -> datetime:
    if cursor:
        try:
            value = moment(json.loads(cursor).get("since"))
            if value is not None:
                return value
        except (ValueError, AttributeError):
            pass
    return (now or datetime.now(UTC)) - timedelta(days=LOOKBACK_DAYS)


def cursor_of(latest: datetime | None, previous: str | None) -> str | None:
    return json.dumps({"since": latest.isoformat()}) if latest is not None else previous


def batch(tenant: uuid.UUID, source: str, accounts: list[Account], logins: dict[tuple[str, str], datetime],
          apps: Apps, own: tuple[str, str] | None, cursor: str | None, latest: datetime | None) -> Batch:
    """Employees, a license of the provider itself (`own`, by the account's last login) and a license per service
    signed in to. logins: (email, app) to the latest sign-in."""
    out = Batch(cursor=cursor_of(latest, cursor))
    people: dict[str, normalized.Entity] = {}
    active = 0
    for a in accounts:
        person = entities.employee(tenant, a.name, a.email, a.department, a.active, source=source,
                                   **({"external_id": a.external_id} if a.external_id else {}))
        out.entities.append(person)
        if a.email:
            people[a.email.lower()] = person
        if own is not None and a.active:
            active += 1
            out.entities.append(entities.license_assignment(tenant, own[0], own[1], person,
                                                            a.last_login.date() if a.last_login else None, source))
    if own is not None:
        out.entities.append(entities.subscription(tenant, own[0], own[1], active, source))
    for (email, app), at in sorted(logins.items()):
        person = people.get(email.lower())
        product = apps.product(app)
        if person is None or product is None:
            continue
        out.entities.append(entities.license_assignment(tenant, product[0], product[1], person, at.date(), source))
    return out


def keep_latest(logins: dict[tuple[str, str], datetime], email: str | None, app: str | None,
                at: datetime | None) -> datetime | None:
    """Adds one sign-in; returns its time, for the cursor."""
    if not email or not app or at is None:
        return None
    key = (email.lower(), " ".join(app.split()))
    if key not in logins or at > logins[key]:
        logins[key] = at
    return at
