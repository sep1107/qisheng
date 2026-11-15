"""Pinned official LiveSync CLI upload adapter with a read-restricting CouchDB proxy."""
import argparse
import base64
import hashlib
import hmac
import http.client
import json
import os
import re
import subprocess
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, quote, unquote, urlencode, urlsplit

IMAGE = 'ghcr.io/vrtmrz/livesync-cli@sha256:c49e56f7390464ccb7754bb85e5b8c776993670120429a4b7cc31183f311dc24'
BUNDLE_SHA = 'a1d01a4e7815dd9ddd3cfd0240ac0621af0bff2748e566535b9abaf3ba5812fc'
FOLDER = '00.收集箱Inbox/07.柒声Qisheng/'
VERSION = 'obsydian_livesync_version'

def prepare(source, target):
    text = Path(source).read_text()
    if hashlib.sha256(text.encode()).hexdigest() != BUNDLE_SHA:
        raise ValueError('Official CLI bundle differs from audited version')
    start = text.index('\tif (options.command === "sync") {')
    end = text.index('\n\tif (options.command === "journal-skip")', start)
    Path(target).write_text(text[:start] + '\tif (options.command === "sync") { return await core.services.replication.replicateAllToRemote(true); }' + text[end:])

def read_owned(root):
    p = root / 'owned.json'
    return json.loads(p.read_text()) if p.exists() else {}

def cli(root, command):
    if not command or command[0] not in ['put', 'ls', 'sync']:
        raise ValueError('Only queue put, ls and upload sync are permitted')
    if command[0] == 'put' and (len(command) != 2 or not command[1].startswith(FOLDER) or '/' in command[1][len(FOLDER):] or not command[1].endswith('.md')):
        raise ValueError('Destination must be a note in the Qisheng inbox')
    prefix = ['sudo', '-n', 'docker', 'run', '--rm', '-i', '--network', 'host', '--user', f'{os.getuid()}:{os.getgid()}', '--memory', '1024m', '--cpus', '1', '-v', f'{root}/cli:/data', '-v', f'{root}/upload.cjs:/app/dist/index.cjs:ro', IMAGE, '--settings', '/data/settings.json']
    result = subprocess.run(prefix + command, capture_output=True, timeout=180)
    if result.returncode:
        raise RuntimeError('Upload CLI failed; queued note retained')
    if command[0] == 'put':
        result_info = subprocess.run(prefix + ['info', command[1]], capture_output=True, timeout=180)
        if result_info.returncode: raise RuntimeError('Could not register uploaded note IDs')
        info = json.loads(result_info.stdout)
        if info['path'] != command[1]: raise ValueError('CLI path mismatch')
        owned = read_owned(root)
        owned[command[1]] = [info['id'], *info['children']]
        temp = root / 'owned.writing'
        temp.write_text(json.dumps(owned)); temp.chmod(0o600)
        with temp.open('rb') as f: os.fsync(f.fileno())
        temp.replace(root / 'owned.json')
    if command[0] == 'ls':
        os.write(1, result.stdout)

def permitted(method, path, query, body, database, owned):
    base = '/' + database
    if method in ['GET', 'HEAD'] and path in ['/', base, base + '/']:
        return path, query
    if not path.startswith(base + '/'): return None
    leaf = path[len(base) + 1:]
    local = re.fullmatch(r'_local/[A-Za-z0-9_.=+-]+', leaf) is not None
    if method in ['GET', 'HEAD', 'PUT'] and (local or leaf == VERSION or (method != 'PUT' and leaf in owned)):
        return path, query
    if leaf == '_all_docs' and method == 'GET':
        if 'startkey' in query and 'endkey' in query:
            lo, hi = json.loads(query['startkey'][0]), json.loads(query['endkey'][0])
            # Replace compatibility sampling with queries for this client's own IDs.
            return path, {'keys': [json.dumps(sorted(k for k in owned if lo <= k <= hi))]}
        keys = json.loads(query.get('keys', ['null'])[0])
        if isinstance(keys, list) and set(keys) <= owned | {VERSION}: return path, query
    if leaf == '_revs_diff' and method == 'POST':
        if set(json.loads(body)) <= owned | {VERSION}: return path, query
    if leaf == '_bulk_docs' and method == 'POST':
        docs = json.loads(body).get('docs', [])
        if docs and all(d.get('_id') in owned | {VERSION} for d in docs): return path, query
    if leaf == '_ensure_full_commit' and method == 'POST': return path, query
    return None

def guard(root):
    config = json.loads((root / 'connection.json').read_text())
    backend = urlsplit(config['couchDB_URI'])
    if backend.scheme not in ['http', 'https'] or backend.query or backend.fragment: raise ValueError('Invalid backend')
    authorization = 'Basic ' + base64.b64encode((config['couchDB_USER'] + ':' + config['couchDB_PASSWORD']).encode()).decode()
    database = config['couchDB_DBNAME']
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_): pass
        def handle_request(self):
            try:
                if not hmac.compare_digest(self.headers.get('Authorization', ''), authorization):
                    self.send_error(401); return
                u = urlsplit(self.path)
                size = int(self.headers.get('Content-Length', '0'))
                if not 0 <= size <= 16 * 1024 * 1024: self.send_error(413); return
                body = self.rfile.read(size)
                owned = {ident for ids in read_owned(root).values() for ident in ids}
                approved = permitted(self.command, unquote(u.path), parse_qs(u.query), body, database, owned)
                if approved is None:
                    self.send_error(403, 'Upload-only: remote note reads are restricted'); return
                path, query = approved
                target = backend.path.rstrip('/') + quote(path, safe='/')
                if query: target += '?' + urlencode(query, doseq=True)
                connection_type = http.client.HTTPSConnection if backend.scheme == 'https' else http.client.HTTPConnection
                c = connection_type(backend.hostname, backend.port, timeout=90)
                headers = {k: v for k, v in self.headers.items() if k.lower() not in ['host', 'connection', 'content-length', 'transfer-encoding', 'authorization']}
                headers['Authorization'] = authorization
                c.request(self.command, target, body, headers)
                r = c.getresponse(); data = r.read()
                self.send_response(r.status)
                for k, v in r.getheaders():
                    if k.lower() not in ['connection', 'transfer-encoding', 'content-length']: self.send_header(k, v)
                self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data); c.close()
            except (OSError, ValueError, KeyError, http.client.HTTPException):
                self.send_error(502, 'Upload proxy unavailable')
        do_GET = do_HEAD = do_POST = do_PUT = do_DELETE = handle_request
    ThreadingHTTPServer(('127.0.0.1', 18770), Handler).serve_forever()

if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'cli', 'guard'])
    parser.add_argument('--root', type=Path)
    parser.add_argument('--source')
    parser.add_argument('--target')
    args, command = parser.parse_known_args()
    if args.command == 'prepare': prepare(args.source, args.target)
    elif args.command == 'guard': guard(args.root.resolve())
    else: cli(args.root.resolve(), command)
