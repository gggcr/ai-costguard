"""Scripts the docs tell people to run as `tools/.../x.sh` must be executable in Git, or Linux and macOS answer
"Permission denied" (tools/release/verify.sh was 100644 until 2026-11-25). The init scripts of the PostgreSQL image
are left out: its entrypoint sources a script that is not executable and runs one that is, so the mode is a choice."""

import shutil
import subprocess
from pathlib import Path

import pytest

ROOT = Path(__file__).resolve().parents[2]


@pytest.mark.skipif(not shutil.which("git") or not (ROOT / ".git").exists(), reason="needs the Git checkout")
def test_every_script_with_a_shebang_under_tools_is_executable():
    listed = subprocess.run(["git", "ls-files", "-s", "--", "tools", "deploy"], cwd=ROOT, check=True,
                            capture_output=True, text=True).stdout.splitlines()
    not_executable = []
    for line in listed:
        mode, _, _, path = line.split(maxsplit=3)
        file = ROOT / path
        if file.suffix in {".sh", ""} and file.is_file() and file.read_bytes()[:2] == b"#!" and mode != "100755":
            not_executable.append(path)
    assert not_executable == []
