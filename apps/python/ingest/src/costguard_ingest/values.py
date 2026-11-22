"""Values as people type them in spreadsheets of any country. Each parser says how sure it is.

1.0 means the text was unambiguous. Below CONFIDENT the value goes to people as a review task.

How a number or a date is read depends on who wrote it (ADR 0072): 1,500 is fifteen hundred in the United States and
one and a half in Russia, 03/04/2026 is March 4 there and 3 April in Britain. A Style says it: the company's country
gives one (style_of), and the other values of the same column can settle it better (amounts_style, dates_style). A
value only the style can decide, and no style decides, goes to people.
"""

from __future__ import annotations

import re
from collections.abc import Iterable
from dataclasses import dataclass, replace
from datetime import date, datetime
from decimal import Decimal, InvalidOperation

CONFIDENT = 0.9


@dataclass(frozen=True)
class Style:
    """How numbers and dates are written. decimal and group are the separators of 1,234.56 (decimal ".", group ",");
    month_first says whether 03/04/2026 is March 4 (True) or 3 April (False). None: not known. settled: the values
    of the column showed the separators themselves, which beats what the country usually writes. exact: values a
    program wrote, such as bank statements, where a dot is always the decimal separator."""

    decimal: str | None = None
    group: str | None = None
    month_first: bool | None = None
    settled: bool = False
    exact: bool = False


UNKNOWN = Style()
EXACT = Style(decimal=".", exact=True)

# 1,234.56: the decimal dot.
_DOT_COMMA = frozenset("US GB IE AU NZ CA IN PK BD LK NP SG MY PH TH HK MO CN TW JP KR IL MX GT HN SV NI DO PR PA "
                       "NG GH KE UG TZ MT AE SA QA KW BH OM JO".split())
# 1.234,56
_COMMA_DOT = frozenset("DE AT NL BE LU IT ES DK IS GR TR BR AR CL CO UY PY VE EC BO ID VN RS HR SI BA ME MK RO".split())
# 1 234,56
_COMMA_SPACE = frozenset("RU BY KZ UA UZ KG TJ TM AM AZ GE MD FR PL CZ SK HU SE NO FI EE LV LT BG PT".split())
# 1'234.56
_DOT_APOSTROPHE = frozenset({"CH", "LI"})
# Where 03/04/2026 is March 4.
_MONTH_FIRST = frozenset("US PR GU VI AS MP UM FM MH PW BZ PH".split())
# Where 03/04/2026 is 3 April, and dates are written with slashes. Elsewhere (Russia, Germany...) people write
# 03.04.2026, and a date with slashes comes from a foreign program as often as not: it goes to people.
_DAY_FIRST = frozenset("GB IE AU NZ IN PK BD LK SG MY HK MO MT IL FR BE LU ES IT PT GR TR BR AR CL CO UY PY VE EC BO "
                       "PE MX GT HN SV NI DO PA ID VN NG GH KE UG TZ AE SA QA KW BH OM JO EG".split())


def style_of(country: object) -> Style:
    """The style of a country (ISO 3166-1 alpha-2); UNKNOWN for a country not listed or no country."""
    code = str(country or "").strip().upper()
    if code in _DOT_COMMA:
        decimal, group = ".", ","
    elif code in _COMMA_DOT:
        decimal, group = ",", "."
    elif code in _COMMA_SPACE:
        decimal, group = ",", " "
    elif code in _DOT_APOSTROPHE:
        decimal, group = ".", "'"
    else:
        decimal, group = None, None
    month_first = True if code in _MONTH_FIRST else False if code in _DAY_FIRST else None
    return Style(decimal, group, month_first)


# Letters that OCR and hurried typing put in place of digits.
_LOOKALIKES = str.maketrans({"O": "0", "О": "0", "L": "1", "I": "1", "З": "3", "S": "5", "B": "8"})
_SPACES = re.compile(r"[\s  ']")
_CURRENCY_SIGNS = {
    "₽": "RUB", "руб": "RUB", "р.": "RUB", "rub": "RUB", "rur": "RUB",
    "$": "USD", "usd": "USD", "€": "EUR", "eur": "EUR", "¥": "CNY", "cny": "CNY", "юань": "CNY",
}
# A bare $ is the company's own dollar (or peso) where it has one; ¥ is the yen in Japan.
_DOLLARS = frozenset("USD CAD AUD NZD SGD HKD TWD MXN ARS CLP COP".split())
_NOT_A_NUMBER = re.compile(r"[^\d.,\-+]")


def _clean(raw: str) -> tuple[str, bool, float] | None:
    """The digits and separators of an amount cell, whether it is negative, and how sure the reading is."""
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
            return None
        cleaned, confidence = fixed, 0.4
    return cleaned, negative, confidence


