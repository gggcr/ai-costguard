"""ISO 20022 CAMT.053 bank-to-customer statements, any version (camt.053.001.02 to .001.12).

Each Ntry is one booking; a batch booking has several TxDtls, and each becomes its own payment. Only debits
(CdtDbtInd DBIT) that are booked are payments. The creditor is RltdPties/Cdtr (Cdtr/Pty in newer versions); its
INN is an Othr/Id of 10 or 12 digits. Namespaces differ by version, so tags are matched by local name.

The file comes from outside: a DOCTYPE is refused before parsing, so no entity can expand.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET

from costguard_ingest.statements import Payment, decode


def looks_like(content: bytes) -> bool:
    head = content[:2048]
    return b"camt.053" in head or (b"<BkToCstmrStmt" in content[:8192])


def _local(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _child(node: ET.Element | None, *path: str) -> ET.Element | None:
    for name in path:
        if node is None:
            return None
        node = next((c for c in node if _local(c.tag) == name), None)
    return node


def _children(node: ET.Element | None, name: str) -> list[ET.Element]:
    return [] if node is None else [c for c in node if _local(c.tag) == name]


def _text(node: ET.Element | None, *path: str) -> str:
    found = _child(node, *path)
    return "" if found is None or found.text is None else " ".join(found.text.split())


def _day(node: ET.Element | None) -> str:
    raw = _text(node, "Dt") or _text(node, "DtTm")
    match = re.match(r"(\d{4})-(\d{2})-(\d{2})", raw)
    return f"{match.group(3)}.{match.group(2)}.{match.group(1)}" if match else ""


def _inn(party: ET.Element | None) -> str:
    for other in (party.iter() if party is not None else []):
        if _local(other.tag) == "Othr":
            value = _text(other, "Id")
            if re.fullmatch(r"\d{10}|\d{12}", value):
                return value
    return ""


def _either(first: ET.Element | None, second: ET.Element | None) -> ET.Element | None:
    """An element with no children is false, so `first or second` would skip a found one."""
    return first if first is not None else second


def _creditor(tx: ET.Element) -> ET.Element | None:
    party = _child(tx, "RltdPties", "Cdtr")
    return _either(_child(party, "Pty"), party)


def payments(content: bytes) -> list[Payment]:
    if b"<!DOCTYPE" in content or b"<!ENTITY" in content:
        raise ValueError("A CAMT.053 file with a DOCTYPE is not read")
    root = ET.fromstring(decode(content).encode("utf-8"))
    out: list[Payment] = []
    for statement in (e for e in root.iter() if _local(e.tag) == "Stmt"):
        for entry in _children(statement, "Ntry"):
            if _text(entry, "CdtDbtInd") != "DBIT" or _text(entry, "RvslInd") == "true":
                continue
            status = _text(entry, "Sts") or _text(entry, "Sts", "Cd")
            if status and status not in ("BOOK", "Booked"):
                continue
            amount = _child(entry, "Amt")
            day = _day(_child(entry, "BookgDt")) or _day(_child(entry, "ValDt"))
            details = [tx for d in _children(entry, "NtryDtls") for tx in _children(d, "TxDtls")]
            if not details:
                out.append(Payment("", _text(entry, "AddtlNtryInf"), _amount(amount), day,
                                   _text(entry, "AcctSvcrRef"), "", _currency(amount)))
                continue
            for tx in details:
                tx_amount = _either(_either(_child(tx, "AmtDtls", "TxAmt", "Amt"), _child(tx, "Amt")), amount)
                creditor = _creditor(tx)
                purpose = " ".join(u.text.strip() for u in _children(_child(tx, "RmtInf"), "Ustrd")
                                   if u.text) or _text(entry, "AddtlNtryInf")
                number = (_text(tx, "Refs", "EndToEndId") if _text(tx, "Refs", "EndToEndId") != "NOTPROVIDED"
                          else "") or _text(tx, "Refs", "InstrId") or _text(entry, "AcctSvcrRef")
                out.append(Payment(_text(creditor, "Nm"), purpose, _amount(tx_amount), day, number,
                                   _inn(creditor), _currency(tx_amount)))
    return out


def _amount(node: ET.Element | None) -> str:
    return "" if node is None or node.text is None else node.text.strip()


def _currency(node: ET.Element | None) -> str:
    return "" if node is None else node.get("Ccy", "")
