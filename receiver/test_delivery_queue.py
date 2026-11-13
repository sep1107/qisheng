import hashlib
import sqlite3
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch
from subprocess import CompletedProcess

from delivery_queue import QueueStore
from note_protocol import NoteConflict


class QueueTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = QueueStore(self.root / "state", self.root / "backup")
        self.note = dict(schema=1, id="session-001", title="会议", markdown="# 中文原文", sha256=hashlib.sha256("# 中文原文".encode()).hexdigest())

    def tearDown(self):
        self.temp.cleanup()

    def rows(self):
        with self.store.connect() as db:
            return db.execute("SELECT * FROM deliveries ORDER BY source").fetchall()

    def success(self, argv, **kwargs):
        return CompletedProcess(argv, 0, stdout="\n".join(row["path"] + "\t1\t0\trev" for row in self.rows()))

    def test_source_namespaces_conflict_and_separate_ledgers(self):
        self.assertEqual("queued", self.store.save(self.note)["status"])
        self.store.enqueue("rednote", self.note)
        self.assertEqual(2, len(self.rows()))
        changed = dict(self.note, markdown="changed", sha256=hashlib.sha256(b"changed").hexdigest())
        with self.assertRaises(NoteConflict):
            self.store.save(changed)
        self.assertEqual("# 柒声交付台账", self.store.ledger("qisheng").splitlines()[0])
        self.assertEqual(1, self.store.ledger("qisheng").count("| session-001 |"))
        self.assertEqual(1, self.store.ledger("rednote").count("| session-001 |"))

    def test_offline_restart_retry_never_puts_staged_note_again(self):
        self.store.save(self.note)
        calls = []
        def offline(argv, **kwargs):
            calls.append(argv)
            return CompletedProcess(argv, 1) if argv[-1] == "sync" else self.success(argv)
        with patch("delivery_queue.subprocess.run", offline):
            self.assertFalse(self.store.deliver(["cli"]))
        self.assertEqual("staged", self.rows()[0]["status"])
        self.store = QueueStore(self.root / "state", self.root / "backup")
        calls.clear()
        with patch("delivery_queue.subprocess.run", side_effect=lambda argv, **kw: calls.append(argv) or self.success(argv)):
            self.assertTrue(self.store.deliver(["cli"]))
        self.assertEqual([["cli", "ls"], ["cli", "sync"]], calls)
        self.assertEqual("duplicate", self.store.save(self.note)["status"])
        self.assertEqual("synced", self.rows()[0]["status"])

    def test_backup_failure_no_success_and_retry_repairs_backup(self):
        with patch.object(self.store, "snapshot", side_effect=OSError("disk failure")):
            with self.assertRaises(OSError):
                self.store.save(self.note)
        self.assertEqual("queued", self.store.save(self.note)["status"])
        backups = list(self.store.backup.glob("*.sqlite3"))
        self.assertEqual(1, len(backups))
        with sqlite3.connect(backups[0]) as db:
            self.assertEqual("ok", db.execute("PRAGMA integrity_check").fetchone()[0])
            self.assertEqual(self.note["markdown"], db.execute("SELECT body FROM deliveries").fetchone()[0])
        self.assertEqual(0o600, backups[0].stat().st_mode & 0o777)

    def test_sync_success_before_receipt_crash_retry_only_syncs(self):
        self.store.save(self.note)
        real_snapshot = self.store.snapshot
        snapshots = 0
        def fail_final_backup():
            nonlocal snapshots
            snapshots += 1
            if snapshots == 4:
                raise OSError("simulated exit after final commit")
            return real_snapshot()
        with patch("delivery_queue.subprocess.run", side_effect=self.success), patch.object(self.store, "snapshot", side_effect=fail_final_backup):
            with self.assertRaises(OSError):
                self.store.deliver(["cli"])
        with patch("delivery_queue.subprocess.run") as runner:
            self.assertTrue(self.store.deliver(["cli"]))
            runner.assert_not_called()
        self.assertEqual("duplicate", self.store.save(self.note)["status"])

    def test_restore_backup_preserves_receipts_and_missing_database_fails_closed(self):
        import shutil
        self.store.save(self.note)
        with patch("delivery_queue.subprocess.run", side_effect=self.success):
            self.store.deliver(["cli"])
        latest = sorted(self.store.backup.glob("*.sqlite3"))[-1]
        restore = self.root / "restore"
        restore.mkdir()
        shutil.copy2(latest, restore / "delivery.sqlite3")
        recovered = QueueStore(restore, self.root / "recovered-backups")
        self.assertEqual("duplicate", recovered.save(self.note)["status"])
        with self.assertRaises(OSError):
            QueueStore(self.root / "missing-state", self.store.backup)

    def test_missing_cli_cache_does_not_falsely_mark_delivered(self):
        self.store.save(self.note)
        with patch("delivery_queue.subprocess.run", return_value=CompletedProcess([], 0, stdout="")):
            self.assertFalse(self.store.deliver(["cli"]))
        self.assertEqual("staged", self.rows()[0]["status"])

    def test_unchanged_state_does_not_generate_unbounded_backup_copies(self):
        self.store.save(self.note)
        before = set(self.store.backup.glob("*.sqlite3"))
        self.store.save(self.note)
        self.store.save(self.note)
        self.assertEqual(before, set(self.store.backup.glob("*.sqlite3")))

    def test_slow_sync_does_not_block_accepting_and_backing_up_new_note(self):
        import threading
        from concurrent.futures import ThreadPoolExecutor
        self.store.save(self.note)
        entered, release = threading.Event(), threading.Event()
        def slow_sync(argv, **kwargs):
            if argv[-1] == "sync":
                entered.set()
                if not release.wait(5):
                    raise TimeoutError("test worker was not released")
            return self.success(argv)
        with ThreadPoolExecutor(max_workers=2) as pool, patch("delivery_queue.subprocess.run", side_effect=slow_sync):
            worker = pool.submit(self.store.deliver, ["cli"])
            try:
                self.assertTrue(entered.wait(3))
                accepted = pool.submit(self.store.save, dict(self.note, id="next-session"))
                self.assertEqual("queued", accepted.result(timeout=2)["status"])
            finally:
                release.set()
            self.assertTrue(worker.result(timeout=3))
        states = {row["id"]: row["status"] for row in self.rows()}
        self.assertEqual({"session-001": "synced", "next-session": "pending"}, states)


if __name__ == "__main__":
    unittest.main()
