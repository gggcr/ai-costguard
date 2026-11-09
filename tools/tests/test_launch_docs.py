"""The launch documents of part 9 are where the runbooks, the alerts and the README point."""

from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]


def test_every_runbook_is_in_the_index():
    index = (ROOT / "docs/runbooks/README.md").read_text("utf-8")
    for runbook in (ROOT / "docs/runbooks").glob("*.md"):
        if runbook.name != "README.md":
            assert f"]({runbook.name})" in index, runbook.name


def test_the_launch_decisions_are_listed():
    index = (ROOT / "docs/adr/README.md").read_text("utf-8")
    for number in ("0025", "0026", "0027", "0028", "0029"):
        [adr] = (ROOT / "docs/adr").glob(f"{number}-*.md")
        assert f"]({adr.name})" in index, adr.name


def test_the_pentest_scope_says_what_to_test_with_whom_and_what_not_to_touch():
    scope = (ROOT / "docs/security/pentest-scope.md").read_text("utf-8")
    for section in ("## Тестовые учётные записи", "## Что проверять", "## Что не трогать", "Изоляция компаний",
                    "Роли", "Шлюз", "Загрузка файлов", "Интеграции", "NEAR mainnet"):
        assert section in scope, section
