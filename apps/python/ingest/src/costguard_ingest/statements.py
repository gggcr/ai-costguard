"""Bank statements in the 1C exchange format (1CClientBankExchange), which every Russian bank exports.

Only payments from the company's own accounts are costs; incoming money is left out. The columns are named so
mapping.suggest maps them without help: recipient to vendor, purpose to product.
"""

from __future__ import annotations

MARKER = "1CClientBankExchange"
COLUMNS = ["Получатель", "Назначение платежа", "Сумма", "Дата", "Номер", "ИНН получателя"]


def _decode(content: bytes) -> str:
    for encoding in ("utf-8-sig", "cp1251"):
        try:
            return content.decode(encoding)
        except UnicodeDecodeError:
            continue
    return content.decode("latin-1")


def is_statement(content: bytes) -> bool:
    return MARKER.encode() in content[:64]


def lines(content: bytes) -> list[list[str]]:
    """The header and one line per outgoing payment, in the order of the file."""
    own: set[str] = set()
    documents: list[dict[str, str]] = []
    current: dict[str, str] | None = None
    for raw in _decode(content).splitlines():
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
    out = [list(COLUMNS)]
    for d in documents:
        payer = d.get("ПлательщикСчет") or d.get("ПлательщикРасчСчет", "")
        if own and payer not in own:
            continue
        out.append([d.get("Получатель1") or d.get("Получатель", ""), d.get("НазначениеПлатежа", ""),
                    d.get("Сумма", ""), d.get("ДатаСписано") or d.get("Дата", ""), d.get("Номер", ""),
                    d.get("ПолучательИНН", "")])
    return out
