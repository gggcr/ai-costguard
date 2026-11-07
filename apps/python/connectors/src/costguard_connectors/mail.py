"""Invoices sent to a mailbox (for example invoices@company.ru).

Every attachment the ingest pipeline can read is uploaded to the core (POST /api/v1/imports), exactly as if a
person had uploaded it; the core skips a file it already has, so reading a letter twice is harmless. A letter is
marked as seen only after all its attachments were accepted.
"""

from __future__ import annotations

import email
import imaplib
import logging
from dataclasses import dataclass
from email.message import Message
from email.policy import default
from typing import Protocol

import httpx

log = logging.getLogger("costguard.connectors.mail")

READABLE = (".csv", ".txt", ".xlsx", ".xlsm", ".pdf")


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


def attachments(raw: bytes) -> list[Attachment]:
    letter: Message = email.message_from_bytes(raw, policy=default)
    found = []
    for part in letter.iter_attachments():
        name = part.get_filename() or ""
        if name.lower().endswith(READABLE):
            found.append(Attachment(name, part.get_payload(decode=True) or b"", part.get_content_type()))
    return found


class MailInvoices:
    name = "mail"

    def __init__(self, mailbox: Mailbox, core: httpx.Client) -> None:
        """core carries base_url of the core API and the service token of the tenant."""
        self._mailbox = mailbox
        self._core = core

    def collect(self) -> int:
        uploaded = 0
        for uid, raw in self._mailbox.unseen():
            files = attachments(raw)
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