def _decimal(text: str) -> Decimal | None:
    try:
        return Decimal(text)
    except InvalidOperation:
        return None


def _grouping_like(head: str, tail: str) -> bool:
    """1,500 or 12.000: the one separator may group thousands as well as start the decimals."""
    digits = head.lstrip("+-")
    return len(tail) == 3 and tail.isdigit() and 1 <= len(digits) <= 3 and digits.isdigit() and digits[0] != "0"


def _grouped(parts: list[str]) -> bool:
    digits = parts[0].lstrip("+-")
    return (1 <= len(digits) <= 3 and digits.isdigit()
            and all(len(p) == 3 and p.isdigit() for p in parts[1:]))


def _number(cleaned: str, style: Style) -> tuple[Decimal | None, float]:
    if style.exact:
        return _decimal(cleaned), 1.0
    dots, commas = cleaned.count("."), cleaned.count(",")
    if dots and commas:
        # The last separator is the decimal one: 1.234,56 or 1,234.56, whoever wrote it.
        decimal = "," if cleaned.rfind(",") > cleaned.rfind(".") else "."
        group = "." if decimal == "," else ","
        return _decimal(cleaned.replace(group, "").replace(decimal, ".")), 1.0
    if not dots and not commas:
        return _decimal(cleaned), 1.0
    sep = "." if dots else ","
    parts = cleaned.split(sep)
    if len(parts) > 2:
        # Only a group separator repeats: 1,234,567 or 1.000.000.
        sure = _grouped(parts) and style.group == sep
        return _decimal("".join(parts)), 1.0 if sure else 0.6
    head, tail = parts
    if not _grouping_like(head, tail):
        return _decimal(f"{head}.{tail}"), 1.0  # 12,5 or 1500.50 or 0,500: only decimals fit
    if style.group == sep:
        return _decimal(head + tail), 1.0  # 1,500 in the United States, 1.500 in Germany
    # 1,500 in Russia is one and a half, unless it came from an American export: only the column can tell
    return _decimal(f"{head}.{tail}"), 1.0 if style.decimal == sep and style.settled else 0.6


def amount(value: object, style: Style = UNKNOWN) -> tuple[Decimal | None, float]:
    if isinstance(value, bool) or value is None:
        return None, 0.0
    if isinstance(value, int | float | Decimal):
        return Decimal(str(value)), 1.0
    raw = str(value).strip()
    if not raw:
        return None, 0.0
    clean = _clean(raw)
    if clean is None:
        return None, 0.0
    cleaned, negative, confidence = clean
    number, sure = _number(cleaned, style)
    if number is None:
        return None, 0.0
    return (-number if negative else number), min(confidence, sure)


def amounts_style(cells: Iterable[object], company: Style = UNKNOWN) -> Style:
    """The separators that the amounts of one column show by themselves (1,234.56, 12,50, 1.000.000), when they
    agree; else the company's. With the decimal separator known, the other one can only group thousands."""
    decimals: set[str] = set()
    for value in cells:
        if not isinstance(value, str) or not value.strip():
            continue
        clean = _clean(value.strip())
        if clean is None:
            continue
        cleaned = clean[0]
        dots, commas = cleaned.count("."), cleaned.count(",")
        if dots and commas:
            decimals.add("," if cleaned.rfind(",") > cleaned.rfind(".") else ".")
        elif dots > 1 or commas > 1:
            decimals.add("," if dots > 1 else ".")
        elif dots + commas == 1:
            sep = "." if dots else ","
            head, tail = cleaned.split(sep)
            if not _grouping_like(head, tail):
                decimals.add(sep)
    if len(decimals) != 1:
        return company
    decimal = decimals.pop()
    return replace(company, decimal=decimal, group="," if decimal == "." else ".", settled=True)


def _home_sign(sign: str, code: str, home: str | None) -> str:
    if home and sign == "$" and home in _DOLLARS:
        return home
    if home == "JPY" and sign == "¥":
        return home
    return code


def currency(value: object, home: str | None = None) -> tuple[str | None, float]:
    """A currency column: an ISO code, or a sign. home is the company's currency, what a bare $ means there."""
    if value is None:
        return None, 0.0
    raw = str(value).strip().lower()
    if not raw:
        return None, 0.0
    if re.fullmatch(r"[a-z]{3}", raw):
        return raw.upper(), 1.0
    for sign, code in _CURRENCY_SIGNS.items():
        if sign in raw:
            return _home_sign(sign, code, home), 0.95
    return None, 0.0


