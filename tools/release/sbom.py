"""Software bill of materials and license inventory of what CostGuard ships.

    ./gradlew -q :core:dependencies :gateway:dependencies --configuration runtimeClasspath > /tmp/java-deps.txt
    python tools/release/sbom.py --java-deps /tmp/java-deps.txt --out docs/sbom

Writes `costguard.cdx.json` (CycloneDX 1.5: name, version, purl and declared licenses of every runtime component)
and `licenses.csv`. Licenses come from what each package declares: the POMs in the local Gradle cache (following
parent POMs), `package.json` of the installed web dependencies (`npm ci` first), and the metadata of the installed
Python packages (`uv sync` first). A component without a declared license is listed as `UNKNOWN`, never guessed.
"""

from __future__ import annotations

import argparse
import csv
import json
import re
import sys
import tomllib
import uuid
import xml.etree.ElementTree as ET
from datetime import UTC, datetime
from importlib import metadata
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
GRADLE_CACHE = Path.home() / ".gradle" / "caches" / "modules-2" / "files-2.1"
COORD = re.compile(r"([\w.\-]+):([\w.\-]+):([\w.\-]+)(?: -> ([\w.\-]+))?")


def java(deps_file: Path) -> list[dict]:
    seen: dict[tuple[str, str], str] = {}
    for line in deps_file.read_text(encoding="utf-8", errors="replace").splitlines():
        m = COORD.search(line)
        if m and "project :" not in line:
            group, name, version, resolved = m.groups()
            seen[(group, name)] = resolved or version
    return [{"ecosystem": "maven", "group": g, "name": n, "version": v, "licenses": pom_licenses(g, n, v)}
            for (g, n), v in sorted(seen.items())]


def pom_licenses(group: str, name: str, version: str, depth: int = 0) -> list[str]:
    pom = next((GRADLE_CACHE / group / name / version).glob(f"*/{name}-{version}.pom"), None)
    if pom is None or depth > 5:
        return []
    try:
        root = ET.parse(pom).getroot()
    except ET.ParseError:
        return []
    ns = {"m": root.tag[1:root.tag.index("}")]} if root.tag.startswith("{") else {"m": ""}
    p = "m:" if ns["m"] else ""
    found = [(lic.findtext(f"{p}name", namespaces=ns) or lic.findtext(f"{p}url", namespaces=ns) or "").strip()
             for lic in root.findall(f"{p}licenses/{p}license", ns)]
    found = [f for f in found if f]
    if found:
        return found
    parent = root.find(f"{p}parent", ns)
    if parent is not None:
        return pom_licenses(parent.findtext(f"{p}groupId", namespaces=ns) or group,
                            parent.findtext(f"{p}artifactId", namespaces=ns) or "",
                            parent.findtext(f"{p}version", namespaces=ns) or "", depth + 1)
    return []


def npm(frontend: Path) -> list[dict]:
    lock = json.loads((frontend / "package-lock.json").read_text(encoding="utf-8"))
    out = []
    for path, info in sorted(lock.get("packages", {}).items()):
        if not path.startswith("node_modules/") or info.get("dev"):
            continue
        name = path.split("node_modules/")[-1]
        declared = info.get("license")
        if not declared:
            pkg = frontend / path / "package.json"
            if pkg.exists():
                declared = json.loads(pkg.read_text(encoding="utf-8")).get("license")
        out.append({"ecosystem": "npm", "group": "", "name": name, "version": info.get("version", ""),
                    "licenses": [declared] if isinstance(declared, str) else []})
    return out


def python(lock_file: Path) -> list[dict]:
    lock = tomllib.loads(lock_file.read_text(encoding="utf-8"))
    out = []
    for package in lock.get("package", []):
        source = package.get("source", {})
        if "editable" in source or "virtual" in source:
            continue  # the workspace's own packages
        name, version = package["name"], package.get("version", "")
        licenses: list[str] = []
        try:
            meta = metadata.metadata(name)
            expression = meta.get("License-Expression")
            if expression:
                licenses = [expression]
            else:
                licenses = [c.split("::")[-1].strip() for c in meta.get_all("Classifier") or []
                            if c.startswith("License ::")]
                if not licenses and meta.get("License") and len(meta.get("License")) < 80:
                    licenses = [meta.get("License")]
        except metadata.PackageNotFoundError:
            pass
        out.append({"ecosystem": "pypi", "group": "", "name": name, "version": version, "licenses": licenses})
    return out


def purl(c: dict) -> str:
    if c["ecosystem"] == "maven":
        return f"pkg:maven/{c['group']}/{c['name']}@{c['version']}"
    return f"pkg:{c['ecosystem']}/{c['name']}@{c['version']}"


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-deps", type=Path, required=True)
    parser.add_argument("--out", type=Path, default=ROOT / "docs" / "sbom")
    args = parser.parse_args(argv)
    components = java(args.java_deps) + npm(ROOT / "apps" / "frontend") + python(ROOT / "uv.lock")
    args.out.mkdir(parents=True, exist_ok=True)
    bom = {
        "bomFormat": "CycloneDX", "specVersion": "1.5", "version": 1,
        "serialNumber": f"urn:uuid:{uuid.uuid4()}",
        "metadata": {"timestamp": datetime.now(UTC).replace(microsecond=0).isoformat(),
                     "component": {"type": "application", "name": "costguard"}},
        "components": [{
            "type": "library", "name": c["name"], "version": c["version"], "purl": purl(c),
            **({"group": c["group"]} if c["group"] else {}),
            "licenses": [{"license": {"name": lic}} for lic in c["licenses"]] or [{"license": {"name": "UNKNOWN"}}],
        } for c in components],
    }
    (args.out / "costguard.cdx.json").write_text(json.dumps(bom, indent=1, ensure_ascii=False) + "\n", "utf-8")
    with (args.out / "licenses.csv").open("w", newline="", encoding="utf-8") as f:
        w = csv.writer(f)
        w.writerow(["ecosystem", "component", "version", "licenses"])
        for c in components:
            name = f"{c['group']}:{c['name']}" if c["group"] else c["name"]
            w.writerow([c["ecosystem"], name, c["version"], " | ".join(c["licenses"]) or "UNKNOWN"])
    counts: dict[str, int] = {}
    for c in components:
        counts[c["ecosystem"]] = counts.get(c["ecosystem"], 0) + 1
    print(f"{len(components)} components: {counts}")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
