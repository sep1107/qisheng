"""Shared validation for direct and queued Markdown receivers."""
import datetime
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


def qisheng_title(title, markdown):
    """Compact an existing recording timestamp; legacy exports can use their session header."""
    match = re.match(r"^(\d{4})-(\d{2})-(\d{2})_(\d{6})\s+(.+)$", title)
    if match:
        stamp = "".join(match.group(i) for i in (1, 2, 3)) + "_" + match.group(4)
        caption = match.group(5)
    else:
        match = re.match(r"^(\d{8}_\d{6})[ _]+(.+)$", title)
        if match:
            stamp, caption = match.groups()
        else:
            header = markdown.split("## ", 1)[0][:1000]
            match = re.search(r"^会话时间[^\n：:]*[：:]\s*(\d{4}-\d{2}-\d{2}) (\d{2}:\d{2}:\d{2})", header, re.M)
            if not match:
                return title
            stamp = match.group(1).replace("-", "") + "_" + match.group(2).replace(":", "")
            caption = title
    try:
        datetime.datetime.strptime(stamp, "%Y%m%d_%H%M%S")
    except ValueError:
        return title
    caption = re.sub(r"^录音整理[：:_\s]*", "", caption).strip() or caption.strip()
    return (stamp + " " + caption)[:60]
