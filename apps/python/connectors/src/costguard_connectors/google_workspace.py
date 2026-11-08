"""Google Workspace: users and their last login from the Admin SDK Directory API, and sign-ins to services behind
Google single sign-on (SAML apps) from the Reports API.

A service account with domain-wide delegation acts as an admin (`admin`) with two read-only scopes; its JSON key
comes from the environment. Every active user holds a Google Workspace seat, last used at lastLoginTime.
"""

from __future__ import annotations

import base64
import json
import time
import uuid
from datetime import datetime

import httpx

from costguard_connectors import signins
from costguard_connectors.contract import Batch

TOKEN_URL = "https://oauth2.googleapis.com/token"
API = "https://admin.googleapis.com"
SCOPES = ("https://www.googleapis.com/auth/admin.directory.user.readonly "
          "https://www.googleapis.com/auth/admin.reports.audit.readonly")
OWN = ("Google", "Google Workspace")


def _b64(data: bytes) -> str:
    return base64.urlsafe_b64encode(data).rstrip(b"=").decode()


def assertion(key: dict, subject: str, now: float) -> str:
    """The signed JWT a service account trades for a token (RS256)."""
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import padding

    header = _b64(json.dumps({"alg": "RS256", "typ": "JWT", "kid": key.get("private_key_id", "")}).encode())
    claims = _b64(json.dumps({"iss": key["client_email"], "sub": subject, "scope": SCOPES,
                              "aud": key.get("token_uri", TOKEN_URL), "iat": int(now),
                              "exp": int(now) + 3600}).encode())
    private = serialization.load_pem_private_key(key["private_key"].encode(), password=None)
    signature = private.sign(f"{header}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())
    return f"{header}.{claims}.{_b64(signature)}"


class GoogleWorkspace:
    name = "google_workspace"

    def __init__(self, client: httpx.Client, key: dict, admin: str, customer: str = "my_customer",
                 apps: signins.Apps | None = None, api: str = API) -> None:
        """key is the service account's JSON key; admin is the user it acts as. URLs are absolute."""
        self._http = client
        self._key = key
        self._admin = admin
        self._customer = customer
        self._apps = apps or signins.Apps.of(None)
        self._api = api.rstrip("/")
        self._token: str | None = None
        self._expires = 0.0

    def _auth(self) -> dict[str, str]:
        if self._token is None or time.time() > self._expires - 60:
            response = self._http.post(self._key.get("token_uri", TOKEN_URL), data={
                "grant_type": "urn:ietf:params:oauth:grant-type:jwt-bearer",
                "assertion": assertion(self._key, self._admin, time.time())})
            response.raise_for_status()
            body = response.json()
            self._token = body["access_token"]
            self._expires = time.time() + float(body.get("expires_in", 3600))
        return {"Authorization": f"Bearer {self._token}"}

    def _pages(self, path: str, params: dict, key: str) -> list[dict]:
        rows: list[dict] = []
        token = None
        while True:
            response = self._http.get(f"{self._api}{path}", params={**params, **({"pageToken": token} if token
                                                                                  else {})},
                                      headers=self._auth())
            response.raise_for_status()
            body = response.json()
            rows += body.get(key, [])
            token = body.get("nextPageToken")
            if not token:
                return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        users = self._pages("/admin/directory/v1/users", {"customer": self._customer, "maxResults": 500,
                                                           "projection": "basic"}, "users")
        accounts = [signins.Account(u.get("primaryEmail"), (u.get("name") or {}).get("fullName") or "",
                                    not u.get("suspended", False) and not u.get("archived", False),
                                    signins.moment(u.get("lastLoginTime")), u.get("orgUnitPath"), str(u.get("id")))
                    for u in users]
        start = signins.since(cursor)
        logins: dict[tuple[str, str], datetime] = {}
        latest: datetime | None = None
        events = self._pages("/admin/reports/v1/activity/users/all/applications/saml",
                             {"eventName": "login_success", "startTime": start.isoformat().replace("+00:00", "Z"),
                              "maxResults": 1000}, "items")
        for item in events:
            at = signins.moment((item.get("id") or {}).get("time"))
            email = (item.get("actor") or {}).get("email")
            for event in item.get("events", []):
                app = next((p.get("value") for p in event.get("parameters", [])
                            if p.get("name") == "application_name"), None)
                seen = signins.keep_latest(logins, email, app, at)
                if seen is not None and (latest is None or seen > latest):
                    latest = seen
        return signins.batch(tenant, self.name, accounts, logins, self._apps, OWN, cursor, latest)
