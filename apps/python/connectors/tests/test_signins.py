"""Google Workspace, Okta and Entra ID: people, the provider's own seats and the last sign-in to every service."""

import base64
import io
import json
import uuid
from datetime import UTC, date, datetime
from urllib.parse import parse_qs

import fastavro
import httpx
import pytest
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding, rsa

from costguard_common import normalized, schemas
from costguard_connectors import contract, entra, google_workspace, okta, signins

TENANT = uuid.UUID("11111111-1111-1111-1111-111111111111")
NOW = datetime(2026, 11, 8, tzinfo=UTC)


def http(routes) -> httpx.Client:
    """routes: path suffix -> body, list of bodies (one per call) or callable(request)."""
    seen: list[httpx.Request] = []
    calls: dict[str, int] = {}

    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        for path, body in routes.items():
            if request.url.path.endswith(path):
                if callable(body):
                    value = body(request)
                elif isinstance(body, list) and body and isinstance(body[0], httpx.Response):
                    value = body[min(calls.get(path, 0), len(body) - 1)]
                    calls[path] = calls.get(path, 0) + 1
                else:
                    value = body
                return value if isinstance(value, httpx.Response) else httpx.Response(200, json=value)
        return httpx.Response(404, json={"error": request.url.path})

    c = httpx.Client(transport=httpx.MockTransport(handle))
    c.seen = seen
    return c


def licenses(batch):
    return sorted((e.payload["vendor"], e.payload["product"], e.payload["employee"], e.payload["last_active"])
                  for e in batch.entities if e.type == normalized.EntityType.LICENSE_ASSIGNMENT)


def seats(batch):
    return {(e.payload["vendor"], e.payload["product"]): e.payload["seats"]
            for e in batch.entities if e.type == normalized.EntityType.SUBSCRIPTION}


def all_fit(name, batch):
    for m in contract.messages(TENANT, name, batch, now=NOW):
        out = io.BytesIO()
        fastavro.schemaless_writer(out, schemas.parsed(m.topic), m.value)


# shared


def test_apps_config_renames_and_ignores():
    apps = signins.Apps.of({"apps": {"Slack": ["Slack Technologies", "Slack Business+"]}, "ignore": ["Портал"]})
    assert apps.product(" slack ") == ("Slack Technologies", "Slack Business+")
    assert apps.product("Портал") is None
    assert apps.product("Тучка CRM") == ("Тучка CRM", "Тучка CRM")


def test_cursor_and_lookback():
    assert signins.since(None, NOW) == datetime(2026, 8, 10, tzinfo=UTC)
    cursor = signins.cursor_of(datetime(2026, 11, 1, 9, tzinfo=UTC), None)
    assert signins.since(cursor, NOW) == datetime(2026, 11, 1, 9, tzinfo=UTC)
    assert signins.cursor_of(None, "old") == "old"
    assert signins.moment("1970-01-01T00:00:00.000Z") is None


# Google Workspace


def service_key():
    private = rsa.generate_private_key(public_exponent=65537, key_size=2048)
    pem = private.private_bytes(serialization.Encoding.PEM, serialization.PrivateFormat.PKCS8,
                                serialization.NoEncryption()).decode()
    return private, {"client_email": "costguard@romashka-it.iam.example", "private_key": pem,
                     "private_key_id": "k1", "token_uri": "https://oauth2.example/token"}


