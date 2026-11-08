"""Invoices sent to a mailbox (for example invoices@company.ru).

Every attachment the ingest pipeline can read is uploaded to the core (POST /api/v1/imports), exactly as if a
person had uploaded it; the core skips a file it already has, so reading a letter twice is harmless. A letter is
marked as seen only after all its attachments were accepted.

Photos and scans go too, for OCR (ADR 0012), but not the logos of signatures: an image smaller than
`min_image_kb` (20 KB) or with a side shorter than `min_image_side` (500 px) is left out. Whether an image is inline
does not tell: phones put photos inline as well.
"""

from __future__ import annotations

import email
import imaplib
import logging
import struct
from dataclasses import dataclass
from email.message import Message
from email.policy import default
from typing import Protocol

import httpx

log = logging.getLogger("costguard.connectors.mail")

# What the ingest pipeline reads: tables, PDFs and bank statements (1C, MT940, CAMT.053, OFX).
READABLE = (".csv", ".txt", ".xlsx", ".xlsm", ".pdf", ".sta", ".mt940", ".940", ".xml", ".ofx", ".qfx")
# Photos and scans, read by OCR in the pipeline. GIF is left out: it is logos and animations.
IMAGES = (".jpg", ".jpeg", ".png", ".tif", ".tiff", ".webp", ".heic")
MIN_IMAGE_KB = 20
MIN_IMAGE_SIDE = 500


def image_size(content: bytes) -> tuple[int, int] | None:
    """Width and height from a PNG or JPEG header; None for other formats or a broken file."""
    if content[:8] == b"\x89PNG\r\n\x1a\n" and len(content) >= 24:
        return struct.unpack(">II", content[16:24])
    if content[:2] != b"\xff\xd8":
        return None
    at = 2
    while at + 9 < len(content):
        if content[at] != 0xFF:
            return None
        marker = content[at + 1]
        if marker in (0xD8, 0x01) or 0xD0 <= marker <= 0xD7:
            at += 2
            continue
        length = struct.unpack(">H", content[at + 2:at + 4])[0]
        if 0xC0 <= marker <= 0xCF and marker not in (0xC4, 0xC8, 0xCC):
            height, width = struct.unpack(">HH", content[at + 5:at + 9])
            return width, height
        at += 2 + length
    return None


def is_document_image(content: bytes, min_kb: int = MIN_IMAGE_KB, min_side: int = MIN_IMAGE_SIDE) -> bool:
    """A photo or a scan of a document rather than a logo: big enough in bytes and, when known, in pixels."""
    if len(content) < min_kb * 1024:
        return False
    size = image_size(content)
    return size is None or min(size) >= min_side


@dataclass(frozen=True)
class Attachment:
    file_name: str
    content: bytes
    content_type: str


class Mailbox(Protocol):
    def unseen(self) -> list[tuple[str, bytes]]: ...

    def mark_seen(self, uid: str) -> None: ...


class Imap:
    def __init__(self, host: str, user: str, password: str, folder: str = "INBOX", port: int = 993) -> None:
        self._imap = imaplib.IMAP4_SSL(host, port)
        self._imap.login(user, password)
        self._imap.select(folder)

    def unseen(self) -> list[tuple[str, bytes]]:
        _, data = self._imap.uid("SEARCH", None, "UNSEEN")
        out = []
        for uid in (data[0] or b"").split():
            _, parts = self._imap.uid("FETCH", uid, "(BODY.PEEK[])")
            raw = next((p[1] for p in parts if isinstance(p, tuple)), None)
            if raw:
                out.append((uid.decode(), raw))
        return out

    def mark_seen(self, uid: str) -> None:
        self._imap.uid("STORE", uid, "+FLAGS", "(\\Seen)")


def attachments(raw: bytes, min_image_kb: int = MIN_IMAGE_KB, min_image_side: int = MIN_IMAGE_SIDE
                ) -> list[Attachment]:
    letter: Message = email.message_from_bytes(raw, policy=default)
    found = []
    for part in letter.iter_attachments():
        name = part.get_filename() or ""
        content = part.get_payload(decode=True) or b""
        if name.lower().endswith(READABLE):
            found.append(Attachment(name, content, part.get_content_type()))
        elif name.lower().endswith(IMAGES):
            if is_document_image(content, min_image_kb, min_image_side):
                found.append(Attachment(name, content, part.get_content_type()))
            else:
                log.info("Image %s left out: too small for a document, likely a logo", name)
    return found


class MailInvoices:
    name = "mail"

    def __init__(self, mailbox: Mailbox, core: httpx.Client, min_image_kb: int = MIN_IMAGE_KB,
                 min_image_side: int = MIN_IMAGE_SIDE) -> None:
        """core carries base_url of the core API and the service token of the tenant."""
        self._mailbox = mailbox
        self._core = core
        self._min_kb = min_image_kb
        self._min_side = min_image_side

    def collect(self) -> int:
        uploaded = 0
        for uid, raw in self._mailbox.unseen():
            files = attachments(raw, self._min_kb, self._min_side)
            ok = True
            for a in files:
                response = self._core.post("/api/v1/imports", files={"file": (a.file_name, a.content,
                                                                               a.content_type)})
                if response.status_code in (200, 201):
                    uploaded += response.status_code == 201
                else:
                    ok = False
                    log.warning("Letter %s: %s was not accepted: %s %s", uid, a.file_name, response.status_code,
                                response.text[:200])
            if ok:
                self._mailbox.mark_seen(uid)
        return uploaded
