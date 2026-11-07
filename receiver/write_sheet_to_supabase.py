#!/usr/bin/env python3
"""把某天的每日活动拼图写入 Supabase 的 daily_sheets 表（一行/天，upsert）。

数据来源：
  图片与统计：snapshots/analysis/daily_sheet_<date>.jpg + daily_manifest_<date>.json
              （由 snapshot_day_sheet.py 生成）
  活动说明：--caption-file 指向的文本文件（拼图任务把说明存到
              summaries/sheet_caption_<date>.md）；缺省则用空说明。

凭证只从 ~/workspace/muse-recordings/supabase.env 读（优先 service_role），
不要把 key 写进代码、聊天或其它文件。

用法：
  python3 write_sheet_to_supabase.py --date 2026-11-06 \
      [--caption-file ~/workspace/muse-recordings/summaries/sheet_caption_2026-11-06.md] \
      [--dry-run]
"""
import argparse
import base64
import json
import os
import sys
import urllib.error
import urllib.request

BASE = os.path.expanduser("~/workspace/muse-recordings")
ENV_FILE = os.path.join(BASE, "supabase.env")
ANALYSIS = os.path.join(BASE, "snapshots", "analysis")
SUMMARIES = os.path.join(BASE, "summaries")


def load_env(path):
    env = {}
    try:
        with open(path, encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                k, v = line.split("=", 1)
                env[k.strip()] = v.strip().strip('"').strip("'")
    except FileNotFoundError:
        return None
    return env


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--date", required=True)
    p.add_argument("--caption-file", default=None)
    p.add_argument("--dry-run", action="store_true")
    args = p.parse_args()
    date = args.date

    sheet_path = os.path.join(ANALYSIS, f"daily_sheet_{date}.jpg")
    manifest_path = os.path.join(ANALYSIS, f"daily_manifest_{date}.json")
    if not os.path.exists(sheet_path) or not os.path.exists(manifest_path):
        print(f"缺少拼图或 manifest：{sheet_path} / {manifest_path}（请先跑 snapshot_day_sheet.py）", file=sys.stderr)
        return 1
    with open(manifest_path, encoding="utf-8") as f:
        manifest = json.load(f)
    with open(sheet_path, "rb") as f:
        image_b64 = base64.b64encode(f.read()).decode("ascii")

    caption = ""
    caption_file = args.caption_file or os.path.join(SUMMARIES, f"sheet_caption_{date}.md")
    if os.path.exists(caption_file):
        with open(caption_file, encoding="utf-8") as f:
            caption = f.read().strip()

    row = {
        "day": date,
        "caption_text": caption,
        "photo_count": manifest.get("total"),
        "usable_count": manifest.get("usable"),
        "cluster_count": manifest.get("clusters"),
        "sheet_size_kb": int(round(manifest.get("sheet_size_kb") or 0)),
        "image_base64": image_b64,
    }
    print(f"{date}: photos={row['photo_count']} usable={row['usable_count']} clusters={row['cluster_count']} "
          f"sheet={row['sheet_size_kb']}KB base64={len(image_b64)} chars caption={len(caption)} chars")
    if args.dry_run:
        print("dry-run：未发送")
        return 0

    env = load_env(ENV_FILE)
    if not env or not env.get("SUPABASE_URL"):
        print(f"缺少 Supabase 凭证：{ENV_FILE}", file=sys.stderr)
        return 1
    key = env.get("SUPABASE_SERVICE_ROLE_KEY") or env.get("SUPABASE_PUBLISHABLE_KEY")
    url = env["SUPABASE_URL"].rstrip("/") + "/rest/v1/daily_sheets?on_conflict=day"
    req = urllib.request.Request(
        url,
        data=json.dumps(row).encode("utf-8"),
        method="POST",
        headers={
            "apikey": key,
            "Authorization": f"Bearer {key}",
            "Content-Type": "application/json",
            "Prefer": "resolution=merge-duplicates,return=minimal",
        },
    )
    try:
        with urllib.request.urlopen(req, timeout=120) as resp:
            print(f"写入成功：HTTP {resp.status}")
    except urllib.error.HTTPError as e:
        body = e.read().decode("utf-8", "replace")[:500]
        print(f"写入失败：HTTP {e.code} {body}", file=sys.stderr)
        return 1
    except Exception as e:
        print(f"写入失败：{e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
