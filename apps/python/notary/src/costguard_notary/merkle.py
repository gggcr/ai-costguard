"""The Merkle tree of ai.costguard.core.journal.internal.Merkle, byte for byte.

Leaves are receipt hashes (hex) in batch order. A node is sha256(left || right) over the raw 32-byte values; a
level with an odd count carries its last node up unchanged. The core checks the root against its receipts before
it marks them anchored, so any difference here would make every anchor fail.
"""

from __future__ import annotations

import hashlib


def pair(left: str, right: str) -> str:
    return hashlib.sha256(bytes.fromhex(left) + bytes.fromhex(right)).hexdigest()


def root(leaves: list[str]) -> str:
    if not leaves:
        raise ValueError("A batch needs at least one receipt")
    level = [x.lower() for x in leaves]
    while len(level) > 1:
        level = [pair(level[i], level[i + 1]) if i + 1 < len(level) else level[i] for i in range(0, len(level), 2)]
    return level[0]


def path(leaves: list[str], index: int) -> list[str]:
    """Siblings from the leaf up, each prefixed with L or R for the side it sits on."""
    out: list[str] = []
    level = [x.lower() for x in leaves]
    i = index
    while len(level) > 1:
        sibling = i + 1 if i % 2 == 0 else i - 1
        if sibling < len(level):
            out.append(("R:" if i % 2 == 0 else "L:") + level[sibling])
        level = [pair(level[j], level[j + 1]) if j + 1 < len(level) else level[j] for j in range(0, len(level), 2)]
        i //= 2
    return out


def verify(leaf: str, steps: list[str], expected_root: str) -> bool:
    node = leaf.lower()
    for step in steps:
        sibling = step[2:]
        node = pair(node, sibling) if step.startswith("R:") else pair(sibling, node)
    return node == expected_root
