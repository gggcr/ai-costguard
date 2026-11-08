"""Т-Банк for business (T-Business API) as a `banks.Bank` (ADR 0015).

The company issues a token in its T-Business account (read-only scopes for accounts and statements); it goes in
`Authorization: Bearer`. Accounts come from /api/v4/bank-accounts, operations from /api/v1/statement, which pages
with `nextCursor`. A card operation carries the masked card number and the merchant; an SBP payment is marked by
its category or description.

The shapes follow the bank's public documentation and were tested on recorded answers, not on a live account:
before the first company, check the category names against the bank's sandbox.
"""

from __future__ import annotations

from collections.abc import Iterable
from datetime import UTC, date, datetime, time
from decimal import Decimal, InvalidOperation

import httpx

from costguard_connectors.banks import Account, Kind, Operation

BASE_URL = "https://business.tbank.ru/openapi"
CURRENCIES = {"643": "RUB", "840": "USD", "978": "EUR", "156": "CNY", "398": "KZT", "933": "BYN", "949": "TRY",
              "784": "AED"}


def _currency(code: object) -> str:
    text = str(code or "").strip()
    return CURRENCIES.get(text, text.upper() if len(text) == 3 and text.isalpha() else "RUB")


def kind_of(op: dict) -> Kind:
    category = str(op.get("category") or "").lower()
    text = f"{op.get('description') or ''} {op.get('payPurpose') or ''}".lower()
    if "selftransfer" in category:
        return Kind.OWN
    if "sbp" in category or "сбп" in text or "системы быстрых платежей" in text:
        return Kind.SBP
    if op.get("cardNumber") or op.get("merchant") or "card" in category:
        return Kind.CARD
    if category in ("fee", "commission") or category.endswith("fee"):
        return Kind.FEE
    if category in ("contragentoutcome", "contragentpeople", "tax", "salary", "budget"):
        return Kind.TRANSFER
    return Kind.OTHER if not op.get("counterParty") else Kind.TRANSFER


class TBank:
    name = "tbank"

    def __init__(self, client: httpx.Client, accounts: list[str] | None = None) -> None:
        """client carries base_url (BASE_URL) and the bearer token. accounts limits the pull to these numbers."""
        self._http = client
        self._only = set(accounts or [])

    def accounts(self) -> list[Account]:
        response = self._http.get("/api/v4/bank-accounts")
        response.raise_for_status()
        out = []
        for a in response.json():
            number = str(a.get("accountNumber", ""))
            if number and (not self._only or number in self._only):
                out.append(Account(number, number, _currency(a.get("currency")), a.get("name") or ""))
        return out

    def operations(self, account: Account, start: date, end: date) -> Iterable[Operation]:
        cursor = None
        while True:
            params = {"accountNumber": account.number, "limit": 1000,
                      "from": datetime.combine(start, time.min, UTC).isoformat().replace("+00:00", "Z"),
                      "to": datetime.combine(end, time.max, UTC).isoformat().replace("+00:00", "Z")}
            if cursor:
                params["cursor"] = cursor
            response = self._http.get("/api/v1/statement", params=params)
            response.raise_for_status()
            body = response.json()
            for op in body.get("operations", []):
                parsed = self._operation(account, op)
                if parsed is not None:
                    yield parsed
            cursor = body.get("nextCursor")
            if not cursor:
                return

    def _operation(self, account: Account, op: dict) -> Operation | None:
        try:
            amount = abs(Decimal(str(op.get("operationAmount", op.get("accountAmount")))))
        except (InvalidOperation, TypeError):
            return None
        when = str(op.get("operationDate") or op.get("trxnPostDate") or "")[:10]
        try:
            day = date.fromisoformat(when)
        except ValueError:
            return None
        party = op.get("counterParty") or {}
        merchant = op.get("merchant") or {}
        return Operation(
            id=str(op.get("operationId")), account=account.id, day=day, amount=amount,
            currency=_currency(op.get("operationCurrencyDigitalCode") or op.get("accountCurrencyDigitalCode")
                               or account.currency),
            outgoing=str(op.get("typeOfOperation", "")).lower() == "debit", kind=kind_of(op),
            counterparty=party.get("name") or "", inn=party.get("inn") or None,
            purpose=op.get("payPurpose") or op.get("description") or "", card=op.get("cardNumber") or None,
            merchant=merchant.get("name") or None, mcc=str(op["mcc"]) if op.get("mcc") else None)
