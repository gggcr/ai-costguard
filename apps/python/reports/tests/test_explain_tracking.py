import json
from datetime import date
from decimal import Decimal

import httpx

from costguard_common import llm
from costguard_reports import explain, publish, report, tracking


def sample(findings=None):
    months = [(date(2026, m, 1), Decimal(100000 + 5000 * m)) for m in range(1, 9)]
    return report.build("Ромашка", "RUB", months,
                        findings or {"open": 3, "expectedSavings": 42000, "confirmedSavings": 7000}, date(2026, 9, 5))


class Fixed:
    def __init__(self, answer=None, refuse=False):
        self.answer, self.refuse, self.asked = answer, refuse, []

    def complete(self, system, user, task_id=None):
        self.asked.append((system, user, task_id))
        if self.refuse:
            raise llm.LlmRefused("budget")
        return self.answer


def test_the_model_writes_from_the_facts_and_its_numbers_are_checked():
    r = sample()
    facts = explain.facts(r)
    assert facts["расходы за период"] == "980 000"
    assert facts["изменение, %"] == 33
    good = (f"За 8 месяцев компания потратила {facts['расходы за период']} RUB, расходы выросли на 33%. "
            f"По 3 открытым находкам можно сэкономить 42 000 RUB.")
    model = Fixed(good)
    out = explain.explain(r, model, "report-2026-09")
    assert out == explain.Explanation(good, "model")
    system, user, task = model.asked[0]
    assert "только числа из фактов" in system and json.loads(user)["открытых находок"] == 3
    assert task == "report-2026-09"

    # a number that is not in the facts: the template instead
    made_up = explain.explain(r, Fixed("Расходы выросли на 31%, экономия 50 000 RUB."))
    assert made_up.source == "template"
    assert explain.explain(r, Fixed(refuse=True)).source == "template"
    assert explain.explain(r, None).source == "template"


def test_the_template_says_the_same_plainly():
    text = explain.template(sample())
    assert text.startswith("За 8 мес. потрачено 980 000 RUB.")
    assert "выросли на 33%" in text and "можно сэкономить 42 000 RUB" in text
    assert "подтверждено экономии: 7 000 RUB" in text
    assert explain.grounded(text, explain.facts(sample()))
    assert explain.template(report.build("Пусто", "RUB", [], {}, date(2026, 9, 5))) == "Расходов за период пока нет."
    calm = explain.template(sample({"open": 0, "expectedSavings": 0, "confirmedSavings": 0}))
    assert "находок" not in calm and "подтверждено" not in calm


def test_the_explanation_goes_into_both_files():
    r = sample()
    text = explain.template(r)
    import io

    from openpyxl import load_workbook

    book = load_workbook(io.BytesIO(report.xlsx(r, text)))
    rows = [[c.value for c in row] for row in book["Итоги"].iter_rows()]
    assert ["Пояснение", text] in rows
    assert report.pdf(r, text)[:4] == b"%PDF"


def test_a_forecast_run_goes_to_mlflow_with_ids_not_names():
    months = [(date(2025, 1, 1).replace(year=2025 + (i // 12), month=i % 12 + 1), Decimal(1000 + 30 * i))
              for i in range(14)]
    body = publish.document("RUB", months, {"d1": months}, {"d1": "Секретный отдел"}, [], [], date(2026, 3, 5), 3)
    calls = []

    def handler(request: httpx.Request) -> httpx.Response:
        calls.append((request.method, request.url.path, json.loads(request.content) if request.content else None))
        path = request.url.path
        if path.endswith("/experiments/get-by-name"):
            return httpx.Response(404, json={"error_code": "RESOURCE_DOES_NOT_EXIST"})
        if path.endswith("/experiments/create"):
            return httpx.Response(200, json={"experiment_id": "7"})
        if path.endswith("/runs/create"):
            return httpx.Response(200, json={"run": {"info": {"run_id": "r1"}}})
        return httpx.Response(200, json={})

    t = tracking.Tracking(httpx.Client(base_url="http://mlflow", transport=httpx.MockTransport(handler)))
    assert t.log("tenant-1", body) == "r1"
    paths = [p for _, p, _ in calls]
    assert paths == ["/api/2.0/mlflow/experiments/get-by-name", "/api/2.0/mlflow/experiments/create",
                     "/api/2.0/mlflow/runs/create", "/api/2.0/mlflow/runs/log-batch", "/api/2.0/mlflow/runs/update"]
    assert calls[2][2]["experiment_id"] == "7"
    batch = calls[3][2]
    params = {p["key"]: p["value"] for p in batch["params"]}
    metrics = {m["key"]: m["value"] for m in batch["metrics"]}
    assert params["company.model"] == body["company"]["model"] and "department.d1.model" in params
    assert "company.wape" in metrics and metrics["departments"] == 1
    assert "Секретный" not in json.dumps(calls, ensure_ascii=False)
    assert calls[4][2]["status"] == "FINISHED"


def test_a_tracking_server_that_does_not_answer_holds_nothing_up():
    def down(request):
        raise httpx.ConnectError("refused")

    t = tracking.Tracking(httpx.Client(base_url="http://mlflow", transport=httpx.MockTransport(down)))
    body = publish.document("RUB", [], {}, {}, [], [], date(2026, 3, 5), 3)
    assert t.log("tenant-1", body) is None
