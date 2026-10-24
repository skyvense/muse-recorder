#!/usr/bin/env python3
"""
Muse Recorder - pull one day's segments + event markers from an
S3-compatible bucket (AWS S3 / Cloudflare R2 / Backblaze B2 / Aliyun OSS /
Tencent COS / Qiniu Kodo). Stdlib only; SigV4 signing implemented here.

App layout in the bucket:
    <YYYY-MM-DD>/rec_....m4a
    markers/events-<YYYY-MM-DD>.jsonl

Downloads land in:
    <base>/inbox/<date>/...           (for summarize_day.py)
    <base>/markers/events-<date>.jsonl

Config via environment:
    S3_ENDPOINT     e.g. https://<account>.r2.cloudflarestorage.com
    S3_BUCKET       e.g. recordings
    S3_ACCESS_KEY / S3_SECRET_KEY
    S3_REGION       default "auto"
    S3_STYLE        "path" (default) or "virtual"
    S3_DELETE_AFTER "1" to delete cloud objects after a verified download

Usage:
    python3 fetch_day_from_s3.py [--date YYYY-MM-DD] [--base ~/workspace/muse-recordings]
"""
import argparse
import hashlib
import hmac
import json
import os
import sys
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from datetime import datetime, timedelta, timezone
from pathlib import Path


def _sign(key, msg):
    return hmac.new(key, msg.encode(), hashlib.sha256).digest()


def authorization(method, canonical_uri, canonical_query, headers, payload_hash,
                  region, service, access, secret, amz_date):
    date_stamp = amz_date[:8]
    canon_headers = "".join(f"{k}:{v.strip()}\n" for k, v in sorted(headers.items()))
    signed_names = ";".join(sorted(headers))
    canonical_request = "\n".join([method, canonical_uri, canonical_query,
                                   canon_headers, signed_names, payload_hash])
    scope = f"{date_stamp}/{region}/{service}/aws4_request"
    string_to_sign = "\n".join(["AWS4-HMAC-SHA256", amz_date, scope,
                                hashlib.sha256(canonical_request.encode()).hexdigest()])
    k = _sign(("AWS4" + secret).encode(), date_stamp)
    k = _sign(k, region)
    k = _sign(k, service)
    k = _sign(k, "aws4_request")
    signature = hmac.new(k, string_to_sign.encode(), hashlib.sha256).hexdigest()
    return (f"AWS4-HMAC-SHA256 Credential={access}/{scope}, "
            f"SignedHeaders={signed_names}, Signature={signature}")


