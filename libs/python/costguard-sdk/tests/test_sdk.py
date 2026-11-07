import json

import httpx
import pytest

from costguard import ApprovalRequired, CostGuard, Refused


def gateway(responses):
    """responses: path -> (status, body) or callable(request) -> (status, body)."""
    calls = []

    def handle(request):
        calls.append((request.method, request.url.path, json.loads(request.content) if request.content else None,
                      dict(request.headers)))
        r = responses[request.url.path]
        status, body = r(request) if callable(r) else r
        return httpx.Response(status, json=body)

    client = httpx.Client(base_url="http://gw", transport=httpx.MockTransport(handle))
    return CostGuard("cg_live_x", base_url="http://gw", client=client), calls


OK = (200, {"authorizationId": "op-1", "status": "authorized", "reserved": 1290})


def test_spend_settles_what_was_spent():
    cg, calls = gateway({"/v1/spend/authorize": OK, "/v1/spend/settle": (200, {"status": "settled"})})
    with cg.spend("Ozon", 1290, product="Картридж", task_id="t-1", operation_id="op-1") as op:
        op.settle(1100.5)
    method, path, body, headers = calls[0]
    assert body == {"vendor": "Ozon", "amount": 1290.0, "product": "Картридж", "taskId": "t-1", "kind": "paid_api"}
    assert headers["idempotency-key"] == "op-1" and headers["authorization"] == "Bearer cg_live_x"
    assert calls[1][2] == {"authorizationId": "op-1", "amount": 1100.5}
    assert len(calls) == 2


def test_spend_without_settle_charges_the_reserve():
    cg, calls = gateway({"/v1/spend/authorize": OK, "/v1/spend/settle": (200, {})})
    with cg.spend("Ozon", 1290):
        pass
    assert calls[1][1] == "/v1/spend/settle" and calls[1][2]["amount"] == 1290.0


def test_an_exception_releases_the_reserve():
    cg, calls = gateway({"/v1/spend/authorize": OK, "/v1/spend/release": (200, {})})
    with pytest.raises(RuntimeError):
        with cg.spend("Ozon", 1290):
            raise RuntimeError("the shop is down")
    assert calls[1][1] == "/v1/spend/release"


def test_refusal():
    cg, _ = gateway({"/v1/spend/authorize": (402, {"error": {"type": "budget_exceeded",
                                                              "message": "budget_exceeded"}})})
    with pytest.raises(Refused) as e:
        cg.authorize("Ozon", 10**6)
    assert e.value.status == 402 and e.value.type == "budget_exceeded"


def test_approval_flow():
    answers = iter(["pending", "pending", "approved"])
    cg, calls = gateway({
        "/v1/spend/authorize": (403, {"error": {"type": "approval_required", "message": "above threshold",
                                                "approvalId": "op-9"}}),
        "/v1/spend/approvals/op-9": lambda r: (200, {"approvalId": "op-9", "status": next(answers)}),
    })
    with pytest.raises(ApprovalRequired) as e:
        cg.authorize("Ozon", 50000, operation_id="op-9")
    slept = []
    assert cg.wait_for_approval(e.value.approval_id, sleep=slept.append, every=5) == "approved"
    assert slept == [5, 5]


def test_wait_gives_up_after_the_timeout():
    cg, _ = gateway({"/v1/spend/approvals/a": (200, {"status": "pending"})})
    assert cg.wait_for_approval("a", timeout=10, every=5, sleep=lambda s: None) == "pending"


def test_non_json_error():
    cg, _ = gateway({"/v1/spend/settle": (502, None)})
    with pytest.raises(Refused, match="http_502"):
        cg.settle("op", 1)


def test_provider_url():
    cg, _ = gateway({})
    assert cg.provider_url("openai") == "http://gw/v1/providers/openai"
    with pytest.raises(ValueError):
        cg.provider_url("mistral")
