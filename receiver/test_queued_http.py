import hashlib
import http.client
import json
import tempfile
import threading
import unittest
from http.server import ThreadingHTTPServer
from pathlib import Path
from subprocess import CompletedProcess
from unittest.mock import patch

from delivery_queue import QueueStore
from qisheng_receiver import handler


class QueuedHttpTest(unittest.TestCase):
    def test_queue_receipt_backup_failure_and_synced_duplicate(self):
        with tempfile.TemporaryDirectory() as directory:
            store = QueueStore(Path(directory) / "state", Path(directory) / "backup")
            token = "test-token-12345678901234567890"
            server = ThreadingHTTPServer(("127.0.0.1", 0), handler(store, token))
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            note = dict(schema=1, id="session-http", title="测试", markdown="# 中文", sha256=hashlib.sha256("# 中文".encode()).hexdigest())
            def send():
                client = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=3)
                client.request("POST", "/v1/notes", json.dumps(note), {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
                response = client.getresponse()
                result = response.status, json.loads(response.read())
                client.close()
                return result
            try:
                with patch.object(store, "snapshot", side_effect=OSError("backup unavailable")):
                    self.assertEqual(503, send()[0])
                self.assertEqual((202, "queued"), (lambda r: (r[0], r[1]["status"]))(send()))
                with store.connect() as db:
                    path = db.execute("SELECT path FROM deliveries").fetchone()[0]
                with patch("delivery_queue.subprocess.run", return_value=CompletedProcess([], 0, stdout=path + "\t1\t0\trev")):
                    self.assertTrue(store.deliver(["cli"]))
                code, ack = send()
                self.assertEqual((200, "duplicate", note["id"], note["sha256"]), (code, ack["status"], ack["id"], ack["sha256"]))
            finally:
                server.shutdown()
                server.server_close()
                thread.join()


if __name__ == "__main__":
    unittest.main()
