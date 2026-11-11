"""The connector agent: runs the connectors on a schedule, inside the platform or inside the client's network.

When 1C or an on-premises Jamf stands behind the client's firewall, the agent runs there and only connects out,
to Kafka. The config is a TOML file; secrets come from the environment, never from the file:

    tenant_id = "…"
    interval_minutes = 60
    state_file = "connector-state.json"

    [onec]
    url = "http://1c.local/base/odata/standard.odata"
    user = "costguard"            # password: ONEC_PASSWORD

    [moysklad]                     # token: MOYSKLAD_TOKEN
    [bitrix24]                     # webhook: BITRIX24_WEBHOOK
    [yandex360]
    org_id = "123"                 # token: YANDEX360_TOKEN
    [mail]
    host = "imap.yandex.ru"
    user = "invoices@company.ru"   # password: MAIL_PASSWORD; core: CORE_URL and CORE_TOKEN
    min_image_kb = 20              # smaller photos are logos, not documents; also min_image_side = 500 (px)
    [jamf]
    url = "https://company.jamfcloud.com"
    client_id = "…"                # secret: JAMF_CLIENT_SECRET
    [intune]
    directory_id = "…"             # the Entra ID tenant
    client_id = "…"                # secret: INTUNE_CLIENT_SECRET
    [google_workspace]
    admin = "admin@company.ru"     # service account key (JSON): GOOGLE_WORKSPACE_KEY
    ignore = ["Портал"]            # apps that cost nothing; [google_workspace.apps] renames apps:
    [okta]                         #   "Slack" = ["Slack Technologies", "Slack Business+"]
    url = "https://company.okta.com"   # token: OKTA_TOKEN
    [entra]
    directory_id = "…"
    client_id = "…"                # secret: ENTRA_CLIENT_SECRET
    [merge]                        # key: MERGE_API_KEY; MERGE_ACCOUNTING_TOKEN and/or MERGE_HRIS_TOKEN
    [tbank]
    accounts = ["40702810…"]       # optional, all accounts otherwise; token: TBANK_TOKEN
    [payments]                     # payments through intermediaries (ADR 0015), for the bank connectors
    intermediaries = ["7700000009", "ООО Агент"]
    services = { "тучкаcrm" = "ООО «Тучка Софт»" }

Systems connected on the page of the core come from connectors.config.v1 and are merged over this file: their
settings that are not secrets add to or replace a section, a system disconnected there is not pulled even if it
is in the file. Secrets stay in the environment. Each pull is reported to connectors.runs.v1 (ADR 0019). A running
agent checks every minute whether a person asked to sync a system now; with --once it simply pulls everything.
from_core = false in the file keeps the agent on the file alone.

Cursors are kept in the state file and written after each successful publish. With COSTGUARD_ENV_PREFIX=ROMASHKA_
the variables ROMASHKA_ONEC_PASSWORD and so on take the place of the unprefixed ones.
"""

from __future__ import annotations

import argparse
import json
import logging
import os
import time
import tomllib
import uuid
from collections.abc import Callable
from datetime import UTC, datetime
from pathlib import Path

import httpx

from costguard_common import logs, payments, tracing
from costguard_connectors import (
    banks,
    bitrix24,
    contract,
    control,
    entra,
    google_workspace,
    intune,
    jamf,
    mail,
    merge,
    moysklad,
    okta,
    onec,
    onec_zup,
    scim,
    signins,
    tbank,
    yandex360,
)

log = logging.getLogger("costguard.connectors")

# Sections of a tenant config the agent reads; Airflow adds its own [report].
SECTIONS = ("onec", "moysklad", "bitrix24", "yandex360", "mail", "jamf", "intune", "google_workspace", "okta",
            "entra", "merge", "tbank", "scim", "payments", "onec_zup")


