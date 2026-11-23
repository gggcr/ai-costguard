"""Walks marketing/DEMO_SCRIPT.md through the API of a running `make demo` stack, as Brightline Analytics Inc.

    python tools/demo/check_demo.py          # after `make demo`; exit code 0 when every step passed

Each step prints PASS or FAIL with what it saw. Nothing here is mocked: Keycloak issues the tokens, the core keeps
the data, the gateway decides over Kafka-delivered state, and the receipt comes back from the ledger. The run
creates a mandate, an agent, budgets and operations in the demo company, so it can be repeated; a fresh
`make down && make demo` resets everything.
"""

import os
import sys
import time
import uuid

import httpx

CORE = os.environ.get("DEMO_CORE_URL", "http://127.0.0.1:8080")
GATEWAY = os.environ.get("DEMO_GATEWAY_URL", "http://127.0.0.1:8081")
KEYCLOAK = os.environ.get("DEMO_KEYCLOAK_URL", "http://127.0.0.1:8180")
WEB = os.environ.get("DEMO_WEB_URL", "http://127.0.0.1:3000")
TOKEN_URL = f"{KEYCLOAK}/realms/costguard/protocol/openid-connect/token"

results: list[tuple[str, bool, str]] = []


def step(name: str, ok: bool, detail: str = "") -> bool:
    results.append((name, ok, detail))
    print(f"{'PASS' if ok else 'FAIL'}  {name}" + (f"  ({detail})" if detail else ""), flush=True)
    return ok


def wait(check, timeout=120, every=2):
    deadline = time.monotonic() + timeout
    while True:
        result = check()
        if result or time.monotonic() > deadline:
            return result
        time.sleep(every)


def token(username: str) -> dict:
    for attempt in range(3):  # Docker Desktop on Windows drops a forwarded connection now and then
        try:
            r = httpx.post(TOKEN_URL, trust_env=False, timeout=60, data={
                "grant_type": "password", "client_id": "costguard-e2e", "username": username, "password": "demo"})
            r.raise_for_status()
            return r.json()
        except httpx.TransportError:
            if attempt == 2:
                raise
            time.sleep(3)
    raise AssertionError("unreachable")


def claims(access_token: str) -> dict:
    import base64
    import json
    body = access_token.split(".")[1]
    return json.loads(base64.urlsafe_b64decode(body + "=" * (-len(body) % 4)))


def client(access_token: str, base: str = CORE) -> httpx.Client:
    return httpx.Client(base_url=base, trust_env=False, timeout=30,
                        headers={"Authorization": f"Bearer {access_token}"})


def authorize(key: str, amount: float, idempotency: str) -> httpx.Response:
    return httpx.post(f"{GATEWAY}/v1/spend/authorize", trust_env=False, timeout=30,
                      headers={"Authorization": f"Bearer {key}", "Idempotency-Key": idempotency},
                      json={"kind": "paid_api", "vendor": "Acme Data", "product": "Company report",
                            "amount": amount, "taskId": "demo-1"})


