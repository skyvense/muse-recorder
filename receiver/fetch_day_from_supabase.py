#!/usr/bin/env python3
"""
Muse Recorder - pull one day's segments from Supabase Storage.

The Android app (Supabase mode) uploads to:
    bucket "<bucket>"/<YYYY-MM-DD>/rec_....m4a
This script lists that prefix via the Storage API and downloads every file
into <base>/inbox/<date>/ so summarize_day.py works unchanged afterwards.

Config via environment:
    SUPABASE_URL     e.g. https://abcdefgh.supabase.co
    SUPABASE_KEY     service_role key (or anon key with select/download policy)
    SUPABASE_BUCKET  default "recordings"

Usage:
    python3 fetch_day_from_supabase.py [--date YYYY-MM-DD] [--base ~/workspace/muse-recordings]
Default date is yesterday (local time).
"""
import argparse
import json
import os
import sys
import urllib.request
from datetime import datetime, timedelta
from pathlib import Path


def api(url: str, key: str, data: bytes = None, ctype: str = None) -> bytes:
    headers = {"Authorization": f"Bearer {key}", "apikey": key}
    if ctype:
        headers["Content-Type"] = ctype
    req = urllib.request.Request(url, data=data, headers=headers,
                                 method="POST" if data is not None else "GET")
    with urllib.request.urlopen(req, timeout=300) as resp:
        return resp.read()


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=None)
    ap.add_argument("--base", default=os.path.expanduser("~/workspace/muse-recordings"))
    args = ap.parse_args()

    sb_url = os.environ.get("SUPABASE_URL", "").rstrip("/")
    key = os.environ.get("SUPABASE_KEY", "")
    bucket = os.environ.get("SUPABASE_BUCKET", "recordings")
    if not sb_url or not key:
        print("MISSING_CONFIG: set SUPABASE_URL and SUPABASE_KEY", file=sys.stderr)
        return 2

    date = args.date or (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
    prefix = f"{date}/"
    list_url = f"{sb_url}/storage/v1/object/list/{bucket}"
    files = []
    offset = 0
    while True:  # paginate: a full day can exceed one page (e.g. 5-min segments = 288)
        payload = json.dumps({"prefix": prefix, "limit": 200, "offset": offset}).encode()
        entries = json.loads(api(list_url, key, payload, "application/json").decode())
        files.extend(e["name"] for e in entries if e.get("name", "").endswith(".m4a"))
        if len(entries) < 200:
            break
        offset += 200
    if not files:
        print(f"NO_FILES in supabase://{bucket}/{prefix}")
        return 0

    out_dir = Path(args.base) / "inbox" / date
    out_dir.mkdir(parents=True, exist_ok=True)
    fetched = 0
    for name in sorted(files):
        dest = out_dir / name
        if dest.exists() and dest.stat().st_size > 0:
            continue
        data = api(f"{sb_url}/storage/v1/object/{bucket}/{prefix}{name}", key)
        dest.write_bytes(data)
        fetched += 1
        print(f"fetched {name} ({len(data)} bytes)")
    print(f"DONE {date}: {fetched} new, {len(files)} total in bucket prefix")
    return 0


if __name__ == "__main__":
    sys.exit(main())
