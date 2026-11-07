"""Microsoft Intune through Microsoft Graph: the company's managed laptops and phones and their primary users.

An app registration in Entra ID with the application permission DeviceManagementManagedDevices.Read.All gets a
token by client credentials. Devices come from /v1.0/deviceManagement/managedDevices, following @odata.nextLink.

Only company-owned devices are company equipment: a personal phone enrolled for mail (BYOD) is not reported. Graph
v1.0 does not say whether a Windows PC is a laptop, so a model whose name does not say so is OTHER: a guessed
laptop would raise a «two laptops» finding for someone with a desktop and a laptop.
"""

from __future__ import annotations

import time
import uuid

import httpx

from costguard_connectors import entities
from costguard_connectors.contract import Batch

GRAPH = "https://graph.microsoft.com"
LOGIN = "https://login.microsoftonline.com"
FIELDS = ("id", "deviceName", "model", "manufacturer", "serialNumber", "operatingSystem", "emailAddress",
          "userPrincipalName", "userDisplayName", "managedDeviceOwnerType")

_LAPTOP_HINTS = ("laptop", "notebook", "macbook", "thinkpad", "latitude", "elitebook", "probook", "zbook",
                 "xps", "zenbook", "vivobook", "expertbook", "ideapad", "yoga", "inspiron", "vostro", "matebook",
                 "surface pro", "surface go", "precision 5", "precision 7", "spectre", "envy x360", "swift",
                 "travelmate", "galaxy book")
_TABLET_HINTS = ("ipad", "tab", "tablet")


def kind_of(operating_system: str | None, model: str | None) -> str:
    system, name = (operating_system or "").lower(), (model or "").lower()
    if system in ("ios", "ipados", "android", "androidforwork", "androidenterprise"):
        return "OTHER" if any(h in name for h in _TABLET_HINTS) else "PHONE"
    if any(h in name for h in _LAPTOP_HINTS):
        return "LAPTOP"
    return "OTHER"


class Intune:
    name = "intune"

    def __init__(self, client: httpx.Client, directory_id: str, client_id: str, client_secret: str,
                 graph: str = GRAPH, login: str = LOGIN) -> None:
        """directory_id is the Entra ID tenant (directory) id; client is any httpx client, URLs are absolute."""
        self._http = client
        self._directory = directory_id
        self._id = client_id
        self._secret = client_secret
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

    def _devices(self) -> list[dict]:
        rows: list[dict] = []
        url: str | None = f"{self._graph}/v1.0/deviceManagement/managedDevices"
        params: dict | None = {"$select": ",".join(FIELDS), "$top": 100}
        while url:
            response = self._http.get(url, params=params, headers=self._auth())
            response.raise_for_status()
            body = response.json()
            rows += body.get("value", [])
            url, params = body.get("@odata.nextLink"), None  # the next link carries its own query
        return rows

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        batch = Batch(cursor=cursor)
        for d in self._devices():
            if (d.get("managedDeviceOwnerType") or "").lower() != "company":
                continue
            model = " ".join(p for p in (d.get("manufacturer"), d.get("model")) if p) or d.get("deviceName")
            device = entities.asset(
                tenant, self.name, d.get("serialNumber"), None, model, kind_of(d.get("operatingSystem"), model),
                holder_email=d.get("emailAddress") or d.get("userPrincipalName"),
                holder_name=d.get("userDisplayName"), external_id=str(d.get("id")),
                device_name=d.get("deviceName"))
            if device is not None:
                batch.entities.append(device)
        return batch
