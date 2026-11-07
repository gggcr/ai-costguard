"""Values as people type them in Russian and English spreadsheets. Each parser says how sure it is.

1.0 means the text was unambiguous. Below CONFIDENT the value goes to people as a review task.
"""

from __future__ import annotations

import re
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

CONFIDENT = 0.9

# Letters that OCR and hurried typing put in place of digits.
_LOOKALIKES = str.maketrans({"O": "0", "О": "0", "L": "1", "I": "1", "З": "3", "S": "5", "B": "8"})
_SPACES = re.compile(r"[\s  ']")
_CURRENCY_SIGNS = {
    "₽": "RUB", "руб": "RUB", "р.": "RUB", "rub": "RUB", "rur": "RUB",
    "$": "USD", "usd": "USD", "€": "EUR", "eur": "EUR", "¥": "CNY", "cny": "CNY", "юань": "CNY",
}
_NOT_A_NUMBER = re.compile(r"[^\d.,\-+]")


def amount(value: object) -> tuple[Decimal | None, float]:
    if isinstance(value, bool) or value is None:
        return None, 0.0
    if isinstance(value, int | float | Decimal):
        return Decimal(str(value)), 1.0
    raw = str(value).strip()
    if not raw:
        return None, 0.0
    confidence = 1.0
    cleaned = raw.lower()
    for sign in _CURRENCY_SIGNS:
        cleaned = cleaned.replace(sign, "")
    cleaned = _SPACES.sub("", cleaned)
    negative = cleaned.startswith("(") and cleaned.endswith(")")
    cleaned = cleaned.strip("()")
    if _NOT_A_NUMBER.search(cleaned):
        fixed = cleaned.upper().translate(_LOOKALIKES)
        if _NOT_A_NUMBER.search(fixed) or not re.search(r"\d", cleaned):
            return None, 0.0
        cleaned, confidence = fixed, 0.4
    if "," in cleaned and "." in cleaned:
        # The last separator is the decimal one: 1.234,56 or 1,234.56.
        if cleaned.rfind(",") > cleaned.rfind("."):
            cleaned = cleaned.replace(".", "").replace(",", ".")
        else:
            cleaned = cleaned.replace(",", "")
    elif cleaned.count(",") == 1:
        cleaned = cleaned.replace(",", ".")
    elif cleaned.count(",") > 1 or cleaned.count(".") > 1:
        cleaned = cleaned.replace(",", "").replace(".", "")
        confidence = min(confidence, 0.6)
    try:
        number = Decimal(cleaned)
    except InvalidOperation:
        return None, 0.0
    return (-number if negative else number), confidence


def currency(value: object) -> tuple[str | None, float]:
    if value is None:
        return None, 0.0
    raw = str(value).strip().lower()
    if not raw:
        return None, 0.0
    if re.fullmatch(r"[a-z]{3}", raw):
        return raw.upper(), 1.0
    for sign, code in _CURRENCY_SIGNS.items():
        if sign in raw:
            return code, 0.95
    return None, 0.0


def currency_in(value: object) -> str | None:
    """A currency sign inside an amount cell, such as '1 500 ₽'."""
    if value is None or isinstance(value, int | float | Decimal):
        return None
    raw = str(value).lower()
    return next((code for sign, code in _CURRENCY_SIGNS.items() if sign in raw), None)


_DATE_FORMATS = [("%Y-%m-%d", 1.0), ("%d.%m.%Y", 1.0), ("%d.%m.%y", 0.95), ("%Y/%m/%d", 1.0), ("%d/%m/%Y", 0.7),
                 ("%m/%d/%Y", 0.5), ("%Y-%m", 0.95), ("%m.%Y", 0.95)]


def day(value: object) -> tuple[date | None, float]:
    if isinstance(value, datetime):
        return value.date(), 1.0
    if isinstance(value, date):
        return value, 1.0
    if value is None:
        return None, 0.0
    raw = str(value).strip()
    if not raw:
        return None, 0.0
    raw = raw.split("T")[0].split(" ")[0]
    for fmt, confidence in _DATE_FORMATS:
        try:
            parsed = datetime.strptime(raw, fmt).date()
        except ValueError:
            continue
        if fmt == "%d/%m/%Y" and parsed.day > 12:
            confidence = 1.0
        return parsed, confidence
    return None, 0.0


def text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()
