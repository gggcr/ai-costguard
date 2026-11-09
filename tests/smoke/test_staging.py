"""Smoke test after a deploy (ADR 0029): the paths a customer uses first, on a running environment.

    SMOKE_CORE_URL=https://api.staging... SMOKE_GATEWAY_URL=https://gw.staging... \
    SMOKE_KEYCLOAK_URL=https://id.staging.../realms/costguard SMOKE_USERNAME=... SMOKE_PASSWORD=... \
    uv run pytest tests/smoke

Without SMOKE_CORE_URL it is skipped. The workflow `deploy-manifests` runs it against staging once Argo CD has synced
the commit. It works in a company of its own (the smoke user's tenant, docs/runbooks/smoke.md) and leaves its agent
suspended and its key revoked; names carry the run id, so runs never collide.

1. A person signs in (Keycloak) and the core answers with their company.
2. They create a mandate, an agent, its key and an agent budget.
3. The agent spends through the gateway: the reserve, then the settle; the ledger and the journal get the charge.
4. They upload an Excel invoice with the same line twice; the pipeline reads it, they confirm the columns.
5. The anomaly detector finds the double charge and the finding appears in the core.
"""

from __future__ import annotations

import io
import os
import time
import uuid
from decimal import Decimal

import httpx
import pytest

pytestmark = pytest.mark.skipif(not os.environ.get("SMOKE_CORE_URL"), reason="SMOKE_CORE_URL is not set")

CORE = os.environ.get("SMOKE_CORE_URL", "")
GATEWAY = os.environ.get("SMOKE_GATEWAY_URL", "")
KEYCLOAK = os.environ.get("SMOKE_KEYCLOAK_URL", "")
CLIENT = os.environ.get("SMOKE_CLIENT_ID", "costguard-smoke")
TIMEOUT = float(os.environ.get("SMOKE_TIMEOUT_SECONDS", "300"))
RUN = os.environ.get("SMOKE_RUN_ID") or uuid.uuid4().hex[:8]
VENDOR = f"Smoke Shop {RUN}"


def wait(what: str, check, timeout: float = TIMEOUT, every: float = 3):
    deadline = time.monotonic() + timeout
    last = None
    while True:
        try:
            result = check()
        except httpx.TransportError as e:  # a pod rolling over
            result, last = None, repr(e)
        if result:
            return result
        if time.monotonic() > deadline:
            pytest.fail(f"Timed out after {timeout:.0f}s waiting for {what} (last: {last})")
        time.sleep(every)


@pytest.fixture(scope="module")
def core() -> httpx.Client:
    token = httpx.post(f"{KEYCLOAK}/protocol/openid-connect/token", timeout=30, data={
        "grant_type": "password", "client_id": CLIENT, "username": os.environ["SMOKE_USERNAME"],
        "password": os.environ["SMOKE_PASSWORD"]})
    assert token.status_code == 200, f"sign-in failed: {token.status_code}"
    return httpx.Client(base_url=CORE, timeout=30,
                        headers={"Authorization": f"Bearer {token.json()['access_token']}"})


def ok(response: httpx.Response) -> dict:
    assert response.status_code in (200, 201, 202), f"{response.request.method} {response.request.url.path}: " \
                                                    f"{response.status_code} {response.text[:300]}"
    return response.json()


