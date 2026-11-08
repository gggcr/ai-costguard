"""Payments through intermediaries: a payment to an agent that pays a foreign service on the company's behalf.

The bank sees the agent as the payee, but the money is for the service named in the purpose ("Оплата подписки
Notion по агентскому договору…"). Such a payment is split before it reaches Kafka (ADR 0015):

- the service: the PAYMENT entity and the cost record name the service, so the core merges it with the service's
  vendor by name, as any other payment (ADR 0008);
- the commission of the agent: a separate PAYMENT and cost record for the agent, but only when the purpose states
  its amount ("в т.ч. вознаграждение агента 350 руб."). Otherwise the whole amount goes to the service and the
  payment is marked commission_unknown: a guessed commission would be a wrong number in the reports.

An intermediary is a payee whose INN or name is in the configured list, or any payee whose purpose speaks of an
agency contract. The service is found by its name in the purpose or the card descriptor: a dictionary of known
services plus the names a company adds. Nothing is split when no service is seen.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation

from costguard_common import normalized

# Product name as people write it in purposes and card descriptors -> the vendor as invoices name it.
SERVICES: dict[str, str] = {
    "chatgpt": "OpenAI", "openai": "OpenAI", "anthropic": "Anthropic", "claude.ai": "Anthropic",
    "midjourney": "Midjourney", "notion": "Notion", "slack": "Slack", "zoom": "Zoom", "figma": "Figma",
    "miro": "Miro", "github": "GitHub", "gitlab": "GitLab", "atlassian": "Atlassian", "jira": "Atlassian",
    "confluence": "Atlassian", "trello": "Atlassian", "adobe": "Adobe", "canva": "Canva", "dropbox": "Dropbox",
    "asana": "Asana", "monday.com": "monday.com", "hubspot": "HubSpot", "salesforce": "Salesforce",
    "mailchimp": "Mailchimp", "twilio": "Twilio", "jetbrains": "JetBrains", "docker": "Docker",
    "vercel": "Vercel", "heroku": "Heroku", "digitalocean": "DigitalOcean", "hetzner": "Hetzner",
    "cloudflare": "Cloudflare", "amazon web services": "Amazon Web Services", "aws": "Amazon Web Services",
    "google workspace": "Google", "google cloud": "Google", "microsoft 365": "Microsoft", "office 365": "Microsoft",
    "azure": "Microsoft", "grammarly": "Grammarly", "deepl": "DeepL", "loom": "Loom", "calendly": "Calendly",
    "1password": "1Password", "linear": "Linear", "intercom": "Intercom", "zendesk": "Zendesk",
}

_AGENCY = re.compile(r"агентск|посредни|по поручению|в интересах принципала|agency agreement", re.IGNORECASE)
_COMMISSION = re.compile(
    r"(?:комисси[яиюей]\s*(?:агента|посредника|сервиса)?|(?:агентское\s+)?вознаграждени[ея]\s*(?:агента)?)"
    r"\s*(?:составляет|[:\-–—])?\s*(\d[\d\s]*(?:[.,]\d{1,2})?)\s*(?:руб|₽|rub)", re.IGNORECASE)


@dataclass(frozen=True)
class Intermediary:
    name: str
    inn: str | None = None


@dataclass(frozen=True)
class Part:
    """One share of a payment. role: "payment" (not split), "service" or "commission"."""

    vendor: str
    inn: str | None
    amount: Decimal
    role: str
    intermediary: str | None = None
    note: str | None = None


def _amount(text: str) -> Decimal | None:
    try:
        return Decimal(re.sub(r"\s", "", text).replace(",", "."))
    except InvalidOperation:
        return None


def service_in(text: str, extra: dict[str, str] | None = None) -> str | None:
    """The vendor of the first known service named in the text, by whole words."""
    lowered = f" {text.lower().replace('ё', 'е')} "
    names = {**SERVICES, **{k.lower(): v for k, v in (extra or {}).items()}}
    best: tuple[int, str] | None = None
    for alias, vendor in names.items():
        match = re.search(rf"(?<![\w.]){re.escape(alias)}(?![\w])", lowered)
        if match and (best is None or match.start() < best[0]):
            best = (match.start(), vendor)
    return best[1] if best else None


def commission_in(purpose: str) -> Decimal | None:
    match = _COMMISSION.search(purpose)
    return _amount(match.group(1)) if match else None


def is_intermediary(payee: str, inn: str | None, purpose: str, known: Iterable[Intermediary]) -> bool:
    code = normalized.clean_inn(inn)
    name = normalized.clean_name(payee)
    for i in known:
        if (code and normalized.clean_inn(i.inn) == code) or (name and normalized.clean_name(i.name) == name):
            return True
    return bool(_AGENCY.search(purpose))


def split(payee: str, inn: str | None, purpose: str, amount: Decimal, known: Iterable[Intermediary] = (),
          services: dict[str, str] | None = None) -> list[Part]:
    """The parts a payment counts as; one part when it is not through an intermediary or no service is seen."""
    whole = [Part(payee, normalized.clean_inn(inn), amount, "payment")]
    if not is_intermediary(payee, inn, purpose, known):
        return whole
    service = service_in(purpose, services)
    if service is None or normalized.clean_name(service) == normalized.clean_name(payee):
        return [Part(payee, normalized.clean_inn(inn), amount, "payment", note="service_unknown")]
    commission = commission_in(purpose)
    agent = payee
    if commission is None or not (Decimal(0) < commission < amount):
        return [Part(service, None, amount, "service", agent, "commission_unknown")]
    return [Part(service, None, amount - commission, "service", agent),
            Part(agent, normalized.clean_inn(inn), commission, "commission", agent)]


def intermediaries_of(text: str) -> list[Intermediary]:
    """From configuration: "7700000009, ООО Агент Плюс" — INNs and names, comma or semicolon separated."""
    out = []
    for item in re.split(r"[;,\n]", text or ""):
        if item.strip():
            out.append(intermediary(item))
    return out


def intermediary(item: str) -> Intermediary:
    """One configured agent: an INN or a name."""
    item = item.strip()
    return Intermediary(item, item) if normalized.clean_inn(item) else Intermediary(item)
