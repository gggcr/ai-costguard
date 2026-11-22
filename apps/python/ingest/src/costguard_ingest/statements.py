"""Bank statements: the 1C exchange format (1CClientBankExchange) that every Russian bank exports, SWIFT MT940,
ISO 20022 CAMT.053 and OFX.

Every format becomes the same table. Only payments from the company's own accounts are costs; incoming money is
left out. The columns are named so mapping.suggest maps them without help: recipient to vendor, purpose to product,
currency to currency. A 1C statement names no currency: it is the exchange format of rouble accounts, so its lines
say RUB. A payment of another format without a currency is in the company's currency (ADR 0072).
"""

from __future__ import annotations

from dataclasses import dataclass

MARKER = "1CClientBankExchange"
COLUMNS = ["Получатель", "Назначение платежа", "Сумма", "Дата", "Номер", "ИНН получателя", "Валюта"]


@dataclass(frozen=True)
class Payment:
    """One outgoing payment. amount is a plain decimal with a dot, day is dd.mm.yyyy, currency ISO 4217 or ""."""

    payee: str
    purpose: str
    amount: str
    day: str
    number: str = ""
    inn: str = ""
    currency: str = ""

    def line(self) -> list[str]:
        return [self.payee, self.purpose, self.amount, self.day, self.number, self.inn, self.currency]


def decode(content: bytes) -> str:
    for encoding in ("utf-8-sig", "cp1251"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("latin-1")


def kind(content: bytes) -> str | None:
    """"1c", "mt940", "camt053", "ofx", or None when the file is not a statement."""
    from costguard_ingest import camt053, mt940, ofx

    if MARKER.encode() in content[:64]:
        return "1c"
    if ofx.looks_like(content):
        return "ofx"
    if camt053.looks_like(content):
        return "camt053"
    if mt940.looks_like(content):
        return "mt940"
    return None


def is_statement(content: bytes) -> bool:
    return kind(content) is not None


def lines(content: bytes) -> list[list[str]]:
    """The header and one line per outgoing payment, in the order of the file."""
    from costguard_ingest import camt053, mt940, ofx

    readers = {"1c": _onec, "mt940": mt940.payments, "camt053": camt053.payments, "ofx": ofx.payments}
    found = kind(content)
    if found is None:
        raise ValueError("Not a bank statement")
    return [list(COLUMNS)] + [p.line() for p in readers[found](content)]


def _onec(content: bytes) -> list[Payment]:
    own: set[str] = set()
    documents: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for raw in decode(content).splitlines():
        line = raw.strip()
        if line.startswith("СекцияДокумент"):
            current = {}
            continue
        if line == "КонецДокумента":
            if current is not None:
                documents.append(current)
            current = None
            continue
        key, sep, value = line.partition("=")
        if not sep:
            continue
        if current is not None:
            current[key] = value.strip()
        elif key == "РасчСчет":
            own.add(value.strip())
    out = []
    for d in documents:
        payer = d.get("ПлательщикСчет") or d.get("ПлательщикРасчСчет", "")
        if own and payer not in own:
            continue
        out.append(Payment(d.get("Получатель1") or d.get("Получатель", ""), d.get("НазначениеПлатежа", ""),
                           d.get("Сумма", ""), d.get("ДатаСписано") or d.get("Дата", ""), d.get("Номер", ""),
                           d.get("ПолучательИНН", ""), "RUB"))
    return out