def test_google_workspace_seats_logins_and_saml_apps():
    private, key = service_key()
    users = [{"pages": [
        {"users": [{"id": "1", "primaryEmail": "Ivanova@romashka.example", "name": {"fullName": "Иванова Анна"},
                    "suspended": False, "lastLoginTime": "2026-11-05T08:00:00.000Z", "orgUnitPath": "/Бухгалтерия"}],
         "nextPageToken": "p2"},
        {"users": [{"id": "2", "primaryEmail": "petrov@romashka.example", "name": {"fullName": "Петров Пётр"},
                    "suspended": True, "lastLoginTime": "1970-01-01T00:00:00.000Z"}]}]}]

    def directory(request):
        page = users[0]["pages"][1 if "pageToken=p2" in str(request.url) else 0]
        return page

    reports = {"items": [
        {"id": {"time": "2026-11-03T10:00:00.000Z"}, "actor": {"email": "ivanova@romashka.example"},
         "events": [{"name": "login_success", "parameters": [{"name": "application_name", "value": "Тучка CRM"}]}]},
        {"id": {"time": "2026-11-06T10:00:00.000Z"}, "actor": {"email": "ivanova@romashka.example"},
         "events": [{"name": "login_success", "parameters": [{"name": "application_name", "value": "Тучка CRM"}]}]},
        {"id": {"time": "2026-11-04T10:00:00.000Z"}, "actor": {"email": "ivanova@romashka.example"},
         "events": [{"name": "login_success", "parameters": [{"name": "application_name", "value": "Портал"}]}]},
    ]}
    tokens = []

    def token(request):
        form = parse_qs(request.content.decode())
        tokens.append(form["assertion"][0])
        return {"access_token": "ya29.x", "expires_in": 3600}

    client = http({"/token": token, "/admin/directory/v1/users": directory,
                   "/applications/saml": reports})
    connector = google_workspace.GoogleWorkspace(client, key, "admin@romashka.example",
                                                 apps=signins.Apps.of({"ignore": ["Портал"]}))
    batch = connector.pull(TENANT, None)

    assert licenses(batch) == [
        ("Google", "Google Workspace", "ivanova@romashka.example", date(2026, 11, 5)),
        ("Тучка CRM", "Тучка CRM", "ivanova@romashka.example", date(2026, 11, 6)),
    ]
    assert seats(batch) == {("Google", "Google Workspace"): 1}
    people = {e.payload["email"]: e.payload for e in batch.entities if e.type == normalized.EntityType.EMPLOYEE}
    assert people["petrov@romashka.example"]["active"] is False
    assert json.loads(batch.cursor)["since"] == "2026-11-06T10:00:00+00:00"
    saml = next(r for r in client.seen if "saml" in r.url.path)
    assert saml.headers["authorization"] == "Bearer ya29.x" and "eventName=login_success" in str(saml.url)

    header, claims, signature = tokens[0].split(".")
    pad = lambda s: base64.urlsafe_b64decode(s + "=" * (-len(s) % 4))  # noqa: E731
    body = json.loads(pad(claims))
    assert body["sub"] == "admin@romashka.example" and "admin.reports.audit.readonly" in body["scope"]
    private.public_key().verify(pad(signature), f"{header}.{claims}".encode(), padding.PKCS1v15(), hashes.SHA256())
    all_fit("google_workspace", batch)


# Okta


def test_okta_pages_by_link_and_reads_sso_events():
    page2 = "https://romashka.okta.example/api/v1/users?after=u2&limit=200"
    first = httpx.Response(200, json=[{"id": "u1", "status": "ACTIVE", "lastLogin": "2026-11-02T07:00:00.000Z",
                                       "profile": {"email": "ivanova@romashka.example", "firstName": "Анна",
                                                   "lastName": "Иванова", "department": "Бухгалтерия"}}],
                           headers={"Link": f'<{page2}>; rel="next"'})
    second = httpx.Response(200, json=[{"id": "u2", "status": "DEPROVISIONED", "lastLogin": None,
                                        "profile": {"login": "sidorov@romashka.example", "firstName": "Иван",
                                                    "lastName": "Сидоров"}}])
    logs = [{"published": "2026-11-04T12:00:00.000Z", "actor": {"alternateId": "ivanova@romashka.example"},
             "target": [{"type": "AppInstance", "displayName": "Slack"}, {"type": "AppUser"}]},
            {"published": "2026-11-05T12:00:00.000Z", "actor": {"alternateId": "nobody@romashka.example"},
             "target": [{"type": "AppInstance", "displayName": "Slack"}]}]
    client = http({"/api/v1/users": [first, second], "/api/v1/logs": logs})
    client.base_url = "https://romashka.okta.example"
    connector = okta.Okta(client, signins.Apps.of({"apps": {"Slack": ["Slack Technologies", "Slack Pro"]}}))
    batch = connector.pull(TENANT, signins.cursor_of(datetime(2026, 11, 1, tzinfo=UTC), None))

    assert licenses(batch) == [("Okta", "Okta", "ivanova@romashka.example", date(2026, 11, 2)),
                               ("Slack Technologies", "Slack Pro", "ivanova@romashka.example", date(2026, 11, 4))]
    assert seats(batch) == {("Okta", "Okta"): 1}
    log_call = next(r for r in client.seen if r.url.path.endswith("/logs"))
    query = parse_qs(log_call.url.query.decode())
    assert query["since"] == ["2026-11-01T00:00:00Z"] and "user.authentication.sso" in query["filter"][0]
    assert str(client.seen[1].url) == page2
    assert json.loads(batch.cursor)["since"].startswith("2026-11-05")  # a sign-in of an unknown account counts
    all_fit("okta", batch)