def build(config: dict, env: dict[str, str]) -> list[contract.Connector]:
    found: list[contract.Connector] = []
    if "onec" in config:
        c = config["onec"]
        found.append(onec.OneC(httpx.Client(base_url=c["url"], auth=(c["user"], env.get("ONEC_PASSWORD", "")),
                                            timeout=60)))
    if "moysklad" in config:
        found.append(moysklad.MoySklad(httpx.Client(
            base_url=config["moysklad"].get("url", moysklad.BASE_URL), timeout=60,
            headers={"Authorization": f"Bearer {env['MOYSKLAD_TOKEN']}", "Accept-Encoding": "gzip"})))
    if "bitrix24" in config:
        found.append(bitrix24.Bitrix24(httpx.Client(base_url=env["BITRIX24_WEBHOOK"], timeout=60)))
    if "yandex360" in config:
        c = config["yandex360"]
        found.append(yandex360.Yandex360(httpx.Client(
            base_url=c.get("url", yandex360.BASE_URL), timeout=60,
            headers={"Authorization": f"OAuth {env['YANDEX360_TOKEN']}"}), c["org_id"]))
    if "jamf" in config:
        c = config["jamf"]
        found.append(jamf.Jamf(httpx.Client(base_url=c["url"], timeout=60), c["client_id"],
                               env["JAMF_CLIENT_SECRET"]))
    if "intune" in config:
        c = config["intune"]
        found.append(intune.Intune(httpx.Client(timeout=60), c["directory_id"], c["client_id"],
                                   env["INTUNE_CLIENT_SECRET"]))
    if "google_workspace" in config:
        c = config["google_workspace"]
        key = json.loads(env["GOOGLE_WORKSPACE_KEY"])
        found.append(google_workspace.GoogleWorkspace(httpx.Client(timeout=60), key, c["admin"],
                                                      c.get("customer", "my_customer"), signins.Apps.of(c)))
    if "okta" in config:
        c = config["okta"]
        found.append(okta.Okta(httpx.Client(base_url=c["url"], timeout=60,
                                            headers={"Authorization": f"SSWS {env['OKTA_TOKEN']}"}),
                               signins.Apps.of(c)))
    if "entra" in config:
        c = config["entra"]
        found.append(entra.Entra(httpx.Client(timeout=60), c["directory_id"], c["client_id"],
                                 env["ENTRA_CLIENT_SECRET"], signins.Apps.of(c)))
    if "merge" in config:
        found.append(merge.Merge(httpx.Client(base_url=config["merge"].get("url", merge.BASE_URL), timeout=60,
                                              headers={"Authorization": f"Bearer {env['MERGE_API_KEY']}"}),
                                 env.get("MERGE_ACCOUNTING_TOKEN"), env.get("MERGE_HRIS_TOKEN")))
    for c in config.get("scim", []):  # [[scim]]: one entry per service, its token in token_env
        found.append(scim.Scim(httpx.Client(base_url=c["url"], timeout=60,
                                            headers={"Authorization": f"Bearer {env[c['token_env']]}"}),
                               c["vendor"], c["product"]))
    paying = config.get("payments", {})
    intermediaries = [payments.intermediary(i) for i in paying.get("intermediaries", [])]
    if "tbank" in config:
        c = config["tbank"]
        bank = tbank.TBank(httpx.Client(base_url=c.get("url", tbank.BASE_URL), timeout=60,
                                        headers={"Authorization": f"Bearer {env['TBANK_TOKEN']}"}),
                           c.get("accounts"))
        found.append(banks.BankConnector(bank, intermediaries, paying.get("services")))
    return found


class State:
    def __init__(self, path: Path) -> None:
        self._path = path
        self._cursors: dict[str, str | None] = json.loads(path.read_text("utf-8")) if path.exists() else {}

    def get(self, name: str) -> str | None:
        return self._cursors.get(name)

    def put(self, name: str, cursor: str | None) -> None:
        self._cursors[name] = cursor
        tmp = self._path.with_suffix(".tmp")
        tmp.write_text(json.dumps(self._cursors, ensure_ascii=False, indent=2), "utf-8")
        tmp.replace(self._path)


def _now() -> datetime:
    return datetime.now(UTC)


class Reporter:
    """Reports each pull to connectors.runs.v1. A report that cannot be sent is logged and does not fail the pull."""

    def __init__(self, tenant: uuid.UUID, publish: Callable[[list[contract.Message]], None],
                 env: dict[str, str] | None = None, ids: dict[str, uuid.UUID] | None = None) -> None:
        self._tenant = tenant
        self._publish = publish
        self.env = env or {}  # the secrets to blank out of errors, the core's included
        self.ids = ids or {}
        self.started: dict[str, datetime] = {}

    def __call__(self, kind: str, started: datetime, records: int, error: str | None) -> None:
        self.started[kind] = started
        if error is not None:
            # Shown on the connections page: no secret of the environment, no e-mail or name of the source system.
            error = logs.mask(control.redact(error, self.env))
        try:
            self._publish([control.run_message(self._tenant, kind, self.ids.get(kind), started, _now(), records,
                                               error)])
        except Exception:
            log.exception("The run of %s was not reported", kind)


