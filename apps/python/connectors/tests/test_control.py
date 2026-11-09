"""The agent following the core's list of connected systems and reporting its pulls (ADR 0019)."""

import io
import uuid
from datetime import UTC, datetime, timedelta

import fastavro

from costguard_common import schemas
from costguard_connectors import agent, contract, control

TENANT = uuid.UUID("00000000-0000-4000-8000-0000000000aa")
OTHER = uuid.UUID("00000000-0000-4000-8000-0000000000bb")
OKTA_ID = uuid.UUID("00000000-0000-4000-8000-00000000c001")
T0 = datetime(2026, 11, 8, 10, 0, tzinfo=UTC)


def connection(kind, enabled=True, settings=None, asked=None, at=T0, tenant=TENANT, cid=OKTA_ID):
    return {"tenant_id": str(tenant), "connection_id": str(cid), "type": kind, "name": kind, "enabled": enabled,
            "settings": settings or {}, "sync_requested_at": asked, "updated_at": at}


def fits(topic, value):
    fastavro.schemaless_writer(io.BytesIO(), schemas.parsed(topic), value)


def test_the_latest_state_of_each_connection_of_the_tenant_wins():
    found = control.latest([connection("okta", settings={"url": "https://old"}),
                            connection("okta", settings={"url": "https://new"}, at=T0 + timedelta(minutes=1)),
                            connection("okta", enabled=False, tenant=OTHER, at=T0 + timedelta(hours=1))], TENANT)
    assert found["okta"]["settings"] == {"url": "https://new"}


def test_the_core_adds_replaces_and_removes_sections_of_the_file():
    file = {"tenant_id": str(TENANT), "onec": {"url": "http://1c/odata", "user": "u"},
            "okta": {"url": "https://file.okta.example", "ignore": ["Slack"]}, "jamf": {"url": "j", "client_id": "c"}}
    merged, ids = control.merge(file, {
        "okta": connection("okta", settings={"url": "https://core.okta.example", "ignore": "Zoom, Miro"}),
        "jamf": connection("jamf", enabled=False),
        "mail": connection("mail", settings={"host": "imap.example", "user": "inv", "min_image_kb": "40"})})
    assert merged["okta"] == {"url": "https://core.okta.example", "ignore": ["Zoom", "Miro"]}
    assert "jamf" not in merged
    assert merged["onec"] == file["onec"]
    assert merged["mail"]["min_image_kb"] == 40
    assert ids["okta"] == OKTA_ID
    assert file["okta"]["url"] == "https://file.okta.example"


def test_a_sync_is_due_until_the_agent_starts_the_system_after_it_was_asked():
    known = {"okta": connection("okta", asked=T0), "onec": connection("onec"),
             "jamf": connection("jamf", enabled=False, asked=T0)}
    assert control.due(known, {}) == {"okta"}
    assert control.due(known, {"okta": T0 - timedelta(minutes=5)}) == {"okta"}
    assert control.due(known, {"okta": T0 + timedelta(seconds=1)}) == set()


def test_secrets_of_the_environment_never_reach_the_core():
    env = {"BITRIX24_WEBHOOK": "https://b24.example/rest/1/s3cr3tw3bh00k/", "OKTA_TOKEN": "00okta-token-xyz",
           "KAFKA_BOOTSTRAP_SERVERS": "kafka:9092"}
    text = control.redact("GET https://b24.example/rest/1/s3cr3tw3bh00k/user.get failed; token 00okta-token-xyz; "
                          "kafka:9092", env)
    assert "s3cr3t" not in text and "00okta" not in text and "kafka:9092" in text


def test_a_run_report_fits_its_schema():
    ok = control.run_message(TENANT, "okta", OKTA_ID, T0, T0 + timedelta(seconds=5), 42, None)
    failed = control.run_message(TENANT, "onec", None, T0, T0, 0, "ConnectError: refused")
    for m in (ok, failed):
        assert m.topic == "connectors.runs.v1" and m.key.startswith(str(TENANT))
        fits(m.topic, m.value)
    assert failed.value["status"] == "FAILED" and ok.value["records"] == 42


def test_the_core_config_fits_what_the_agent_reads():
    fits("connectors.config.v1", connection("okta", settings={"url": "https://x"}, asked=T0))


class Fake:
    def __init__(self, name, batch=None, error=None):
        self.name = name
        self._batch = batch
        self._error = error

    def pull(self, tenant, cursor):
        if self._error:
            raise self._error
        return self._batch


