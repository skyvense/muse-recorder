#!/usr/bin/env python3
"""
Muse Recorder - receiver for the Android companion app.

Stdlib-only HTTP server. The Android app POSTs each finished audio segment
as multipart/form-data to /receive with header: Authorization: Bearer <token>

Files are saved to:  <base>/inbox/<YYYY-MM-DD>/rec_....m4a
and one JSON line per upload is appended to <base>/manifest.jsonl

Usage:
    MUSE_RECORDER_TOKEN='choose-a-long-random-token' \
        python3 server.py --port 8765 --base ~/workspace/muse-recordings

Expose it to the phone with ONE of:
  * Tailscale: join this machine to your tailnet, install Tailscale on the
    phone, use http://<this-machine-tailnet-ip>:8765/receive in the app.
  * Cloudflare Tunnel:  cloudflared tunnel --url http://localhost:8765
    then use the https://*.trycloudflare.com/receive URL in the app.
Do NOT port-forward this directly to the public internet; the bearer token
is the only auth.
"""
import argparse
import json
import os
import re
import sys
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

SAFE_NAME = re.compile(r"[^A-Za-z0-9._-]")


def parse_multipart(body: bytes, boundary: str):
    """Minimal multipart/form-data parser (fields + one file part)."""
    fields, files = {}, {}
    delimiter = b"--" + boundary.encode()
    for part in body.split(delimiter):
        part = part.strip(b"\r\n")
        if not part or part == b"--":
            continue
        if part.endswith(b"--"):
            part = part[:-2].strip(b"\r\n")
        header_blob, _, payload = part.partition(b"\r\n\r\n")
        headers = header_blob.decode("utf-8", "replace")
        name_match = re.search(r'name="([^"]+)"', headers)
        if not name_match:
            continue
        name = name_match.group(1)
        file_match = re.search(r'filename="([^"]*)"', headers)
        if file_match:
            files[name] = (file_match.group(1), payload)
        else:
            fields[name] = payload.decode("utf-8", "replace")
    return fields, files


class Handler(BaseHTTPRequestHandler):
    server_version = "MuseRecorderReceiver/1.0"

    def log_message(self, fmt, *args):  # keep stdout useful but compact
        sys.stderr.write("%s - %s\n" % (self.address_string(), fmt % args))

    def _authorized(self) -> bool:
        token = self.server.token  # type: ignore[attr-defined]
        if not token:
            return True  # token not configured on server side
        return self.headers.get("Authorization", "") == f"Bearer {token}"

    def do_GET(self):
        if self.path.rstrip("/") in ("/health", ""):
            self._send(200, b'{"ok": true}')
        else:
            self._send(404, b'{"error": "not found"}')

    def do_POST(self):
        if self.path.rstrip("/") != "/receive":
            self._send(404, b'{"error": "not found"}')
            return
        if not self._authorized():
            self._send(401, b'{"error": "unauthorized"}')
            return
        ctype = self.headers.get("Content-Type", "")
        m = re.search(r"boundary=([^;]+)", ctype)
        if not m:
            self._send(400, b'{"error": "missing boundary"}')
            return
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > 512 * 1024 * 1024:
            self._send(413, b'{"error": "bad size"}')
            return
        body = self.rfile.read(length)
        fields, files = parse_multipart(body, m.group(1).strip())
        if "file" not in files:
            self._send(400, b'{"error": "no file part"}')
            return
        filename, payload = files["file"]
        filename = SAFE_NAME.sub("_", os.path.basename(filename or "segment.m4a"))
        # Folder by segment date if the name looks like rec_YYYY-MM-DD_...
        date_part = filename[4:14] if filename.startswith("rec_") and len(filename) > 14 \
            else datetime.now().strftime("%Y-%m-%d")
        if not re.fullmatch(r"\d{4}-\d{2}-\d{2}", date_part):
            date_part = datetime.now().strftime("%Y-%m-%d")
        base: Path = self.server.base  # type: ignore[attr-defined]
        day_dir = base / "inbox" / date_part
        day_dir.mkdir(parents=True, exist_ok=True)
        dest = day_dir / filename
        if dest.exists():  # duplicate upload of the same segment: keep first
            self._send(200, b'{"ok": true, "duplicate": true}')
            return
        dest.write_bytes(payload)
        record = {
            "received_at": datetime.now().isoformat(timespec="seconds"),
            "date": date_part,
            "file": str(dest),
            "bytes": len(payload),
            "segment_start": fields.get("segment_start", ""),
            "device": fields.get("device", ""),
        }
        with open(base / "manifest.jsonl", "a", encoding="utf-8") as fh:
            fh.write(json.dumps(record, ensure_ascii=False) + "\n")
        print(f"saved {dest} ({len(payload)} bytes) from {record['device']}", flush=True)
        self._send(200, b'{"ok": true}')

    def _send(self, code, body: bytes):
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8765)
    ap.add_argument("--host", default="0.0.0.0")
    ap.add_argument("--base", default=os.path.expanduser("~/workspace/muse-recordings"))
    args = ap.parse_args()

    base = Path(args.base)
    (base / "inbox").mkdir(parents=True, exist_ok=True)
    token = os.environ.get("MUSE_RECORDER_TOKEN", "")
    if not token:
        print("WARNING: MUSE_RECORDER_TOKEN is not set; anyone who can reach "
              "this port can upload. Set it and use the same token in the app.",
              file=sys.stderr)

    httpd = ThreadingHTTPServer((args.host, args.port), Handler)
    httpd.token = token      # type: ignore[attr-defined]
    httpd.base = base        # type: ignore[attr-defined]
    print(f"Muse Recorder receiver on {args.host}:{args.port}, saving to {base}/inbox")
    httpd.serve_forever()


if __name__ == "__main__":
    main()
