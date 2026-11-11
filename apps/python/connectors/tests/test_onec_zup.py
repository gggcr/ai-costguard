"""1С:ЗУП pay statements become payments the core stores (ADR 0054); nothing goes to Kafka."""

import json
from datetime import date

import httpx

from costguard_connectors import agent, onec_zup

EMPLOYEES = [{"Ref_Key": "e1", "Description": "Иванов Иван Иванович"},
             {"Ref_Key": "e2", "Description": "Петрова Анна"}]
WAYS = [{"Ref_Key": "w1", "Description": "Аванс"}, {"Ref_Key": "w2", "Description": "Зарплата за месяц"}]
BANK = [
    {"Ref_Key": "d1", "Posted": True, "Date": "2026-10-24T00:00:00", "ПериодРегистрации": "2026-10-01T00:00:00",
     "СпособВыплаты_Key": "w1", "Зарплата": [{"Сотрудник_Key": "e1", "КВыплате": 40000},
                                             {"Сотрудник_Key": "e2", "КВыплате": 0}]},
    {"Ref_Key": "d2", "Posted": True, "Date": "2026-11-10T00:00:00", "ПериодРегистрации": "2026-10-01T00:00:00",
     "СпособВыплаты_Key": "w2", "DeletionMark": True, "Зарплата": [{"Сотрудник_Key": "e1", "КВыплате": 1}]},
]
CASH = [{"Ref_Key": "d3", "Posted": True, "Date": "2026-11-10T00:00:00", "ПериодРегистрации": "2026-10-01T00:00:00",
         "СпособВыплаты_Key": "w2", "Зарплата": [{"Сотрудник_Key": "e2", "КВыплате": 61500.5}]}]


def odata(request: httpx.Request) -> httpx.Response:
    entity = request.url.path.rsplit("/", 1)[-1]
    data = {onec_zup.EMPLOYEES: EMPLOYEES, onec_zup.WAYS: WAYS, onec_zup.STATEMENTS[0]: BANK,
            onec_zup.STATEMENTS[2]: CASH}
    if entity not in data:
        return httpx.Response(404)
    assert "Posted eq true" in request.url.params.get("$filter", "Posted eq true")
    return httpx.Response(200, json={"value": data[entity]})


def test_statements_become_payments():
    zup = onec_zup.ZupPayroll(httpx.Client(transport=httpx.MockTransport(odata), base_url="http://1c/odata"),
                              httpx.Client(), today=date(2026, 11, 11))
    payments = zup.payments()
    assert payments == [
        {"employeeName": "Иванов Иван Иванович", "kind": "advance", "period": "2026-10-01", "payDate": "2026-10-24",
         "amount": "40000", "externalKey": "d1:e1"},
        {"employeeName": "Петрова Анна", "kind": "salary", "period": "2026-10-01", "payDate": "2026-11-10",
         "amount": "61500.5", "externalKey": "d3:e2"},
    ]


def test_kinds_by_the_way_of_paying():
    assert onec_zup.kind_of("Аванс") == "advance"
    assert onec_zup.kind_of("Отпуск") == "vacation_pay"
    assert onec_zup.kind_of("Премия к празднику") == "bonus"
    assert onec_zup.kind_of("Зарплата за месяц") == "salary"
    assert onec_zup.kind_of("Увольнение") == "other"


def test_the_payments_go_to_the_core_over_rest():
    sent = []

    def core(request: httpx.Request) -> httpx.Response:
        assert request.url.path == "/api/v1/payroll/payments"
        assert request.headers["Authorization"] == "Bearer t"
        body = json.loads(request.content)
        sent.append(body)
        return httpx.Response(200, json={"stored": len(body["payments"]), "skipped": []})

    zup = onec_zup.ZupPayroll(httpx.Client(transport=httpx.MockTransport(odata), base_url="http://1c/odata"),
                              httpx.Client(transport=httpx.MockTransport(core), base_url="http://core",
                                           headers={"Authorization": "Bearer t"}), today=date(2026, 11, 11))
    assert zup.collect() == 2
    assert sent[0]["source"] == "1c-zup"


def test_the_agent_runs_it_beside_the_kafka_connectors(tmp_path):
    reports = []

    class Fake:
        def collect(self):
            return 3

    failures = agent.round_({"onec_zup": {"url": "http://1c", "user": "u"}}, {}, __import__("uuid").uuid4(),
                            agent.State(tmp_path / "s.json"), lambda m: None,
                            lambda kind, started, n, error: reports.append((kind, n, error)),
                            payroll=lambda config, env: Fake())
    assert failures == {}
    assert reports == [("onec_zup", 3, None)]
    assert "onec_zup" in agent.SECTIONS