# Entra ID


def test_entra_microsoft_licenses_and_app_sign_ins():
    skus = {"value": [{"skuId": "s-e3", "skuPartNumber": "SPE_E3", "prepaidUnits": {"enabled": 25}},
                      {"skuId": "s-vis", "skuPartNumber": "VISIOCLIENT", "prepaidUnits": {"enabled": 2}}]}
    users = {"value": [
        {"id": "a", "displayName": "Иванова Анна", "mail": "ivanova@romashka.example", "accountEnabled": True,
         "assignedLicenses": [{"skuId": "s-e3"}, {"skuId": "s-vis"}],
         "signInActivity": {"lastSuccessfulSignInDateTime": "2026-11-07T09:00:00Z"}},
        {"id": "b", "displayName": "Сидоров Иван", "userPrincipalName": "sidorov@romashka.example",
         "accountEnabled": False, "assignedLicenses": [{"skuId": "s-e3"}], "signInActivity": {}}],
        "@odata.nextLink": "https://graph.example/v1.0/users?$skiptoken=2"}
    page2 = {"value": [{"id": "c", "displayName": "Козлова Мария", "mail": "kozlova@romashka.example",
                        "accountEnabled": True, "assignedLicenses": [{"skuId": "s-vis"}],
                        "signInActivity": {"lastSignInDateTime": "2026-06-01T09:00:00Z"}}]}
    sign_ins = {"value": [{"userPrincipalName": "ivanova@romashka.example", "appDisplayName": "Тучка CRM",
                           "createdDateTime": "2026-11-06T15:00:00Z"}]}

    def users_page(request):
        return page2 if "skiptoken" in str(request.url) else users

    client = http({"/oauth2/v2.0/token": {"access_token": "g", "expires_in": 3600}, "/v1.0/subscribedSkus": skus,
                   "/v1.0/users": users_page, "/auditLogs/signIns": sign_ins})
    batch = entra.Entra(client, "dir", "app", "secret", graph="https://graph.example",
                        login="https://login.example").pull(TENANT, None)

    assert licenses(batch) == [
        ("Microsoft", "SPE_E3", "ivanova@romashka.example", date(2026, 11, 7)),
        ("Microsoft", "VISIOCLIENT", "ivanova@romashka.example", date(2026, 11, 7)),
        ("Microsoft", "VISIOCLIENT", "kozlova@romashka.example", date(2026, 6, 1)),
        ("Тучка CRM", "Тучка CRM", "ivanova@romashka.example", date(2026, 11, 6)),
    ]
    assert seats(batch) == {("Microsoft", "SPE_E3"): 25, ("Microsoft", "VISIOCLIENT"): 2}
    filter_ = parse_qs(next(r for r in client.seen if "signIns" in r.url.path).url.query.decode())["$filter"][0]
    assert filter_.startswith("createdDateTime ge ") and "errorCode eq 0" in filter_
    all_fit("entra", batch)


def test_a_provider_that_refuses_stops_the_pull():
    client = http({"/oauth2/v2.0/token": httpx.Response(401, json={"error": "invalid_client"})})
    with pytest.raises(httpx.HTTPStatusError):
        entra.Entra(client, "dir", "app", "bad").pull(TENANT, None)
