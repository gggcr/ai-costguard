"""OFX statements, both the SGML of OFX 1.x (tags that are never closed) and the XML of OFX 2.x.

Every STMTTRN is one transaction; a negative TRNAMT is money out, so only those are payments. NAME (or PAYEE/NAME)
is the payee and MEMO the purpose; CURDEF of the statement is the currency. Tags are read with one pattern that
works for both syntaxes, so no XML parser and no DTD is involved.
"""

from __future__ import annotations

import re

from costguard_ingest.statements import Payment, decode

_BLOCK = re.compile(r"<STMTTRN>(.*?)(?:</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))", re.DOTALL | re.IGNORECASE)
_STATEMENT = re.compile(r"<(?:STMTRS|CCSTMTRS)>(.*?)(?:</(?:STMTRS|CCSTMTRS)>|$)", re.DOTALL | re.IGNORECASE)


def looks_like(content: bytes) -> bool:
    head = content[:1024].upper()
    return b"OFXHEADER" in head or b"<OFX>" in head or b"<?OFX" in head


def _tag(block: str, name: str) -> str:
    match = re.search(rf"<{name}>([^<\r\n]*)", block, re.IGNORECASE)
    return " ".join(match.group(1).split()) if match else ""


def _day(raw: str) -> str:
    return f"{raw[6:8]}.{raw[4:6]}.{raw[0:4]}" if re.match(r"\d{8}", raw) else ""


def payments(content: bytes) -> list[Payment]:
    text = decode(content)
    out: list[Payment] = []
    statements = _STATEMENT.findall(text) or [text]
    for statement in statements:
        currency = _tag(statement, "CURDEF").upper()
        for block in _BLOCK.findall(statement):
            raw = _tag(block, "TRNAMT").replace(",", ".")
            if not raw.startswith("-"):
                continue
            payee = _tag(block, "NAME")
            out.append(Payment(payee, _tag(block, "MEMO"), raw.lstrip("-"), _day(_tag(block, "DTPOSTED")),
                               _tag(block, "CHECKNUM") or _tag(block, "FITID"), "",
                               _tag(block, "CURSYM").upper() or currency))
    return out
