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
    [jamf]
    url = "https://company.jamfcloud.com"
    client_id = "…"                # secret: JAMF_CLIENT_SECRET
    [intune]
    directory_id = "…"             # the Entra ID tenant
    client_id = "…"                # secret: INTUNE_CLIENT_SECRET

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

from costguard_connectors import bitrix24, contract, intune, jamf, mail, moysklad, onec, yandex360

log = logging.getLogger("costguard.connectors")


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
                         timeout=60))
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