def currency_in(value: object, home: str | None = None) -> str | None:
    """A currency sign inside an amount cell, such as '1 500 ₽'."""
    if value is None or isinstance(value, int | float | Decimal):
        return None
    raw = str(value).lower()
    return next((_home_sign(sign, code, home) for sign, code in _CURRENCY_SIGNS.items() if sign in raw), None)


_ISO = re.compile(r"(\d{4})-(\d{1,2})-(\d{1,2})(?:[T ].*)?")
_YEAR_FIRST = re.compile(r"(\d{4})[/.](\d{1,2})[/.](\d{1,2})")
_NUMERIC = re.compile(r"(\d{1,2})([./-])(\d{1,2})\2(\d{4}|\d{2})")
# A month by its name says which number is the day: Nov 22, 2026; 22 November 2026; 22-Nov-26.
_WORDS = [("%b %d, %Y", 1.0), ("%B %d, %Y", 1.0), ("%b %d %Y", 1.0), ("%B %d %Y", 1.0), ("%b. %d, %Y", 1.0),
          ("%d %b %Y", 1.0), ("%d %B %Y", 1.0), ("%d-%b-%Y", 1.0), ("%d-%b-%y", 0.95)]
_MONTHS = [("%Y-%m", 0.95), ("%m.%Y", 0.95)]


def _date(year: int, month: int, day_of_month: int) -> date | None:
    try:
        return date(year, month, day_of_month)
    except ValueError:
        return None


def _year(text: str) -> int:
    """Two digits as strptime reads %y: 69-99 are the 1900s, the rest the 2000s."""
    year = int(text)
    if len(text) == 2:
        return year + (1900 if year >= 69 else 2000)
    return year


def _numeric(match: re.Match[str], style: Style) -> tuple[date | None, float]:
    first, sep, second, year_text = int(match[1]), match[2], int(match[3]), match[4]
    cap = 0.95 if len(year_text) == 2 else 1.0
    year = _year(year_text)
    if sep == ".":
        month_first, confidence = False, cap  # 03.04.2026 is 3 April wherever dots are used
    elif first > 12 >= second:
        month_first, confidence = False, cap
    elif second > 12 >= first:
        month_first, confidence = True, cap
    elif first > 12 and second > 12:
        return None, 0.0
    elif style.month_first is not None:
        month_first, confidence = style.month_first, min(cap, 0.95)
    else:
        month_first, confidence = False, 0.7
    parsed = _date(year, first, second) if month_first else _date(year, second, first)
    return (parsed, confidence) if parsed else (None, 0.0)


def _day(raw: str, style: Style) -> tuple[date | None, float]:
    if m := _ISO.fullmatch(raw):
        parsed = _date(int(m[1]), int(m[2]), int(m[3]))
        return (parsed, 1.0) if parsed else (None, 0.0)
    if m := _YEAR_FIRST.fullmatch(raw):
        parsed = _date(int(m[1]), int(m[2]), int(m[3]))
        return (parsed, 1.0) if parsed else (None, 0.0)
    if m := _NUMERIC.fullmatch(raw):
        return _numeric(m, style)
    for fmt, confidence in _WORDS + _MONTHS:
        try:
            return datetime.strptime(raw, fmt).date(), confidence
        except ValueError:
            continue
    return None, 0.0


def day(value: object, style: Style = UNKNOWN) -> tuple[date | None, float]:
    if isinstance(value, datetime):
        return value.date(), 1.0
    if isinstance(value, date):
        return value, 1.0
    if value is None:
        return None, 0.0
    raw = str(value).strip()
    if not raw:
        return None, 0.0
    found = _day(raw, style)
    if found[0] is None and " " in raw:
        found = _day(raw.split(" ")[0], style)  # 31.10.2026 10:00
    return found


def dates_style(cells: Iterable[object], company: Style = UNKNOWN) -> Style:
    """The order that the dates of one column show by themselves: 25/10/2026 is day first, 10/25/2026 month first.
    When they agree, 05/10/2026 in the same column is read the same way; else the company's style decides."""
    votes: set[bool] = set()
    for value in cells:
        if not isinstance(value, str):
            continue
        m = _NUMERIC.fullmatch(value.strip().split(" ")[0])
        if m is None or m[2] == ".":
            continue
        first, second = int(m[1]), int(m[3])
        if first > 12 >= second:
            votes.add(False)
        elif second > 12 >= first:
            votes.add(True)
    return replace(company, month_first=votes.pop()) if len(votes) == 1 else company


def text(value: object) -> str:
    if value is None:
        return ""
    if isinstance(value, float) and value.is_integer():
        return str(int(value))
    return str(value).strip()