class S3:
    def __init__(self):
        self.endpoint = os.environ["S3_ENDPOINT"].rstrip("/")
        self.bucket = os.environ["S3_BUCKET"]
        self.access = os.environ["S3_ACCESS_KEY"]
        self.secret = os.environ["S3_SECRET_KEY"]
        self.region = os.environ.get("S3_REGION", "auto")
        self.virtual = os.environ.get("S3_STYLE", "path") == "virtual"
        parts = urllib.parse.urlparse(self.endpoint)
        self.scheme, self.endpoint_host = parts.scheme, parts.netloc

    def _host_path(self, key):
        if self.virtual:
            return f"{self.bucket}.{self.endpoint_host}", "/" + key
        return self.endpoint_host, f"/{self.bucket}/{key}"

    def request(self, method, key="", query=""):
        host, path = self._host_path(key)
        amz_date = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        payload_hash = hashlib.sha256(b"").hexdigest()
        headers = {"host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": amz_date}
        auth = authorization(method, urllib.parse.quote(path, safe="/-_.~"), query,
                             headers, payload_hash, self.region, "s3",
                             self.access, self.secret, amz_date)
        url = f"{self.scheme}://{host}{urllib.parse.quote(path, safe='/-_.~')}"
        if query:
            url += "?" + query
        req = urllib.request.Request(url, method=method, headers={
            "Authorization": auth, "x-amz-date": amz_date,
            "x-amz-content-sha256": payload_hash})
        with urllib.request.urlopen(req, timeout=600) as resp:
            return resp.read()

    def list_keys(self, prefix):
        keys, token = [], ""
        while True:
            q = {"list-type": "2", "prefix": prefix}
            if token:
                q["continuation-token"] = token
            query = urllib.parse.urlencode(q)
            host, _ = self._host_path("")
            amz_date = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
            payload_hash = hashlib.sha256(b"").hexdigest()
            headers = {"host": host, "x-amz-content-sha256": payload_hash, "x-amz-date": amz_date}
            root_path = f"/{self.bucket}" if not self.virtual else "/"
            auth = authorization("GET", root_path, query, headers, payload_hash,
                                 self.region, "s3", self.access, self.secret, amz_date)
            url = f"{self.scheme}://{host}{root_path}?{query}"
            req = urllib.request.Request(url, headers={
                "Authorization": auth, "x-amz-date": amz_date,
                "x-amz-content-sha256": payload_hash})
            with urllib.request.urlopen(req, timeout=120) as resp:
                xml = resp.read()
            ns = {"s3": "http://s3.amazonaws.com/doc/2006-03-01/"}
            tree = ET.fromstring(xml)
            keys += [e.text for e in tree.findall(".//s3:Contents/s3:Key", ns)]
            truncated = tree.findtext(".//s3:IsTruncated", default="false", namespaces=ns)
            if truncated != "true":
                return keys
            token = tree.findtext(".//s3:NextContinuationToken", namespaces=ns) or ""


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=None)
    ap.add_argument("--base", default=os.path.expanduser("~/workspace/muse-recordings"))
    args = ap.parse_args()
    for var in ("S3_ENDPOINT", "S3_BUCKET", "S3_ACCESS_KEY", "S3_SECRET_KEY"):
        if not os.environ.get(var):
            print(f"MISSING_CONFIG: set {var}", file=sys.stderr)
            return 2
    s3 = S3()
    date = args.date or (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
    base = Path(args.base)
    delete_after = os.environ.get("S3_DELETE_AFTER") == "1"

    seg_keys = sorted(k for k in s3.list_keys(f"{date}/") if k.endswith(".m4a"))
    # Rescue self-notes an app bug (fixed in v1.6.2) filed under unknown-date/:
    # note files carry their date in the name, so they can be re-homed here.
    rescued = sorted(k for k in s3.list_keys("unknown-date/")
                     if k.endswith(".m4a") and f"note_{date}_" in k)
    seg_keys += rescued
    marker_keys = [k for k in s3.list_keys("markers/") if k.endswith(f"events-{date}.jsonl")]
    if not seg_keys and not marker_keys:
        print(f"NO_FILES for {date} in bucket {s3.bucket}")
        return 0

    fetched = 0
    for key in seg_keys:
        dest = base / "inbox" / date / Path(key).name
        dest.parent.mkdir(parents=True, exist_ok=True)
        if dest.exists() and dest.stat().st_size > 0:
            continue
        data = s3.request("GET", key)
        dest.write_bytes(data)
        fetched += 1
        print(f"fetched {key} ({len(data)} bytes)")
        if delete_after:
            try:
                s3.request("DELETE", key)
                print(f"deleted cloud copy {key}")
            except Exception as e:
                # Housekeeping must never fail the fetch: the local copy is safe.
                print(f"WARNING: could not delete cloud copy {key}: {e}")
    for key in marker_keys:
        dest = base / "markers" / Path(key).name
        dest.parent.mkdir(parents=True, exist_ok=True)
        data = s3.request("GET", key)
        dest.write_bytes(data)
        print(f"fetched {key} ({len(data)} bytes, {len(data.splitlines())} events)")
    print(f"DONE {date}: {fetched} new segments, {len(seg_keys)} total in bucket")
    return 0


if __name__ == "__main__":
    sys.exit(main())
