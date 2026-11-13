"""Shared validation for direct and queued Markdown receivers."""
import hashlib
import hmac
import re

MAX_BODY = 4 * 1024 * 1024


class NoteConflict(Exception):
    pass


def validate_note(data):
    if not isinstance(data, dict) or data.get("schema") != 1:
        raise ValueError("invalid schema")
    ident, title, text, digest = (data.get(k) for k in ("id", "title", "markdown", "sha256"))
    if not all(isinstance(v, str) for v in (ident, title, text, digest)):
        raise ValueError("invalid fields")
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,100}", ident) or not re.fullmatch(r"[a-f0-9]{64}", digest) or not text.strip() or len(title) > 200:
        raise ValueError("invalid note")
    body = text.encode("utf-8")
    if len(body) > MAX_BODY or not hmac.compare_digest(hashlib.sha256(body).hexdigest(), digest):
        raise ValueError("invalid hash or size")
    title = re.sub(r'[\\/:*?"<>|\x00-\x1f\x7f]', "_", title).strip(" .")[:60] or "柒声录音"
    return ident, title, text, digest
