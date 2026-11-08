"""What a bank connector does, whatever the bank: accounts, then the operations of each, as payments (ADR 0015).

A bank implements `Bank`; `BankConnector` turns its operations into the general connector contract. Money out is
a payment: transfers, card operations (any card network, «Мир» included, told by the card number), SBP payments
and bank fees. Money in and transfers between the company's own accounts are not costs and are left out.

Every outgoing operation goes through costguard_common.payments.split, so a payment to an intermediary counts
as the service it paid for, and the agent's commission separately when its amount is stated.

The cursor keeps, per account, the day of the latest operation read; the next pull starts on that day again, so an
operation posted late is not missed, and the stable keys make the repeat harmless.
"""

from __future__ import annotations

import json
import uuid
from collections.abc import Iterable
from dataclasses import dataclass
from datetime import UTC, date, datetime, timedelta
from decimal import Decimal
from enum import StrEnum
from typing import Protocol

from costguard_common import normalized
from costguard_common import payments as paying
from costguard_common.focus import CostSource
from costguard_connectors import entities
from costguard_connectors.contract import Batch

LOOKBACK_DAYS = 90


class Kind(StrEnum):
    TRANSFER = "TRANSFER"
    CARD = "CARD"
    SBP = "SBP"
    FEE = "FEE"
    OWN = "OWN"            # between the company's own accounts
    OTHER = "OTHER"


@dataclass(frozen=True)
class Account:
    id: str
    number: str
    currency: str
    name: str = ""


@dataclass(frozen=True)
class Operation:
    id: str
    account: str
    day: date
    amount: Decimal               # always positive
    currency: str
    outgoing: bool
    kind: Kind
    counterparty: str = ""
    inn: str | None = None
    purpose: str = ""
    card: str | None = None       # masked number, 220070******1234
    merchant: str | None = None
    mcc: str | None = None


class Bank(Protocol):
    name: str

    def accounts(self) -> list[Account]: ...

    def operations(self, account: Account, start: date, end: date) -> Iterable[Operation]: ...


def card_network(card: str | None) -> str | None:
    """The network of a card by its first digits: МИР 2200–2204, Visa 4, Mastercard 51–55 and 2221–2720,
    UnionPay 62."""
    digits = "".join(c for c in card or "" if c.isdigit())[:6]
    if len(digits) < 4:
        return None
    four = int(digits[:4])
    if 2200 <= four <= 2204:
        return "MIR"
    if digits[0] == "4":
        return "VISA"
    if 51 <= int(digits[:2]) <= 55 or 2221 <= four <= 2720:
        return "MASTERCARD"
    if digits[:2] == "62":
        return "UNIONPAY"
    return None


class BankConnector:
    def __init__(self, bank: Bank, intermediaries: Iterable[paying.Intermediary] = (),
                 services: dict[str, str] | None = None, today=lambda: datetime.now(UTC).date()) -> None:
        self.bank = bank
        self.name = bank.name
        self._intermediaries = list(intermediaries)
        self._services = services or {}
        self._today = today

    def pull(self, tenant: uuid.UUID, cursor: str | None) -> Batch:
        days = json.loads(cursor) if cursor else {}
        end = self._today()
        batch = Batch()
        for account in self.bank.accounts():
            start = date.fromisoformat(days[account.id]) if account.id in days else end - timedelta(LOOKBACK_DAYS)
            latest = start
            for op in self.bank.operations(account, start, end):
                latest = max(latest, op.day)
                if op.outgoing and op.kind != Kind.OWN:
                    self._payment(tenant, op, batch)
            days[account.id] = latest.isoformat()
        batch.cursor = json.dumps(days, sort_keys=True)
        return batch

    def _payment(self, tenant: uuid.UUID, op: Operation, batch: Batch) -> None:
        payee = op.merchant or op.counterparty or op.purpose or "unknown"
        text = " ".join(t for t in (op.merchant, op.purpose) if t)
        for part in paying.split(payee, op.inn, text, op.amount, self._intermediaries, self._services):
            suffix = ("commission",) if part.role == "commission" else ()
            vendor = entities.vendor(tenant, part.vendor, part.inn, source=self.name)
            batch.entities.append(vendor)
            batch.entities.append(normalized.Entity(
                normalized.EntityType.PAYMENT, normalized.key(tenant, "payment", self.name, op.id, *suffix),
                {"vendor": part.vendor, "inn": part.inn, "amount": str(part.amount), "currency": op.currency,
                 "date": op.day.isoformat(), "purpose": op.purpose or None, "kind": op.kind.value,
                 "card": op.card, "card_network": card_network(op.card), "mcc": op.mcc,
                 "intermediary": part.intermediary, "role": part.role, "note": part.note, "source": self.name}))
            batch.costs.append(entities.cost(tenant, self.name, ":".join((op.id, *suffix)), part.amount,
                                             op.currency, op.day, part.vendor, CostSource.PAYMENT))