def main() -> int:
    run = uuid.uuid4().hex[:6]

    # --- Sign-in and the company
    owner_token = token("demo-us")["access_token"]
    aud = claims(owner_token).get("aud")
    step("demo-us signs in through Keycloak; the token is for costguard-api",
         "costguard-api" in (aud if isinstance(aud, list) else [aud]), f"aud={aud}")
    core = client(owner_token)
    company = core.get("/api/v1/company").json()
    step("the company is Brightline Analytics Inc. in US dollars, English",
         company.get("name") == "Brightline Analytics Inc." and company.get("currency") == "USD"
         and str(company.get("language", "")).upper() == "EN",
         f"{company.get('name')}, {company.get('currency')}, {company.get('language')}")

    # --- Track B: the audit
    def listed(path):
        body = core.get(path).json()
        return body.get("items", []) if isinstance(body, dict) else body

    findings = wait(lambda: (lambda f: f if len(f) >= 5 else None)(listed("/api/v1/findings")), timeout=150)
    titles = [f.get("title", "") for f in (findings or [])]
    step("the audit found the seeded waste by itself", bool(findings) and len(findings) >= 5,
         f"{len(titles)} findings")
    cyrillic = [t for t in titles if any("а" <= ch.lower() <= "я" for ch in t)]
    step("finding titles are in English", not cyrillic, "; ".join(cyrillic[:2]) or "all English")
    step("the leaver's Sales Cloud license is a finding", "License of a leaver: Salesforce Sales Cloud" in titles)

    # --- Track A: mandate, agent, key, budgets
    mandate = core.post("/api/v1/mandates", json={
        "name": f"Paid APIs {run}", "models": ["gpt-5"], "vendors": ["Acme Data"], "maxPerOperation": 300,
        "approvalThreshold": 100, "hours": "круглосуточно"})
    ok = step("a mandate: paid APIs up to $300, approval above $100", mandate.status_code in (200, 201), mandate.text[:120])
    if not ok:
        return summary()
    mandate_id = mandate.json()["id"]
    step("the mandate is published", core.put(f"/api/v1/mandates/{mandate_id}/publication").status_code == 200)
    departments = core.get("/api/v1/departments").json()
    engineering = next((d for d in departments if d.get("name") == "Engineering"), departments[0])
    people = listed("/api/v1/employees")
    agent = core.post("/api/v1/agents", json={
        "name": f"Research agent {run}", "description": "demo", "framework": "SDK",
        "departmentId": engineering["id"], "ownerId": people[0]["id"], "mandateId": mandate_id})
    if not step("an agent is connected", agent.status_code in (200, 201), agent.text[:120]):
        return summary()
    agent_id = agent.json()["id"]
    issued = core.post(f"/api/v1/agents/{agent_id}/keys")
    key = issued.json().get("secret", "") if issued.status_code in (200, 201) else ""
    step("a key is issued", key.startswith("cg_live_"), f"{key[:12]}…")

    def budget(level, ref, parent, limit):
        for b in core.get("/api/v1/budgets").json():
            if b["level"] == level and b.get("refId") == ref:
                return b["id"]
        r = core.post("/api/v1/budgets", json={"name": f"demo {level}", "level": level, "refId": ref,
                                               "parentId": parent, "limit": limit, "currency": "USD"})
        assert r.status_code in (200, 201), r.text
        return r.json()["id"]

    company_budget = budget("company", None, None, 100_000)
    department_budget = budget("department", engineering["id"], company_budget, 20_000)
    r = core.post("/api/v1/budgets", json={"name": f"Research agent {run}", "level": "agent", "refId": agent_id,
                                           "parentId": department_budget, "limit": 200, "currency": "USD"})
    step("a monthly limit of $200 for the agent", r.status_code in (200, 201), r.text[:120])

    # --- The gateway
    first = wait(lambda: (lambda x: x if x.status_code == 200 else None)(authorize(key, 40, str(uuid.uuid4()))),
                 timeout=120)
    step("the agent buys a $40 report: authorized", first is not None and first.status_code == 200,
         "the gateway learned the agent, mandate and budget from Kafka" if first is not None else "timed out")
    big = str(uuid.uuid4())
    held = authorize(key, 150, big)
    body = held.json() if held.headers.get("content-type", "").startswith("application/json") else {}
    approval = (body.get("error") or {}).get("approvalId") or body.get("approvalId")
    step("$150 needs an approval: 403 approval_required", held.status_code == 403 and "approval_required" in held.text,
         f"{held.status_code} {held.text[:100]}")
    if approval:
        # The agent's approvalId is the operation it waits on (GET /v1/spend/approvals/{id} at the gateway); the
        # request people decide on reaches the core over Kafka a moment later, under its own id.
        request = wait(lambda: next((a for a in listed("/api/v1/approvals") if a.get("operationId") == approval),
                                    None), timeout=60)
        d = request and core.put(f"/api/v1/approvals/{request['id']}/decision",
                                 json={"verdict": "approve", "comment": "demo"})
        d = d if d is not None and d.status_code == 200 else None
        step("the owner approves it", d is not None, "" if d is not None else "the core did not see the request")
        again = wait(lambda: (lambda x: x if x.status_code == 200 else None)(authorize(key, 150, big)), timeout=60)
        step("the same request with the same Idempotency-Key passes", again is not None)
    else:
        step("the refusal carries an approval id", False, held.text[:160])
    over = authorize(key, 150, str(uuid.uuid4()))
    step("another $150: 402 budget_exceeded before any money moved",
         over.status_code == 402 and "budget_exceeded" in over.text, f"{over.status_code} {over.text[:100]}")
    if first is not None:
        auth_id = first.json().get("authorizationId")
        # the agent got the report and reports what it paid: the operation is settled and charged
        settled = httpx.post(f"{GATEWAY}/v1/spend/settle", trust_env=False, timeout=30,
                             headers={"Authorization": f"Bearer {key}"},
                             json={"authorizationId": auth_id, "amount": 40})
        step("the $40 operation is settled", settled.status_code == 200, f"{settled.status_code} {settled.text[:80]}")
        receipt = wait(lambda: (lambda x: x.json() if x.status_code == 200 else None)(
            core.get(f"/api/v1/operations/{auth_id}/receipt")), timeout=90)
        step("the operation has a signed receipt", bool(receipt and receipt.get("hash") and receipt.get("signature")))

    # --- People
    employee_token = token("ecarter")["access_token"]
    me = client(employee_token)
    step("ecarter (employee) opens their cabinet", me.get("/api/v1/me").status_code == 200)
    step("ecarter cannot read company data: 403", me.get("/api/v1/company").status_code == 403)

    # --- Tokens made for another client of the realm (ADR 0076)
    other = httpx.post(TOKEN_URL, trust_env=False, timeout=60, data={
        "grant_type": "client_credentials", "client_id": "costguard-privacy",
        "client_secret": os.environ.get("KEYCLOAK_PRIVACY_CLIENT_SECRET", "costguard-privacy-local")})
    if other.status_code == 200:
        r = client(other.json()["access_token"]).get("/api/v1/company")
        step("a token of another client (costguard-privacy) is refused: 401", r.status_code == 401, str(r.status_code))

    # --- The web interface
    web = httpx.get(WEB, trust_env=False, timeout=30)
    step("the web interface answers", web.status_code == 200 and "<div id=\"root\"" in web.text)
    plan_page = httpx.get(f"{WEB}/settings/plan", trust_env=False, timeout=30)
    step("the plan page route is served", plan_page.status_code == 200)
    plans = core.get("/api/v1/billing/plans")
    usd = plans.status_code == 200 and all("USD" in (p.get("prices") or {}) for p in plans.json() if p["name"] != "trial")
    step("plans are priced in USD", usd)
    return summary()


def summary() -> int:
    failed = [name for name, ok, _ in results if not ok]
    print(f"\n{len(results) - len(failed)} of {len(results)} steps passed")
    for name in failed:
        print(f"  failed: {name}")
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
