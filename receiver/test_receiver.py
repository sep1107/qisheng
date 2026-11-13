import hashlib
import http.client
import json
import tempfile
import threading
import unittest
from pathlib import Path
from http.server import ThreadingHTTPServer
from qisheng_receiver import Store, handler, MAX_BODY


class ReceiverTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.root = Path(self.temp.name)
        self.store = Store(self.root / "inbox", self.root / "state")
        self.server = ThreadingHTTPServer(("127.0.0.1", 0), handler(self.store, "test-token-12345678901234567890"))
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        text = "# 测试录音\n\n中文原文和总结。\n"
        self.note = dict(schema=1, id="device-session-001", title="../测试/录音", markdown=text, sha256=hashlib.sha256(text.encode()).hexdigest())

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.temp.cleanup()

    def request(self, note=None, token="test-token-12345678901234567890", size=None):
        client = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        body = json.dumps(note or self.note).encode()
        headers = {"Authorization": "Bearer " + token, "Content-Type": "application/json"}
        if size is not None:
            headers["Content-Length"] = str(size)
        client.request("POST", "/v1/notes", body, headers)
        response = client.getresponse()
        result = response.status, json.loads(response.read())
        client.close()
        return result

    def test_save_duplicate_and_changed_note_without_overwrite(self):
        self.assertEqual(201, self.request()[0])
        code, ack = self.request()
        self.assertEqual((200, "duplicate"), (code, ack["status"]))
        files = list(self.store.inbox.glob("*.md"))
        self.assertEqual(1, len(files))
        changed = dict(self.note, markdown="new text", sha256=hashlib.sha256(b"new text").hexdigest())
        self.assertEqual(409, self.request(changed)[0])
        self.assertEqual(self.note["markdown"], files[0].read_text())
        files[0].write_text("edited in Obsidian")
        self.assertEqual(409, self.request()[0])
        self.assertEqual("edited in Obsidian", files[0].read_text())

    def test_reject_unauthorized_invalid_id_hash_and_oversized_body(self):
        self.assertEqual(401, self.request(token="wrong")[0])
        self.assertEqual(400, self.request(dict(self.note, id="../../escape"))[0])
        self.assertEqual(400, self.request(dict(self.note, sha256="wrong"))[0])
        self.assertEqual(413, self.request(size=MAX_BODY + 1)[0])
        self.assertEqual([], list(self.store.inbox.iterdir()))

    def test_missing_note_is_not_silently_recreated(self):
        self.assertEqual(201, self.request()[0])
        path = next(self.store.inbox.glob("*.md"))
        path.rename(self.root / "user-moved.md")
        self.assertEqual(409, self.request()[0])
        self.assertEqual([], list(self.store.inbox.iterdir()))


if __name__ == "__main__":
    unittest.main()