def once(connectors: list[contract.Connector], tenant: uuid.UUID, state: State,
         publish: Callable[[list[contract.Message]], None], report: Reporter | None = None) -> dict[str, str]:
    """Runs every connector; one failing does not stop the others. Returns the failures by connector."""
    failures: dict[str, str] = {}
    for c in connectors:
        started = _now()
        try:
            cursor, records = contract.run_counted(c, tenant, state.get(c.name), publish)
            state.put(c.name, cursor)
        except Exception as e:  # a connector's system is down or refuses us; the next round tries again
            log.exception("Connector %s failed", c.name)
            failures[c.name] = str(e)
            if report is not None:
                report(control.type_of(c), started, 0, f"{type(e).__name__}: {e}")
        else:
            if report is not None:
                report(control.type_of(c), started, records, None)
    return failures


def build_each(config: dict, env: dict[str, str]) -> tuple[list[contract.Connector], dict[str, str]]:
    """Like build, but a section that cannot be set up (a secret missing in the environment) does not stop the
    others. Returns the connectors and why each broken section could not be set up."""
    found: list[contract.Connector] = []
    broken: dict[str, str] = {}
    for section in SECTIONS:
        if section in ("mail", "payments", "onec_zup") or section not in config:
            continue
        try:
            found.extend(build({section: config[section], "payments": config.get("payments", {})}, env))
        except KeyError as e:
            broken[section] = f"Missing setting or secret {e}"
        except Exception as e:
            broken[section] = f"Cannot set up: {type(e).__name__}: {e}"
    return found, broken


def mail_inbox(config: dict, env: dict[str, str]) -> mail.MailInvoices:
    c = config["mail"]
    return mail.MailInvoices(
        mail.Imap(c["host"], c["user"], env["MAIL_PASSWORD"], c.get("folder", "INBOX")),
        httpx.Client(base_url=env["CORE_URL"], headers={"Authorization": f"Bearer {env['CORE_TOKEN']}"},
                     timeout=60),
        int(c.get("min_image_kb", mail.MIN_IMAGE_KB)), int(c.get("min_image_side", mail.MIN_IMAGE_SIDE)))


def zup_payroll(config: dict, env: dict[str, str]) -> onec_zup.ZupPayroll:
    c = config["onec_zup"]
    return onec_zup.ZupPayroll(
        httpx.Client(base_url=c["url"], auth=(c["user"], env.get("ONEC_ZUP_PASSWORD", "")), timeout=120),
        httpx.Client(base_url=env["CORE_URL"], headers={"Authorization": f"Bearer {env['CORE_TOKEN']}"},
                     timeout=120),
        int(c.get("days", 62)))


def round_(config: dict, env: dict[str, str], tenant: uuid.UUID, state: State,
           publish: Callable[[list[contract.Message]], None], report: Reporter,
           only: set[str] | None = None,
           inbox: Callable[[dict, dict[str, str]], object] = mail_inbox,
           payroll: Callable[[dict, dict[str, str]], object] = zup_payroll) -> dict[str, str]:
    """One round over the systems of the config, or over only some of them. Returns the failures."""
    connectors, broken = build_each(config, env)
    if only is not None:
        connectors = [c for c in connectors if control.type_of(c) in only]
        broken = {k: v for k, v in broken.items() if k in only}
    failures = once(connectors, tenant, state, publish, report)
    for kind, why in broken.items():
        log.error("Connector %s: %s", kind, why)
        report(kind, _now(), 0, why)
        failures[kind] = why
    if "mail" in config and (only is None or "mail" in only):
        started = _now()
        try:
            uploaded = inbox(config, env).collect()
            log.info("Mail: %d new files uploaded", uploaded)
            report("mail", started, uploaded, None)
        except Exception as e:  # the mailbox is down; the next round tries again
            log.exception("Mail connector failed")
            failures["mail"] = str(e)
            report("mail", started, 0, f"{type(e).__name__}: {e}")
    # pay goes to the core over REST, never to Kafka (ADR 0054)
    if "onec_zup" in config and (only is None or "onec_zup" in only):
        started = _now()
        try:
            stored = payroll(config, env).collect()
            log.info("1С:ЗУП: %d payments stored", stored)
            report("onec_zup", started, stored, None)
        except Exception as e:  # ЗУП or the core is down; the next round reads the same days again
            log.exception("1С:ЗУП connector failed")
            failures["onec_zup"] = str(e)
            report("onec_zup", started, 0, f"{type(e).__name__}: {e}")
    return failures


