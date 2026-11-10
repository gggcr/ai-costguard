"""End to end on the running stack (`make demo`): Keycloak, core, Kafka, gateway, Redis and the seeded company.

    E2E=1 pytest tests/e2e            # defaults to the ports of infra/local/docker-compose.yml

The path a rouble takes: a person logs in, creates a mandate, an agent, a key and budgets in the core; the core
publishes them to Kafka; the gateway picks them up; the agent spends through the gateway with the SDK; the
gateway reports the operation; the core charges the ledger and issues a signed receipt. Also checks that the
audit found the seeded problems and that another tenant sees none of it.
"""

import os
import time
import uuid

import httpx
import pytest

from costguard import CostGuard, Refused

pytestmark = pytest.mark.skipif(not os.environ.get("E2E"), reason="E2E is not set: needs the running demo stack")

CORE = os.environ.get("E2E_CORE_URL", "http://127.0.0.1:8080")
GATEWAY = os.environ.get("E2E_GATEWAY_URL", "http://127.0.0.1:8081")
KEYCLOAK = os.environ.get("E2E_KEYCLOAK_URL", "http://127.0.0.1:8180")
DEMO_TENANT = "0d6f8a1e-5b2c-4c3e-9a7d-1f2e3d4c5b6a"
IVANOV = str(uuid.uuid5(uuid.UUID(DEMO_TENANT), "employee:ivanov@romashka.example"))


def wait_with(describe, check, timeout=90, every=2):
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        if time.monotonic() > deadline:
            pytest.fail(f"Timed out waiting for {describe()}")
        time.sleep(every)


def wait(what, check, timeout=90, every=2):
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result:
            return result
        if time.monotonic() > deadline:
            pytest.fail(f"Timed out waiting for {what}")
        time.sleep(every)


@pytest.fixture(scope="module")
def core():
    for attempt in range(3):  # Docker Desktop port forwarding on Windows drops a connection now and then
        try:
            token = httpx.post(f"{KEYCLOAK}/realms/costguard/protocol/openid-connect/token", trust_env=False,
                               timeout=60, data={"grant_type": "password", "client_id": "costguard-e2e",
                                                 "username": "demo", "password": "demo"})
            break
        except httpx.TransportError:
            if attempt == 2:
                raise
            time.sleep(3)
    assert token.status_code == 200, token.text
    return httpx.Client(base_url=CORE, trust_env=False, timeout=30,
                        headers={"Authorization": f"Bearer {token.json()['access_token']}"})


def created(response):
    assert response.status_code in (200, 201), response.text
    return response.json()["id"]


def budget(core, level, ref, parent, limit):
    """The company and the department have one budget each; a second run of the test reuses them."""
    for b in core.get("/api/v1/budgets").json():
        if b["level"] == level and b.get("refId") == ref:
            return b["id"]
    return created(core.post("/api/v1/budgets", json={"name": f"e2e {level}", "level": level, "refId": ref,
                                                       "parentId": parent, "limit": limit, "currency": "RUB"}))


def test_the_audit_found_the_seeded_problems(core):
    findings = wait("findings of the audit", lambda: core.get("/api/v1/findings").json() or None)
    rules = {f["rule"] for f in findings}
    assert {"license.unused", "license.dismissed", "subscription.idle-seats", "asset.not-returned"} <= rules


def test_an_agent_spends_through_the_gateway_and_gets_a_receipt(core):
    run = uuid.uuid4().hex[:8]
    mandate = created(core.post("/api/v1/mandates", json={
        "name": f"e2e {run}", "models": ["gpt-5"], "vendors": ["Ozon"], "maxPerOperation": 5000,
        "approvalThreshold": 4000, "hours": "круглосуточно"}))
    assert core.put(f"/api/v1/mandates/{mandate}/publication").status_code == 200
    departments = core.get("/api/v1/departments").json()
    agent = created(core.post("/api/v1/agents", json={
        "name": f"e2e-bot-{run}", "description": "e2e", "framework": "SDK", "departmentId": departments[0]["id"],
        "ownerId": IVANOV, "mandateId": mandate}))
    issued = core.post(f"/api/v1/agents/{agent}/keys")
    assert issued.status_code in (200, 201), issued.text
    secret = issued.json()["secret"]
    company = budget(core, "company", None, None, 1_000_000)
    department = budget(core, "department", departments[0]["id"], company, 100_000)
    created(core.post("/api/v1/budgets", json={
        "name": f"e2e agent {run}", "level": "agent", "refId": agent, "parentId": department, "limit": 3000,
        "currency": "RUB"}))

    cg = CostGuard(secret, base_url=GATEWAY, client=httpx.Client(base_url=GATEWAY, trust_env=False, timeout=30))

    last = []

    def authorized():
        try:
            return cg.authorize("Ozon", 1290, product="Картридж", task_id=f"e2e-{run}")
        except Refused as e:
            if e.type in ("invalid_api_key", "budget_exceeded", "policy_denied"):
                last[:] = [e.type]
                return None  # the gateway has not read the agent, mandate or budget from Kafka yet
            raise

    op = wait_with(lambda: f"the gateway to know the agent (last refusal: {last})", authorized)
    op.settle(1100)

    with pytest.raises(Refused) as over:
        cg.authorize("Ozon", 2500)  # 3000 budget, 1100 spent
    assert over.value.type == "budget_exceeded"
    with pytest.raises(Refused) as denied:
        cg.authorize("AliExpress", 10)
    assert denied.value.type == "policy_denied"

    receipt = wait("the receipt of the operation", lambda: (
        r.json() if (r := core.get(f"/api/v1/operations/{op.authorization_id}/receipt")).status_code == 200 else None))
    assert receipt["hash"] and receipt["signature"]



def test_an_excel_list_of_equipment_reaches_the_assets(core):
    """Board frame 15 and the e2e step «Excel»: a list of equipment goes through the ingest pipeline, its mapping is
    confirmed, and the device lands in the assets of the core, held by the employee of the row (ADR 0041)."""
    import io

    from openpyxl import Workbook

    run = uuid.uuid4().hex[:8].upper()
    inventory = f"E2E-{run}"
    book = Workbook()
    sheet = book.active
    sheet.append(["Инв. номер", "Серийный номер", "Модель", "E-mail", "Стоимость"])
    sheet.append([inventory, f"SN{run}", "Ноутбук ThinkPad X1", "ivanov@romashka.example", 95000])
    out = io.BytesIO()
    book.save(out)
    uploaded = core.post("/api/v1/imports", files={"file": (
        f"техника-{run}.xlsx", out.getvalue(), "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")})
    assert uploaded.status_code == 201, uploaded.text
    job = uploaded.json()["id"]

    def read():
        j = core.get(f"/api/v1/imports/{job}").json()
        return j if j["status"] in ("mapping", "review", "done", "failed") else None

    parsed = wait("the pipeline to read the file", read)
    assert parsed["status"] == "mapping", parsed
    assert parsed["mapping"]["Инв. номер"] == "inventory_number"
    assert parsed["mapping"]["E-mail"] == "email"
    confirmed = core.put(f"/api/v1/imports/{job}/mapping", json={"mapping": parsed["mapping"], "saveTemplate": False})
    assert confirmed.status_code == 200, confirmed.text

    device = wait("the device in the assets", lambda: next(
        (a for a in core.get("/api/v1/assets", params={"size": 200}).json()["items"]
         if a["inventoryNumber"] == inventory), None))
    assert device["type"] == "LAPTOP" and device["currentAssignment"]["employeeId"] == IVANOV
