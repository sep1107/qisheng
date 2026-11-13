"""Opt-in synthetic integration test; creates only an isolated Docker test network."""
import argparse
import hashlib
import http.client
import json
import os
import secrets
import shutil
import sqlite3
import subprocess
import sys
import threading
import time
from http.server import ThreadingHTTPServer
from pathlib import Path
from unittest.mock import patch

from delivery_queue import QueueStore
from qisheng_receiver import handler

CLI = "ghcr.io/vrtmrz/livesync-cli@sha256:c49e56f7390464ccb7754bb85e5b8c776993670120429a4b7cc31183f311dc24"
COUCH = "couchdb@sha256:1c0a30a54535377bc9ae23f42efb1ca4e92abe796f78ec98b768ac1d5874cc1b"


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", required=True, help="New temporary directory, never an existing vault")
    parser.add_argument("--adapter", required=True, help="Path to Rednote deliver_notes.py")
    args = parser.parse_args()
    root = Path(args.root).resolve()
    if not str(root).startswith("/tmp/"):
        raise ValueError("Evaluation root must be a new /tmp directory")
    root.mkdir(mode=0o700, exist_ok=False)
    network = "qs-delivery-eval-" + secrets.token_hex(5)
    report = {"checks": {}, "root": str(root), "container": network, "synthetic_only": True,
              "real_apk_or_phone_tested": False, "production_sync_changed": False}
    def check(name, value):
        report["checks"][name] = bool(value)
        (root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"check": name, "pass": bool(value)}), flush=True)
        if not value:
            raise AssertionError(name)
    real_run = subprocess.run
    def execute(argv, content=None):
        result = real_run(argv, input=content, capture_output=True, text=True, timeout=180)
        if result.returncode:
            raise RuntimeError("Test subprocess failed (exit %s); no request/credential logs emitted" % result.returncode)
        return result.stdout
    def docker(argv, content=None):
        return execute(["sudo", "-n", "docker", *argv], content)
    def prefix(who, online=True):
        return ["sudo", "-n", "docker", "run", "--rm", "-i", "--network", network if online else "none",
                "--user", f"{os.getuid()}:{os.getgid()}", "--memory", "1024m", "--cpus", "1",
                "-v", f"{root/who}:/data", CLI, "--settings", "/data/settings.json"]
    def cli(who, argv, content=None):
        return execute(prefix(who) + argv, content)
    def paths(who):
        return set(line.split("\t")[0] for line in cli(who, ["ls"]).splitlines() if "\t" in line)
    def payload(ident, text, title="模拟会议"):
        return dict(schema=1, id=ident, title=title, markdown=text, sha256=hashlib.sha256(text.encode()).hexdigest())
    server = None
    thread = None
    started = False
    docker(["network", "create", "--internal", network])
    try:
        user, password, phrase = "synthetic", secrets.token_urlsafe(24), secrets.token_urlsafe(32)
        docker(["run", "-d", "--name", network, "--network", network, "--network-alias", "testcouch",
                "--memory", "768m", "--cpus", "1", "-e", "COUCHDB_USER=" + user, "-e", "COUCHDB_PASSWORD=" + password, COUCH])
        started = True
        for _ in range(40):
            try:
                docker(["exec", network, "curl", "-fsS", "http://127.0.0.1:5984/"])
                break
            except RuntimeError:
                time.sleep(0.5)
        for who in ("delivery", "phone"):
            (root / who).mkdir()
            docker(["run", "--rm", "--network", "none", "--user", f"{os.getuid()}:{os.getgid()}",
                    "-v", f"{root/who}:/data", CLI, "init-settings", "/data/settings.json"])
            config = json.loads((root / who / "settings.json").read_text())
            config.update(dict(couchDB_URI="http://testcouch:5984", couchDB_USER=user, couchDB_PASSWORD=password,
                               couchDB_DBNAME="synthetic_delivery", encrypt=True, passphrase=phrase,
                               usePathObfuscation=True, isConfigured=True, liveSync=False, syncOnStart=False,
                               syncOnSave=False, periodicReplication=False, pathNormalizationVersion=0,
                               idDerivationVersion=0, idDerivationKey="", hashAlg="xxhash64", enableCompression=False,
                               handleFilenameCaseSensitive=False, readChunksOnline=False, useOnlyLocalChunk=False))
            (root / who / "settings.json").write_text(json.dumps(config))
            (root / who / "settings.json").chmod(0o600)
        cli("delivery", ["sync"])
        store = QueueStore(root / "state", root / "backups")
        token = secrets.token_urlsafe(32)
        server = ThreadingHTTPServer(("127.0.0.1", 0), handler(store, token))
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        def post(note):
            connection = http.client.HTTPConnection("127.0.0.1", server.server_port, timeout=15)
            connection.request("POST", "/v1/notes", json.dumps(note), {"Authorization": "Bearer " + token, "Content-Type": "application/json"})
            response = connection.getresponse()
            value = response.status, json.loads(response.read())
            connection.close()
            return value
        def worker(online=True):
            return real_run([sys.executable, str(Path(__file__).with_name("delivery_queue.py")),
                             "--state", str(store.state), "--backup", str(store.backup), "deliver",
                             "--cli-command", json.dumps(prefix("delivery", online))], capture_output=True, text=True, timeout=240)
        qnote = payload("device-session-001", "# 模拟柒声会议\n\n## 总结\n明天三点开会。\n\n## 原文\nSYNTHETIC_QISHENG_1287\n")
        check("qisheng_http_202_after_durable_backup", post(qnote)[0] == 202 and any(store.backup.glob("*.sqlite3")))
        check("pending_http_repeat_stays_queued", post(qnote)[0] == 202)
        notes = root / "xhs/out/notes"
        notes.mkdir(parents=True)
        ident = "1234567890abcdef12345678"
        xnote = notes / "模拟收藏.md"
        xtext = f"---\nsource: https://www.xiaohongshu.com/explore/{ident}\n---\n# 模拟收藏\n\n## Transcript\nSYNTHETIC_REDNOTE_6381：这是测试转录。\n"
        xnote.write_text(xtext)
        manifest = notes.parent / "clipped.jsonl"
        manifest.write_text(json.dumps(dict(note_id=ident, status="ok", path=str(xnote))) + "\n")
        adapter = [sys.executable, args.adapter, "--receiver-code", str(Path(__file__).parent), "--state", str(store.state),
                   "--backup", str(store.backup), "--notes", str(notes), "--manifest", str(manifest)]
        execute(adapter)
        execute(adapter)
        with store.connect() as db:
            rows = db.execute("SELECT * FROM deliveries ORDER BY source").fetchall()
        check("rednote_real_adapter_idempotent_without_consuming_mac_files", len(rows) == 2 and xnote.read_text() == xtext and manifest.exists())
        qpath = next(r["path"] for r in rows if r["source"] == "qisheng")
        xpath = next(r["path"] for r in rows if r["source"] == "rednote")
        check("offline_worker_nonzero", worker(False).returncode != 0)
        with store.connect() as db:
            check("both_sources_persist_as_staged_after_disconnect", db.execute("SELECT count(*) FROM deliveries WHERE status='staged'").fetchone()[0] == 2)
        check("new_worker_process_retries_and_drains_queue", worker().returncode == 0)
        cli("phone", ["sync"])
        check("second_client_reads_both_full_markdown_notes", qnote["markdown"] in cli("phone", ["cat", qpath]) and xtext in cli("phone", ["cat", xpath]))
        check("synced_http_repeat_returns_duplicate", post(qnote)[0] == 200 and post(qnote)[1]["status"] == "duplicate")
        changed = payload(qnote["id"], "changed content")
        check("same_id_different_body_http409", post(changed)[0] == 409)
        moved = "10.资料Sources/合成测试/用户改名.md"
        cli("phone", ["put", moved], xtext + "\n用户补充测试")
        cli("phone", ["rm", xpath])
        cli("phone", ["sync"])
        cli("delivery", ["sync"])
        execute(adapter)
        check("moved_rednote_repeat_worker_success", worker().returncode == 0)
        cli("phone", ["sync"])
        check("moved_path_not_resurrected_and_user_edit_preserved", xpath not in paths("phone") and "用户补充测试" in cli("phone", ["cat", moved]))
        # Simulate loss of the success receipt after actual replication, before queue commit.
        crash_note = payload("ack-loss", "# 回执丢失测试\n合成内容")
        post(crash_note)
        class SimulatedExit(Exception):
            pass
        def exit_after_sync(argv, **kwargs):
            if argv[-1] == "sync":
                check("http_accepts_new_note_while_worker_is_syncing", post(payload("during-sync", "# 同步时继续接收"))[0] == 202)
            result = real_run(argv, **kwargs)
            if argv[-1] == "sync" and result.returncode == 0:
                raise SimulatedExit()
            return result
        try:
            with patch("delivery_queue.subprocess.run", side_effect=exit_after_sync):
                store.deliver(prefix("delivery"))
        except SimulatedExit:
            pass
        with store.connect() as db:
            crash_row = db.execute("SELECT * FROM deliveries WHERE id='ack-loss'").fetchone()
        check("actual_sync_success_before_receipt_remains_staged", crash_row["status"] == "staged")
        cli("phone", ["sync"])
        cli("phone", ["put", crash_row["path"]], "# 用户改过的版本\nKEEP_USER_EDIT")
        cli("phone", ["sync"])
        check("ack_loss_retry_worker_succeeds", worker().returncode == 0)
        cli("phone", ["sync"])
        check("ack_loss_retry_does_not_put_old_text_over_user_edit", "KEEP_USER_EDIT" in cli("phone", ["cat", crash_row["path"]]))
        cli("phone", ["rm", qpath])
        cli("phone", ["sync"])
        cli("delivery", ["sync"])
        check("deleted_qisheng_repeat_is_duplicate", post(qnote)[1]["status"] == "duplicate")
        worker()
        cli("phone", ["sync"])
        check("deleted_qisheng_not_resurrected", qpath not in paths("phone"))
        for source in ("qisheng", "rednote"):
            text = store.ledger(source)
            (root / (source + "-ledger.md")).write_text(text)
        check("separate_source_ledger_views", ident not in store.ledger("qisheng") and qnote["id"] not in store.ledger("rednote"))
        with store.lock():
            snapshot = store.snapshot()
        restore = root / "restored-state"
        restore.mkdir()
        shutil.copy2(snapshot, restore / "delivery.sqlite3")
        recovered = QueueStore(restore, root / "restored-backups")
        check("restored_backup_keeps_deleted_note_receipt", recovered.save(qnote)["status"] == "duplicate")
        with sqlite3.connect(snapshot) as backup_db:
            check("backup_integrity_and_all_source_records", backup_db.execute("PRAGMA integrity_check").fetchone()[0] == "ok" and backup_db.execute("SELECT count(*) FROM deliveries").fetchone()[0] == 4)
        with patch.object(store, "snapshot", side_effect=OSError("synthetic backup outage")):
            check("backup_failure_http503_not_false_success", post(payload("backup-outage", "# 备份故障测试"))[0] == 503)
        check("backup_recovery_repeat_safely_queued", post(payload("backup-outage", "# 备份故障测试"))[0] == 202)
        check("backup_recovery_worker_delivers", worker().returncode == 0)
        cache_note = payload("cache-loss", "# CLI 缓存丢失保护测试")
        post(cache_note)
        check("cache_test_staged_before_disconnect", worker(False).returncode != 0)
        (root / "empty-client").mkdir()
        shutil.copy2(root / "delivery/settings.json", root / "empty-client/settings.json")
        check("missing_cli_cache_fails_closed", not store.deliver(prefix("empty-client")))
        with store.connect() as db:
            check("missing_cache_note_not_falsely_synced", db.execute("SELECT status FROM deliveries WHERE id='cache-loss'").fetchone()[0] == "staged")
        check("original_cli_cache_recovers_delivery", worker().returncode == 0)
        cli("phone", ["sync"])
        with store.connect() as db:
            cache_path = db.execute("SELECT path FROM deliveries WHERE id='cache-loss'").fetchone()[0]
        check("cache_recovery_note_arrives", cache_note["markdown"] in cli("phone", ["cat", cache_path]))
        raw = docker(["exec", network, "curl", "-fsS", "-u", user + ":" + password,
                      "http://127.0.0.1:5984/synthetic_delivery/_all_docs?include_docs=true"])
        check("remote_couchdb_content_and_paths_encrypted", "SYNTHETIC_QISHENG_1287" not in raw and "SYNTHETIC_REDNOTE_6381" not in raw and qpath not in raw)
        with store.lock():
            snapshot = store.snapshot()
        report["backup_for_second_host_copy"] = str(snapshot)
        report["completed"] = True
    finally:
        if server:
            server.shutdown()
            server.server_close()
        if thread:
            thread.join()
        if started:
            docker(["stop", network])
            report["test_container_stopped"] = True
        (root / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2))
        print(json.dumps({"completed": report.get("completed", False), "checks": len(report["checks"]), "root": str(root)}), flush=True)


if __name__ == "__main__":
    main()
