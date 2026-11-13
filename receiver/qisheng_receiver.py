"""Optional Markdown-only receiver. Python 3.9+, standard library only."""
import hashlib
import hmac
import json
import os
import sqlite3
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from note_protocol import MAX_BODY, NoteConflict, validate_note


class Store:
    def __init__(self, inbox, state):
        self.inbox = Path(inbox).resolve()
        self.inbox.mkdir(parents=True, exist_ok=True)
        state = Path(state).resolve()
        state.mkdir(parents=True, exist_ok=True, mode=0o700)
        self.database = state / "receipts.sqlite3"
        with sqlite3.connect(self.database) as db:
            db.execute("CREATE TABLE IF NOT EXISTS notes (id TEXT PRIMARY KEY, sha TEXT NOT NULL, filename TEXT NOT NULL)")
        self.database.chmod(0o600)

    def save(self, data):
        ident, title, text, digest = validate_note(data)
        body = text.encode("utf-8")
        with sqlite3.connect(self.database, timeout=10) as db:
            db.execute("BEGIN IMMEDIATE")
            row = db.execute("SELECT sha, filename FROM notes WHERE id=?", (ident,)).fetchone()
            name = row[1] if row else f"{title} [{ident}].md"
            if Path(name).name != name:
                raise NoteConflict()
            target = self.inbox / name
            if target.is_symlink():
                raise NoteConflict()
            if row:
                if row[0] != digest or not target.is_file() or target.stat().st_size > MAX_BODY or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                    raise NoteConflict()
                return {"id": ident, "sha256": digest, "status": "duplicate"}
            try:
                with target.open("xb") as output:
                    os.chmod(target, 0o600)
                    output.write(body)
                    output.flush()
                    os.fsync(output.fileno())
                status = "saved"
            except FileExistsError:
                if not target.is_file() or target.stat().st_size > MAX_BODY or hashlib.sha256(target.read_bytes()).hexdigest() != digest:
                    raise NoteConflict()
                status = "duplicate"
            db.execute("INSERT INTO notes VALUES (?, ?, ?)", (ident, digest, name))
            return {"id": ident, "sha256": digest, "status": status}


def handler(store, token):
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # No note text, tokens or request URLs in access logs.

        def setup(self):
            super().setup()
            self.connection.settimeout(15)

        def reply(self, code, data):
            body = json.dumps(data, ensure_ascii=False).encode("utf-8")
            self.send_response(code)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(body)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("Connection", "close")
            self.end_headers()
            self.wfile.write(body)
            self.close_connection = True

        def do_POST(self):
            if self.path != "/v1/notes":
                self.reply(404, {"error": "not_found"}); return
            credential = self.headers.get("Authorization", "")
            if not hmac.compare_digest(credential.encode(), ("Bearer " + token).encode()):
                self.reply(401, {"error": "unauthorized"}); return
            try:
                size = int(self.headers.get("Content-Length", "0"))
            except ValueError:
                size = 0
            if size <= 0 or size > MAX_BODY or self.headers.get("Transfer-Encoding"):
                self.reply(413, {"error": "invalid_size"}); return
            if self.headers.get("Content-Type", "").split(";")[0].strip() != "application/json":
                self.reply(415, {"error": "json_required"}); return
            try:
                body = self.rfile.read(size)
                if len(body) != size:
                    raise ValueError()
                result = store.save(json.loads(body))
                self.reply({"saved": 201, "duplicate": 200, "queued": 202}[result["status"]], result)
            except (ValueError, UnicodeError):
                self.reply(400, {"error": "invalid_note"})
            except NoteConflict:
                self.reply(409, {"error": "existing_content_not_overwritten"})
            except (OSError, sqlite3.Error):
                self.reply(503, {"error": "storage_unavailable"})
    return Handler


def main():
    token_file = os.environ.get("QISHENG_TOKEN_FILE")
    token = Path(token_file).read_text().strip() if token_file else os.environ.get("QISHENG_TOKEN", "")
    if len(token) < 24 or any(ord(c) < 33 or ord(c) > 126 for c in token):
        raise SystemExit("Set QISHENG_TOKEN_FILE or QISHENG_TOKEN (at least 24 non-whitespace characters).")
    inbox = os.environ.get("QISHENG_INBOX")
    state = os.environ.get("QISHENG_STATE")
    backup = os.environ.get("QISHENG_DELIVERY_BACKUP")
    if not state or (not inbox and not backup):
        raise SystemExit("Set QISHENG_INBOX and QISHENG_STATE; keep state outside your Obsidian Vault.")
    if backup:
        from delivery_queue import QueueStore
        store = QueueStore(state, backup)
    else:
        store = Store(inbox, state)
    server = ThreadingHTTPServer((os.environ.get("QISHENG_BIND", "127.0.0.1"), int(os.environ.get("QISHENG_PORT", "8769"))), handler(store, token))
    print("QiSheng receiver ready; only /v1/notes accepts authenticated Markdown.", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
