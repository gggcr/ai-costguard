"""The path of an employee on the running stack (`make demo` or `make demo-lite`), through Keycloak and the core.

    E2E=1 pytest tests/e2e/test_employee_path.py

The owner adds a person (ADR 0059) and gets a password; the person logs in with it, sees only their cabinet, gets a
free license from the pool, has a purchase refused because the company has the program, reports a purchase of their
own; the owner sees the saving (ADR 0061); the dismissal closes the account (ADR 0060). Without Microsoft 365
connected the address is typed: the mailbox itself is tested in the core's integration tests.
"""

import os
import time
import uuid

import httpx
import pytest

pytestmark = pytest.mark.skipif(not os.environ.get("E2E"), reason="E2E is not set: needs the running demo stack")

CORE = os.environ.get("E2E_CORE_URL", "http://127.0.0.1:8080")
KEYCLOAK = os.environ.get("E2E_KEYCLOAK_URL", "http://127.0.0.1:8180")


def token(username, password):
    for attempt in range(3):  # Docker Desktop port forwarding on Windows drops a connection now and then
        try:
            return httpx.post(f"{KEYCLOAK}/realms/costguard/protocol/openid-connect/token", trust_env=False,
                              timeout=60, data={"grant_type": "password", "client_id": "costguard-e2e",
                                                "username": username, "password": password})
        except httpx.TransportError:
            if attempt == 2:
                raise
            time.sleep(3)


def client(username, password):
    t = token(username, password)
    assert t.status_code == 200, t.text
    return httpx.Client(base_url=CORE, trust_env=False, timeout=30,
                        headers={"Authorization": f"Bearer {t.json()['access_token']}"})


def created(response):
    assert response.status_code in (200, 201), response.text
    return response.json()


@pytest.fixture(scope="module")
def owner():
    return client("demo", "demo")


def test_an_added_employee_works_in_their_cabinet_and_leaves(owner):
    run = uuid.uuid4().hex[:8]
    department = owner.get("/api/v1/departments").json()[0]["id"]
    added = created(owner.post("/api/v1/employees/onboarding", json={
        "firstName": "Анна", "lastName": f"Тестова{run}", "departmentId": department, "position": "Дизайнер",
        "email": f"anna.{run}@romashka.example", "createMailbox": False}))
    assert added["mailboxCreated"] is False
    email, password = added["email"], added["password"]
    assert len(password) == 14

    # a program of the company with a free seat
    vendor = created(owner.post("/api/v1/vendors", json={"name": f"Figma e2e {run}", "inn": "", "category": "SaaS"}))
    subscription = created(owner.post("/api/v1/subscriptions", json={
        "vendorId": vendor["id"], "product": f"Figma {run} Professional", "contractId": None,
        "departmentId": department, "seats": 3, "pricePerSeat": 1500, "billingPeriod": "month",
        "renewalDate": "2027-12-31"}))

    anna = client(email, password)
    me = anna.get("/api/v1/me")
    assert me.status_code == 200, me.text
    # the employee sees no company data
    assert anna.get("/api/v1/employees").status_code == 403

    granted = created(anna.post("/api/v1/me/license-requests", json={"subscriptionId": subscription["id"]}))
    assert granted["status"] == "granted"
    held = anna.get("/api/v1/me/licenses").json()["held"]
    assert any(h["product"] == f"Figma {run} Professional" for h in held)

    # a second seat of what the company has is not bought
    refused = anna.post("/api/v1/me/purchase-requests", data={
        "product": f"Figma {run} Professional", "amount": "1200", "period": "month", "reason": "e2e"},
        files={"file": ("чек.pdf", b"%PDF-1.4 e2e", "application/pdf")})
    assert refused.status_code == 201, refused.text
    assert refused.json()["status"] == "has_license"

    shadow = anna.post("/api/v1/me/shadow-spend", json={"service": "Midjourney", "amount": 1000,
                                                         "paidOn": "2026-11-01", "purpose": "e2e"})
    assert shadow.status_code == 201, shadow.text

    savings = owner.get("/api/v1/cabinet/savings").json()
    assert savings["purchasesRefused"] >= 1 and savings["totalMonthly"] > 0

    # dismissal: the account no longer logs in
    assert owner.put(f"/api/v1/employees/{added['employee']['id']}/dismissal").status_code == 200
    deadline = time.monotonic() + 60
    while token(email, password).status_code == 200:
        if time.monotonic() > deadline:
            pytest.fail("the account of the dismissed employee still logs in")
        time.sleep(2)
