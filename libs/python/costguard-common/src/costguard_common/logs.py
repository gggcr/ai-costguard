"""Logs without personal data or secrets (ADR 0023).

Services log ids, counts and codes, never a name, an e-mail or a token. The formatter here is the safety net for
what still slips in: an e-mail inside a database error, a token in an exception from an HTTP client, a full name in
a message from a connected system. It masks the whole formatted line, the traceback included.

The rules match ai.costguard.platform.logging.LogMasking on the Java side; both run the cases of
schemas/logging/masking-cases.json. A name without a patronymic or initials ("Иван Петров") cannot be told from
ordinary words, so the rule that matters most stays the first one: do not log people.
"""

from __future__ import annotations

import logging
import re

FORMAT = "%(asctime)s %(levelname)s %(name)s %(message)s"

_WORD = r"[А-ЯЁ][а-яё]+"
_SURNAME = rf"{_WORD}(?:-{_WORD})?"
_PATRONYMIC = r"[А-ЯЁ][а-яё]+(?:ич|вна|чна)"
_END = r"(?![а-яёА-ЯЁ])"

# Applied in order: credentials in URLs before e-mails, a name with the patronymic first before one with the
# surname first, so "Согласовала Анна Сергеевна Кузнецова" loses the whole name and keeps the verb.
RULES: list[tuple[re.Pattern[str], str]] = [
    (re.compile(r"://[^/\s:@]+:[^/\s@]+@"), "://***:***@"),
    (re.compile(r"(?i)\b(bearer|basic)\s+(?=[A-Za-z0-9._~+/=-]*[0-9=_.-])[A-Za-z0-9._~+/=-]{8,}"), r"\1 ***"),
    (re.compile(r"eyJ[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]{5,}\.[A-Za-z0-9_-]+"), "***"),
    (re.compile(r"cg_(?:live|test)_[A-Za-z0-9_-]+"), "cg_***"),
    (re.compile(r"sk-[A-Za-z0-9_-]{12,}"), "sk-***"),
    (re.compile(r"(?i)\b(password|passwd|pwd|secret|client_secret|token|access_token|refresh_token|api[_-]?key"
                r"|access[_-]?key|secret[_-]?key)(\s*[=:]\s*)(\"?)[^\s\",;&]+"), r"\1\2\3***"),
    (re.compile(r"[A-Za-z0-9._%+-]+@((?:[A-Za-z0-9-]+\.)+[A-Za-z]{2,})"), r"***@\1"),
    (re.compile(r"(?<!\d)\d{4}([ -])\d{4}\1\d{4}\1\d{4}(?!\d)"), "***"),
    (re.compile(r"(?<!\d)\d{3}-\d{3}-\d{3}[ -]\d{2}(?!\d)"), "***"),
    (re.compile(r"(?<![\d+])(?:\+7|8)[\s(-]*\d{3}[\s)-]*\d{3}[\s-]*\d{2}[\s-]*\d{2}(?!\d)"), "***"),
    (re.compile(rf"{_WORD}\s+{_PATRONYMIC}\s+{_SURNAME}{_END}"), "***"),
    (re.compile(rf"{_SURNAME}\s+{_WORD}\s+{_PATRONYMIC}{_END}"), "***"),
    (re.compile(rf"{_SURNAME}\s+[А-ЯЁ]\.\s?[А-ЯЁ]\."), "***"),
    (re.compile(rf"[А-ЯЁ]\.\s?[А-ЯЁ]\.\s?{_SURNAME}{_END}"), "***"),
]

# Loggers of libraries that write request URLs at INFO: a webhook URL carries its token in the path.
QUIET = ("httpx", "httpcore", "urllib3", "botocore", "kafka", "confluent_kafka")


def mask(text: str) -> str:
    for pattern, replacement in RULES:
        text = pattern.sub(replacement, text)
    return text


class MaskingFormatter(logging.Formatter):
    """Masks the formatted record, so the message, its arguments and the traceback are all covered."""

    def format(self, record: logging.LogRecord) -> str:
        return mask(super().format(record))


def configure(level: int = logging.INFO) -> None:
    """What every service calls instead of logging.basicConfig."""
    handler = logging.StreamHandler()
    handler.setFormatter(MaskingFormatter(FORMAT))
    logging.basicConfig(level=level, handlers=[handler], force=True)
    for name in QUIET:
        logging.getLogger(name).setLevel(max(level, logging.WARNING))
