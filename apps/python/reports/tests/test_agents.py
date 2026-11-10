import random
from datetime import date, timedelta
from decimal import Decimal

import httpx

from costguard_reports import agents, publish, tracking

TODAY = date(2026, 11, 10)


def weekly_history(days=150, weekend=0.3, growth=3.0, noise=0.15, seed=1):
    rnd = random.Random(seed)
    start = TODAY - timedelta(days=days)
    out = []
    for i in range(days):
        d = start + timedelta(days=i)
        v = (1000 + growth * i) * (weekend if d.weekday() >= 5 else 1.0) * rnd.uniform(1 - noise, 1 + noise)
        out.append((d, v))
    return out


def test_boosting_learns_what_the_trees_before_it_left():
    rows = [[float(x), float(x % 2)] for x in range(40)]
    targets = [10.0 if x < 20 else 30.0 for x in range(40)]
    model = agents.boost(rows, targets, rounds=60, rate=0.3)
    assert abs(model([5.0, 1.0]) - 10) < 0.5 and abs(model([35.0, 1.0]) - 30) < 0.5
    flat = agents.boost(rows, [7.0] * 40)
    assert flat.trees == [] and flat([3.0, 0.0]) == 7.0


def test_an_agent_with_a_week_in_its_spend_is_won_by_the_models_that_see_the_week():
    f = agents.forecast(weekly_history(), TODAY, 30)
    assert f.model in ("gbm", "weekly") and f.wape < 0.15
    assert f.challenger in ("gbm", "weekly") and f.challenger != f.model
    assert [d for d, _ in f.days][:2] == [TODAY, TODAY + timedelta(days=1)]
    weekend = [v for d, v in f.days if d.weekday() >= 5]
    weekday = [v for d, v in f.days if d.weekday() < 5]
    assert max(weekend) < min(weekday)
    assert f.low < f.total < f.high
    assert abs(f.total - sum(v for _, v in f.days)) < 1


def steady(step_at=None):
    rnd = random.Random(3)
    start = TODAY - timedelta(days=160)
    out = []
    for i in range(160):
        d = start + timedelta(days=i)
        level = 500 if step_at is None or i < step_at else 1500
        out.append(level * (0.4 if d.weekday() >= 5 else 1.0) * rnd.uniform(0.9, 1.1))
    return start, out


def test_the_backtest_picks_the_boosting_on_a_steady_week_and_the_week_after_a_step():
    start, series = steady()
    scores = agents.backtest(series, start)
    assert {s.model for s in scores} == {"naive", "weekly", "mean7", "gbm"}
    assert scores[0].model == "gbm"
    # the level steps up three times: the features measured from the last week lag behind, the week repeated wins
    start, series = steady(step_at=130)
    assert agents.backtest(series, start)[0].model == "weekly"


def test_short_and_empty_histories():
    assert agents.forecast([], TODAY) is None
    assert agents.forecast([(TODAY - timedelta(days=3), 0.0)], TODAY) is None
    short = agents.forecast([(TODAY - timedelta(days=i), 100.0) for i in range(1, 11)], TODAY, 7)
    assert short.model == "mean7" and short.wape is None and short.total == 700.0
    tiny = agents.forecast([(TODAY - timedelta(days=2), 50.0)], TODAY, 5)
    assert tiny.total > 0 and len(tiny.days) == 5
    # days older than half a year are not read
    old = [(TODAY - timedelta(days=400), 1000.0)]
    assert agents.forecast(old, TODAY) is None


def test_the_agents_go_into_the_document_most_expensive_first_and_into_mlflow():
    spend = {"a-small": [(d, v / 10) for d, v in weekly_history()], "a-big": weekly_history(seed=2),
             "a-silent": [(TODAY - timedelta(days=5), 0.0)]}
    body = publish.document("RUB", [], {}, {}, [], [], TODAY, 3, spend)
    assert [a["agentId"] for a in body["agents"]] == ["a-big", "a-small"]
    big = body["agents"][0]
    assert len(big["days"]) == 30 and big["days"][0]["day"] == "2026-11-10"
    assert isinstance(big["total"], Decimal) and big["low"] <= big["total"] <= big["high"]
    params, metrics = tracking.entries(body)
    keys = {m["key"]: m["value"] for m in metrics}
    assert keys["agents"] == 2 and "agents.mean_wape" in keys
    assert sum(v for k, v in keys.items() if k.startswith("agents.won.")) == 2


def test_agent_spend_is_read_by_day_for_the_company():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen["query"] = request.content.decode()
        seen["params"] = dict(request.url.params)
        return httpx.Response(200, text="a1\t2026-11-01\t12.5\na1\t2026-11-02\t3\na2\t2026-11-02\t7\n")

    clickhouse = httpx.Client(base_url="http://ch", transport=httpx.MockTransport(handler))
    out = publish.fetch_agent_spend(clickhouse, "t1", "RUB", tenant_setting=True)
    assert out == {"a1": [(date(2026, 11, 1), 12.5), (date(2026, 11, 2), 3.0)], "a2": [(date(2026, 11, 2), 7.0)]}
    assert "agent_spend_by_day" in seen["query"] and "day < today()" in seen["query"]
    assert seen["params"]["param_tenant"] == "t1" and seen["params"]["SQL_tenant_id"] == "t1"