def test_every_pull_is_reported_with_its_records_or_its_error(tmp_path):
    published, reports = [], []
    report = agent.Reporter(TENANT, reports.extend, {"ONEC_PASSWORD": "pa55word-1c"}, {"okta": OKTA_ID})
    batch = contract.Batch(entities=[], costs=[], cursor="c1")
    good = Fake("okta", batch)
    bad = Fake("1c", error=RuntimeError("login failed for pa55word-1c"))
    failures = agent.once([good, bad], TENANT, agent.State(tmp_path / "s.json"), published.extend, report)

    assert set(failures) == {"1c"}
    by_type = {r.value["type"]: r.value for r in reports}
    assert by_type["okta"]["status"] == "SUCCEEDED" and by_type["okta"]["connection_id"] == str(OKTA_ID)
    assert by_type["onec"]["status"] == "FAILED" and by_type["onec"]["connection_id"] is None
    assert "pa55word" not in by_type["onec"]["error"]
    assert set(report.started) == {"okta", "onec"}


def test_a_reported_error_carries_no_e_mail_or_name_of_the_source_system(tmp_path):
    reports = []
    report = agent.Reporter(TENANT, reports.extend)
    bad = Fake("okta", error=RuntimeError("user anna.k@romashka.ru (Кузнецова Анна Сергеевна) is locked"))
    agent.once([bad], TENANT, agent.State(tmp_path / "s.json"), lambda _: None, report)
    [run] = reports
    assert run.value["error"] == "RuntimeError: user ***@romashka.ru (***) is locked"


def test_a_report_that_cannot_be_sent_does_not_fail_the_pull(tmp_path):
    def down(_):
        raise RuntimeError("kafka is down")

    report = agent.Reporter(TENANT, down)
    assert agent.once([Fake("okta", contract.Batch(entities=[], costs=[], cursor=None))], TENANT,
                      agent.State(tmp_path / "s.json"), lambda _: None, report) == {}


def test_a_section_without_its_secret_fails_alone_and_is_reported(tmp_path):
    reports = []
    report = agent.Reporter(TENANT, reports.extend)
    config = {"okta": {"url": "https://x.okta.example"}, "moysklad": {}}
    failures = agent.round_(config, {"MOYSKLAD_TOKEN": "t"}, TENANT, agent.State(tmp_path / "s.json"),
                            lambda _: None, report, only={"okta"})
    assert set(failures) == {"okta"} and "OKTA_TOKEN" in failures["okta"]
    assert [r.value["type"] for r in reports] == ["okta"]


def test_mail_is_reported_with_the_files_it_uploaded(tmp_path):
    class Inbox:
        def collect(self):
            return 3

    reports = []
    agent.round_({"mail": {"host": "h", "user": "u"}}, {}, TENANT, agent.State(tmp_path / "s.json"),
                 lambda _: None, agent.Reporter(TENANT, reports.extend), inbox=lambda c, e: Inbox())
    assert reports[0].value["type"] == "mail" and reports[0].value["records"] == 3


# secrets kept in the core (ADR 0030)


def _core(body: dict, status: int = 200):
    import httpx

    def handle(request):
        assert request.url.path == "/api/v1/connections/agent-secrets"
        assert request.headers["authorization"] == "Bearer t"
        return httpx.Response(status, json=body)

    return httpx.Client(base_url="http://core", transport=httpx.MockTransport(handle),
                        headers={"Authorization": "Bearer t"})


def test_secrets_kept_in_the_core_come_by_environment_variable_and_the_environment_wins():
    body = {"tenantId": str(TENANT), "secrets": {"okta": {"OKTA_TOKEN": "core-okta"},
                                                  "onec": {"ONEC_PASSWORD": "core-1c"}}}
    secrets = control.core_secrets(_core(body), TENANT)
    assert secrets == {"OKTA_TOKEN": "core-okta", "ONEC_PASSWORD": "core-1c"}
    env = control.with_secrets({"OKTA_TOKEN": "env-okta", "CORE_URL": "http://core"}, secrets)
    assert env["OKTA_TOKEN"] == "env-okta" and env["ONEC_PASSWORD"] == "core-1c"


def test_secrets_for_another_company_or_a_refused_token_are_not_used():
    import httpx
    import pytest

    with pytest.raises(RuntimeError):
        control.core_secrets(_core({"tenantId": str(uuid.uuid4()), "secrets": {}}), TENANT)
    with pytest.raises(httpx.HTTPStatusError):
        control.core_secrets(_core({"code": "connector_role_required"}, 403), TENANT)


def test_a_secret_from_the_core_is_blanked_out_of_a_reported_error():
    reports = []
    report = agent.Reporter(TENANT, reports.extend)
    report.env = control.with_secrets({}, {"OKTA_TOKEN": "core-secret-okta-token"})
    report("okta", datetime.now(UTC), 0, "401 for token core-secret-okta-token")
    assert "core-secret-okta-token" not in reports[0].value["error"]
