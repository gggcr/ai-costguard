"""SWIFT MT940 customer statements.

A statement is a run of tags, each starting a line as :NN: or :NNa:. :25: is the account, :60F: the opening
balance (its currency is the statement's), :61: one entry and :86: the details of the entry before it. Only debit
entries (mark D) are payments; reversals and credits are not.

:86: has no single layout. Three are read: German-style subfields (?20…?29 purpose, ?32/?33 name), SWIFT keywords
(/NAME/, /REMI/, /INN/) and free text, where "ИНН" and "Получатель:" are looked for.
"""

from __future__ import annotations

import re

from costguard_ingest.statements import Payment, decode

_TAG = re.compile(r"^:(\d{2}[A-Z]?):", re.MULTILINE)
# :61: value date YYMMDD, entry date MMDD, mark, funds code, amount, type, customer ref [//bank ref]
_ENTRY = re.compile(r"^(?P<value>\d{6})(?P<entry>\d{4})?(?P<mark>RC|RD|C|D)(?P<funds>[A-Z])?"
                    r"(?P<amount>\d+(?:,\d*)?)(?P<type>[NSF][A-Z0-9]{3})(?P<ref>[^/\n]*)")
_BALANCE = re.compile(r"^[CD]\d{6}(?P<currency>[A-Z]{3})")
_INN = re.compile(r"ИНН\s*[:№]?\s*(\d{12}|\d{10})\b")
_PAYEE = re.compile(r"(?:Получатель|Payee|Beneficiary)\s*[:\-]\s*([^;\n/]+)", re.IGNORECASE)


def looks_like(content: bytes) -> bool:
    text = decode(content[:4096])
    tagged = bool(re.search(r"^:20:", text, re.MULTILINE)) and bool(re.search(r"^:61:", text, re.MULTILINE))
    return tagged or (text.lstrip().startswith("{1:F01") and ":61:" in text)


def _fields(text: str) -> list[tuple[str, str]]:
    """Tags and their values; a value runs over continuation lines until the next tag or the end of a block."""
    body = re.sub(r"\{[1-3]:[^}]*\}|\{4:|-\}|\{5:[^}]*\}", "\n", text)
    matches = list(_TAG.finditer(body))
    out = []
    for i, m in enumerate(matches):
        end = matches[i + 1].start() if i + 1 < len(matches) else len(body)
        out.append((m.group(1), body[m.end():end].strip("\r\n")))
    return out


def _day(yymmdd: str) -> str:
    return f"{yymmdd[4:6]}.{yymmdd[2:4]}.20{yymmdd[0:2]}"


def details(info: str) -> tuple[str, str, str]:
    """Payee, purpose and INN from :86:."""
    flat = " ".join(line.strip() for line in info.splitlines())
    inn_match = _INN.search(flat)
    inn = inn_match.group(1) if inn_match else ""
    if "?" in flat and re.search(r"\?\d\d", flat):
        parts = dict(re.findall(r"\?(\d\d)([^?]*)", flat))
        purpose = "".join(parts.get(f"{n}", "") for n in range(20, 30)).strip()
        payee = (parts.get("32", "") + parts.get("33", "")).strip()
        return payee, purpose, inn
    if re.match(r"^/[A-Z]{3,4}/", flat):
        parts = {k: v.strip() for k, v in re.findall(r"/([A-Z]{3,4})/([^/]*)", flat)}
        inn = inn or (parts.get("INN", "") if re.fullmatch(r"\d{10}|\d{12}", parts.get("INN", "")) else "")
        return parts.get("NAME", ""), parts.get("REMI", "") or parts.get("PURP", ""), inn
    payee_match = _PAYEE.search(info)  # by line: a name ends where its line does
    return (payee_match.group(1).strip() if payee_match else ""), flat, inn


def payments(content: bytes) -> list[Payment]:
    currency = ""
    out: list[Payment] = []
    pending: dict | None = None

    def flush(info: str = "") -> None:
        nonlocal pending
        if pending is not None:
            payee, purpose, inn = details(info)
            out.append(Payment(payee, purpose, pending["amount"], pending["day"], pending["number"], inn, currency))
        pending = None

    for tag, value in _fields(decode(content)):
        if tag in ("60F", "60M"):
            flush()
            balance = _BALANCE.match(value.strip())
            currency = balance.group("currency") if balance else currency
        elif tag == "61":
            flush()
            entry = _ENTRY.match(value.strip())
            if entry is None or entry.group("mark") != "D":
                continue
            reference = entry.group("ref").strip()
            pending = {"amount": entry.group("amount").replace(",", ".").rstrip("."),
                       "day": _day(entry.group("value")),
                       "number": "" if reference.upper() == "NONREF" else reference}
        elif tag == "86":
            flush(value)
        else:
            flush()
    flush()
    return out
