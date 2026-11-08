"""What the CostGuard DAGs share: where tenant configs live and how a tenant's task is named.

Every tenant is one file, $COSTGUARD_TENANTS_DIR/<name>.toml (default /opt/costguard/tenants), in the format of
costguard_connectors.agent. A [report] section turns on the monthly report for that tenant. Secrets come from
the worker's environment, prefixed with the tenant name in upper case: ROMASHKA_CORE_TOKEN, ROMASHKA_ONEC_PASSWORD.
"""

from __future__ import annotations

import os
import re
import tomllib
from datetime import timedelta
from pathlib import Path

TENANTS_DIR = Path(os.environ.get("COSTGUARD_TENANTS_DIR", "/opt/costguard/tenants"))
REPORTS_DIR = os.environ.get("COSTGUARD_REPORTS_DIR", "/opt/costguard/reports")

_LATIN = str.maketrans({c: t for c, t in zip(
    "абвгдеёжзийклмнопрстуфхцчшщъыьэюя",
    ["a", "b", "v", "g", "d", "e", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p", "r", "s", "t", "u",
     "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"], strict=True)})

DEFAULT_ARGS = {"owner": "costguard", "retries": 2, "retry_delay": timedelta(minutes=5),
                "execution_timeout": timedelta(minutes=30)}


def tenants(directory: Path = TENANTS_DIR) -> dict[str, dict]:
    """Tenant name to its config; a file that does not parse is skipped, so one typo stops one tenant."""
    out = {}
    for path in sorted(directory.glob("*.toml")) if directory.is_dir() else []:
        name = re.sub(r"[^a-z0-9_]", "_", path.stem.lower().translate(_LATIN))  # task ids must be ASCII
        try:
            out[name] = {"path": str(path), **tomllib.loads(path.read_text("utf-8"))}
        except (OSError, tomllib.TOMLDecodeError):
            continue
    return out


def env_prefix(name: str) -> str:
    return name.upper() + "_"
