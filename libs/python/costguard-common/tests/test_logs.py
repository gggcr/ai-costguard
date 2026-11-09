import json
import logging
from pathlib import Path

import pytest

from costguard_common import logs

CASES = json.loads((Path(__file__).resolve().parents[4] / "schemas/logging/masking-cases.json").read_text("utf-8"))


@pytest.mark.parametrize("case", CASES["cases"], ids=lambda c: c["in"][:40])
def test_the_shared_cases(case):
    assert logs.mask(case["in"]) == case["out"]


def test_the_formatter_masks_arguments_and_the_traceback():
    formatter = logs.MaskingFormatter(logs.FORMAT)
    try:
        raise ValueError("duplicate key ivan@romashka.ru")
    except ValueError:
        record = logging.LogRecord("t", logging.ERROR, __file__, 1, "Employee %s skipped", ("anna@romashka.ru",),
                                   exc_info=__import__("sys").exc_info())
    line = formatter.format(record)
    assert "ivan@" not in line and "anna@" not in line
    assert "Employee ***@romashka.ru skipped" in line and "duplicate key ***@romashka.ru" in line


def test_configure_installs_the_masking_formatter_and_quiets_url_loggers(capsys):
    logs.configure()
    logging.getLogger("costguard.test").warning("token=abcdef123 for %s", "Петров Иван Сергеевич")
    assert logging.getLogger("httpx").getEffectiveLevel() == logging.WARNING
    err = capsys.readouterr().err
    assert "token=*** for ***" in err and "Петров" not in err