def kafka_publisher(bootstrap: str, registry_url: str) -> Callable[[list[contract.Message]], None]:
    from confluent_kafka import Producer

    from costguard_common.registry import SchemaRegistry
    from costguard_common.wire import Codec

    codec = Codec(SchemaRegistry(registry_url))
    producer = Producer({"bootstrap.servers": bootstrap, "enable.idempotence": True, "acks": "all"})

    def publish(messages: list[contract.Message]) -> None:
        failed: list[str] = []
        for m in messages:
            producer.produce(m.topic, key=m.key.encode(), value=codec.encode(m.topic, m.value),
                             on_delivery=lambda err, _m, failed=failed: failed.append(str(err)) if err else None)
            producer.poll(0)
        producer.flush(60)
        if failed or len(producer):
            raise RuntimeError(f"Not delivered: {failed[:3]}")

    return publish


@tracing.job("costguard-connectors", "connectors run")
def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="costguard-connector-agent")
    parser.add_argument("config", type=Path)
    parser.add_argument("--once", action="store_true", help="one round, then exit (for cron or Airflow)")
    parser.add_argument("--only", help="comma-separated system types to pull, such as onec,okta (with --once: the "
                                       "manual sync DAG costguard_connector_sync)")
    parser.add_argument("--skip", default="", help="comma-separated system types to leave out, such as "
                                                   "google_workspace,merge (the hourly DAG: those run daily)")
    args = parser.parse_args(argv)
    only = {x for x in args.only.split(",") if x} if args.only else None
    skip = {x for x in args.skip.split(",") if x}
    logs.configure()

    config = tomllib.loads(args.config.read_text("utf-8"))
    env = dict(os.environ)
    prefix = env.get("COSTGUARD_ENV_PREFIX")
    if prefix:  # Airflow runs one agent per tenant: ROMASHKA_ONEC_PASSWORD becomes ONEC_PASSWORD
        env.update({k.removeprefix(prefix): v for k, v in os.environ.items() if k.startswith(prefix)})
    tenant = uuid.UUID(config["tenant_id"])
    state = State(Path(config.get("state_file", "connector-state.json")))
    bootstrap = env.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
    registry = env.get("SCHEMA_REGISTRY_URL", "http://localhost:8085")
    publish = kafka_publisher(bootstrap, registry)
    report = Reporter(tenant, publish, env)
    from_core = bool(config.get("from_core", True))

    def secrets() -> dict[str, str]:
        """The environment with the secrets kept in the core (ADR 0030), when the agent has a core token."""
        if not from_core or not env.get("CORE_URL") or not env.get("CORE_TOKEN"):
            return env
        try:
            with httpx.Client(base_url=env["CORE_URL"], timeout=30,
                              headers={"Authorization": f"Bearer {env['CORE_TOKEN']}"}) as core:
                return control.with_secrets(env, control.core_secrets(core, tenant))
        except Exception:  # the core is out of reach; secrets in the environment still work
            log.exception("Cannot read the secrets kept in the core; using the environment")
            return env

    def connections() -> dict[str, dict]:
        if not from_core:
            return {}
        try:
            return control.read_config(bootstrap, registry, tenant)
        except Exception:  # the core's list is out of reach; the file alone still works
            log.exception("Cannot read %s; pulling what the file says", control.CONFIG)
            return {}

    interval = 60 * int(config.get("interval_minutes", 60))
    next_full = 0.0
    while True:
        known = connections()
        merged, report.ids = control.merge(config, known)
        merged = {k: v for k, v in merged.items() if k not in skip}
        round_env = report.env = secrets()
        if args.once or time.monotonic() >= next_full:
            failures = round_(merged, round_env, tenant, state, publish, report, only=only)
            if args.once:
                raise SystemExit(1 if failures else 0)
            next_full = time.monotonic() + interval
        elif due := control.due(known, report.started):
            log.info("Sync asked for %s", ", ".join(sorted(due)))
            round_(merged, round_env, tenant, state, publish, report, only=due)
        time.sleep(60)

if __name__ == "__main__":
    main()
