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
from pathlib import Path

import httpx

from costguard_common import payments
from costguard_connectors import (
    banks,
    bitrix24,
    contract,
    entra,
    google_workspace,
    intune,
    jamf,
    mail,
    merge,
    moysklad,
    okta,
    onec,
    signins,
    tbank,
    yandex360,
)

log = logging.getLogger("costguard.connectors")

# Sections of a tenant config the agent reads; Airflow adds its own [report].
SECTIONS = ("onec", "moysklad", "bitrix24", "yandex360", "mail", "jamf", "intune", "google_workspace", "okta",
            "entra", "merge", "tbank", "payments")


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


def once(connectors: list[contract.Connector], tenant: uuid.UUID, state: State,
         publish: Callable[[list[contract.Message]], None]) -> dict[str, str]:
    """Runs every connector; one failing does not stop the others. Returns the failures by connector."""
    failures: dict[str, str] = {}
    for c in connectors:
        try:
            state.put(c.name, contract.run(c, tenant, state.get(c.name), publish))
        except Exception as e:  # a connector's system is down or refuses us; the next round tries again
            log.exception("Connector %s failed", c.name)
            failures[c.name] = str(e)
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


def main(argv: list[str] | None = None) -> None:
    parser = argparse.ArgumentParser(prog="costguard-connector-agent")
    parser.add_argument("config", type=Path)
    parser.add_argument("--once", action="store_true", help="one round, then exit (for cron or Airflow)")
    args = parser.parse_args(argv)
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")

    config = tomllib.loads(args.config.read_text("utf-8"))
    env = dict(os.environ)
    prefix = env.get("COSTGUARD_ENV_PREFIX")
    if prefix:  # Airflow runs one agent per tenant: ROMASHKA_ONEC_PASSWORD becomes ONEC_PASSWORD
        env.update({k.removeprefix(prefix): v for k, v in os.environ.items() if k.startswith(prefix)})
    tenant = uuid.UUID(config["tenant_id"])
    connectors = build(config, env)
    state = State(Path(config.get("state_file", "connector-state.json")))
    publish = kafka_publisher(env.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092"),
                              env.get("SCHEMA_REGISTRY_URL", "http://localhost:8085"))
    inbox = None
    if "mail" in config:
        c = config["mail"]
        inbox = mail.MailInvoices(
            mail.Imap(c["host"], c["user"], env["MAIL_PASSWORD"], c.get("folder", "INBOX")),
            httpx.Client(base_url=env["CORE_URL"], headers={"Authorization": f"Bearer {env['CORE_TOKEN']}"},
                         timeout=60),
            int(c.get("min_image_kb", mail.MIN_IMAGE_KB)), int(c.get("min_image_side", mail.MIN_IMAGE_SIDE)))
    while True:
        failures = once(connectors, tenant, state, publish)
        if inbox is not None:
            try:
                log.info("Mail: %d new files uploaded", inbox.collect())
            except Exception:  # the mailbox is down; the next round tries again
                log.exception("Mail connector failed")
                failures["mail"] = "see log"
        if args.once:
            raise SystemExit(1 if failures else 0)
        time.sleep(60 * int(config.get("interval_minutes", 60)))


if __name__ == "__main__":
    main()
