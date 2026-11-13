"""Durable, source-scoped delivery queue for a dedicated LiveSync CLI client."""
import argparse
import fcntl
import hashlib
import json
import os
import sqlite3
import subprocess
import time
from contextlib import contextmanager
from pathlib import Path

from note_protocol import NoteConflict, validate_note

FOLDERS = {"qisheng": "00.收集箱Inbox/07.柒声Qisheng", "rednote": "00.收集箱Inbox/01.小红书Rednote"}


class QueueStore:
    def __init__(self, state, backup):
        self.state, self.backup = Path(state).resolve(), Path(backup).resolve()
        if self.state == self.backup or self.state in self.backup.parents or self.backup in self.state.parents:
            raise ValueError("Backup must be a separate directory outside queue state")
        for directory in (self.state, self.backup):
            directory.mkdir(parents=True, exist_ok=True, mode=0o700)
            directory.chmod(0o700)
        self.database = self.state / "delivery.sqlite3"
        with self.lock():
            if not self.database.exists() and any(self.backup.glob("*.sqlite3")):
                raise OSError("Queue database is missing; restore and reconcile before accepting notes")
            with self.connect() as db:
                db.execute("""CREATE TABLE IF NOT EXISTS deliveries (
                source TEXT NOT NULL, id TEXT NOT NULL, sha TEXT NOT NULL,
                title TEXT NOT NULL, path TEXT NOT NULL, body TEXT NOT NULL,
                status TEXT NOT NULL, attempts INTEGER NOT NULL DEFAULT 0,
                created INTEGER NOT NULL, updated INTEGER NOT NULL,
                PRIMARY KEY(source,id))""")
        self.database.chmod(0o600)

    @contextmanager
    def lock(self, name="queue.lock"):
        with (self.state / name).open("a") as lock:
            os.chmod(lock.name, 0o600)
            fcntl.flock(lock, fcntl.LOCK_EX)
            yield

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.database, timeout=30)
        db.row_factory = sqlite3.Row
        db.execute("PRAGMA synchronous=FULL")
        try:
            with db:
                yield db
        finally:
            db.close()

    def snapshot(self):
        """Caller holds queue.lock. Never return an acceptance before this succeeds."""
        digest = hashlib.sha256()
        with self.database.open("rb") as source_file:
            for block in iter(lambda: source_file.read(1024 * 1024), b""):
                digest.update(block)
        revision = digest.hexdigest()
        existing = next(self.backup.glob(f"delivery-*-{revision}.sqlite3"), None)
        if existing:
            check = sqlite3.connect(existing)
            try:
                if check.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise OSError("Existing backup failed integrity check")
            finally:
                check.close()
            return existing
        name = f"delivery-{time.time_ns()}-{revision}.sqlite3"
        temporary = self.backup / (name + ".partial")
        with self.connect() as source:
            target = sqlite3.connect(temporary)
            try:
                temporary.chmod(0o600)
                source.backup(target)
                if target.execute("PRAGMA integrity_check").fetchone()[0] != "ok":
                    raise OSError("Backup integrity check failed")
            finally:
                target.close()
        with temporary.open("rb") as handle:
            os.fsync(handle.fileno())
        complete = self.backup / name
        temporary.rename(complete)
        descriptor = os.open(self.backup, os.O_RDONLY)
        try:
            os.fsync(descriptor)
        finally:
            os.close(descriptor)
        return complete

    def save(self, data):
        return self.enqueue("qisheng", data)

    def enqueue(self, source, data):
        if source not in FOLDERS:
            raise ValueError("Unknown source")
        ident, title, text, digest = validate_note(data)
        path = f"{FOLDERS[source]}/{title} [{source}-{ident}].md"
        with self.lock():
            with self.connect() as db:
                row = db.execute("SELECT sha,status FROM deliveries WHERE source=? AND id=?", (source, ident)).fetchone()
                if row and row["sha"] != digest:
                    raise NoteConflict()
                if not row:
                    now = int(time.time())
                    db.execute("INSERT INTO deliveries (source,id,sha,title,path,body,status,created,updated) VALUES (?,?,?,?,?,?,'pending',?,?)", (source, ident, digest, title, path, text, now, now))
            self.snapshot()
        return {"id": ident, "sha256": digest, "status": "duplicate" if row and row["status"] == "synced" else "queued"}

    def deliver(self, command):
        """command is an administrator-supplied argv prefix; never invoke a shell."""
        if not isinstance(command, list) or not command or not all(isinstance(v, str) and v for v in command):
            raise ValueError("CLI command must be a nonempty JSON string array")
        def call(args, body=None):
            try:
                result = subprocess.run(command + args, input=body, text=True, capture_output=True, timeout=180)
                return result if result.returncode == 0 else None
            except (OSError, subprocess.TimeoutExpired):
                return None
        # Network waits must not prevent the HTTP receiver from accepting new notes.
        with self.lock("worker.lock"):
            with self.lock():
                # Also covers a previous process exiting after commit but before backup.
                self.snapshot()
                with self.connect() as db:
                    rows = db.execute("SELECT * FROM deliveries WHERE status!='synced' ORDER BY created,source,id").fetchall()
            if not rows:
                return True
            for row in rows:
                if row["status"] == "pending":
                    if not call(["put", row["path"]], row["body"]):
                        return False
                    with self.lock():
                        with self.connect() as db:
                            db.execute("UPDATE deliveries SET status='staged',updated=? WHERE source=? AND id=?", (int(time.time()), row["source"], row["id"]))
                        self.snapshot()
            local = call(["ls"])
            if local is None:
                return False
            present = {line.split("\t")[0] for line in (local.stdout or "").splitlines() if "\t" in line}
            if any(row["path"] not in present for row in rows):
                # Missing CLI data (or an unresolved move after an uncertain sync)
                # must be reconciled; never silently mark absent notes as delivered.
                return False
            # All staged rows are backed up before any replication takes place.
            # Retrying a staged row only syncs; it must not put the original text again.
            with self.lock():
                with self.connect() as db:
                    db.execute("UPDATE deliveries SET attempts=attempts+1 WHERE status='staged'")
                self.snapshot()
            if not call(["sync"]):
                return False
            with self.lock():
                with self.connect() as db:
                    db.execute("UPDATE deliveries SET status='synced',updated=? WHERE status='staged'", (int(time.time()),))
                self.snapshot()
            return True

    def ledger(self, source):
        if source not in FOLDERS:
            raise ValueError("Unknown source")
        with self.connect() as db:
            rows = db.execute("SELECT id,title,status,attempts FROM deliveries WHERE source=? ORDER BY created,id", (source,)).fetchall()
        title = "柒声交付台账" if source == "qisheng" else "小红书交付台账"
        lines = [f"# {title}", "", "> 自动生成的交付记录视图，不代表手机已收到，也不代替库内内容清单。", "", "| ID | 标题 | 状态 | 同步尝试 |", "|---|---|---|---|"]
        for row in rows:
            clean = row["title"].replace("|", "\\|").replace("\n", " ").replace("\r", " ")
            status = {"pending": "待写入", "staged": "待同步", "synced": "已同步到服务端"}[row["status"]]
            lines.append(f"| {row['id']} | {clean} | {status} | {row['attempts']} |")
        return "\n".join(lines) + "\n"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--state", required=True)
    parser.add_argument("--backup", required=True)
    sub = parser.add_subparsers(dest="action", required=True)
    worker = sub.add_parser("deliver")
    command = worker.add_mutually_exclusive_group(required=True)
    command.add_argument("--cli-command", help="JSON argv prefix including settings and database arguments")
    command.add_argument("--cli-command-file", help="Private file containing the JSON argv prefix")
    ledger = sub.add_parser("ledger")
    ledger.add_argument("source", choices=FOLDERS)
    sub.add_parser("backup")
    args = parser.parse_args()
    store = QueueStore(args.state, args.backup)
    if args.action == "deliver":
        configured = Path(args.cli_command_file).read_text() if args.cli_command_file else args.cli_command
        raise SystemExit(0 if store.deliver(json.loads(configured)) else 1)
    if args.action == "ledger":
        print(store.ledger(args.source), end="")
    if args.action == "backup":
        with store.lock():
            print(store.snapshot().name)


if __name__ == "__main__":
    main()