@pytest.fixture(scope="module")
def agent(core):
    """A mandate, an employee to own the agent, the agent, its key and budgets down to it."""
    ok(core.put("/api/v1/company", json={"name": "CostGuard smoke", "currency": "RUB"})) \
        if core.get("/api/v1/company").status_code == 404 else None
    mandate = ok(core.post("/api/v1/mandates", json={
        "name": f"smoke {RUN}", "models": ["smoke"], "vendors": [VENDOR], "maxPerOperation": 5000,
        "approvalThreshold": 4000, "hours": "круглосуточно"}))["id"]
    ok(core.put(f"/api/v1/mandates/{mandate}/publication"))
    departments = ok(core.get("/api/v1/departments"))
    department = next((d["id"] for d in departments if d["name"] == "Smoke"), None) \
        or ok(core.post("/api/v1/departments", json={"name": "Smoke"}))["id"]
    owner = next((e["id"] for e in ok(core.get("/api/v1/employees")) if e["email"] == "smoke-owner@costguard.example"),
                 None) or ok(core.post("/api/v1/employees", json={
                     "name": "Smoke Owner", "email": "smoke-owner@costguard.example", "departmentId": department,
                     "position": "Smoke"}))["id"]
    agent = ok(core.post("/api/v1/agents", json={
        "name": f"smoke-{RUN}", "description": "post-deploy smoke test", "framework": "SDK",
        "departmentId": department, "ownerId": owner, "mandateId": mandate}))["id"]
    issued = ok(core.post(f"/api/v1/agents/{agent}/keys"))
    budgets = ok(core.get("/api/v1/budgets"))
    company = next((b["id"] for b in budgets if b["level"] == "company"), None) or ok(core.post(
        "/api/v1/budgets", json={"name": "smoke company", "level": "company", "limit": 1_000_000,
                                 "currency": "RUB"}))["id"]
    dept_budget = next((b["id"] for b in budgets if b["level"] == "department" and b.get("refId") == department),
                       None) or ok(core.post("/api/v1/budgets", json={
                           "name": "smoke department", "level": "department", "refId": department,
                           "parentId": company, "limit": 100_000, "currency": "RUB"}))["id"]
    budget = ok(core.post("/api/v1/budgets", json={
        "name": f"smoke {RUN}", "level": "agent", "refId": agent, "parentId": dept_budget, "limit": 3000,
        "currency": "RUB"}))["id"]
    yield {"id": agent, "key": issued["secret"], "key_id": issued["key"]["id"], "budget": budget}
    # Leave nothing that can spend.
    core.delete(f"/api/v1/agents/{agent}/keys/{issued['key']['id']}")
    core.put(f"/api/v1/agents/{agent}/suspension")


def test_1_a_person_signs_in_and_sees_their_company(core):
    assert ok(core.get("/api/v1/company"))["id"]


def test_2_and_3_an_agent_reserves_and_settles_through_the_gateway(core, agent):
    from costguard import CostGuard, Refused

    cg = CostGuard(agent["key"], base_url=GATEWAY)

    def authorized():
        try:
            return cg.authorize(VENDOR, 1290, product="smoke", task_id=f"smoke-{RUN}")
        except Refused as e:
            if e.type in ("invalid_api_key", "budget_exceeded", "policy_denied"):
                return None  # the gateway has not read the agent, its mandate or its budget from Kafka yet
            raise

    op = wait("the gateway to know the new agent", authorized)
    # The reserve holds the money: what is left of 3000 cannot take 2000 more.
    with pytest.raises(Refused) as over:
        cg.authorize(VENDOR, 2000)
    assert over.value.type == "budget_exceeded"
    op.settle(1100)

    entries = wait("the charge in the ledger", lambda: [
        e for e in ok(core.get(f"/api/v1/budgets/{agent['budget']}/entries"))["items"] if e["kind"] == "charge"])
    assert sum(Decimal(str(e["amount"])) for e in entries) == Decimal("1100")
    receipt = wait("the receipt", lambda: (
        r.json() if (r := core.get(f"/api/v1/operations/{op.authorization_id}/receipt")).status_code == 200 else None))
    assert receipt["hash"] and receipt["signature"]


def excel() -> bytes:
    from openpyxl import Workbook

    book = Workbook()
    sheet = book.active
    sheet.append(["Поставщик", "Сумма", "Дата"])
    day = time.strftime("%d.%m.%Y", time.gmtime())
    sheet.append([VENDOR, 4321, day])
    sheet.append([VENDOR, 4321, day])
    out = io.BytesIO()
    book.save(out)
    return out.getvalue()


def test_4_and_5_an_uploaded_excel_is_read_and_its_double_charge_becomes_a_finding(core):
    job = ok(core.post("/api/v1/imports", files={"file": (f"smoke-{RUN}.xlsx", excel(),
                                                          "application/vnd.openxmlformats-officedocument."
                                                          "spreadsheetml.sheet")}))
    job = wait("the pipeline to read the file", lambda: (
        j if (j := ok(core.get(f"/api/v1/imports/{job['id']}")))["status"] in ("mapping", "failed") else None))
    assert job["status"] == "mapping", job.get("failure")
    assert set(job["mapping"].values()) >= {"vendor", "amount"}, job["mapping"]
    ok(core.put(f"/api/v1/imports/{job['id']}/mapping", json={"mapping": job["mapping"]}))
    wait("the rows to be accepted", lambda: ok(core.get(f"/api/v1/imports/{job['id']}"))["status"] == "done")

    finding = wait("the double charge to become a finding", lambda: next((
        f for f in ok(core.get("/api/v1/findings")) if f["rule"] == "anomaly.double-charge" and RUN in f["title"]),
        None))
    assert Decimal(str(finding["expectedSavings"])) == Decimal("4321")
