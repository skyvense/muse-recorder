#!/usr/bin/env python3
"""Sample auto-snapshot photos in a time window and build a contact sheet.

The 4-hour report worker cannot view hundreds of snapshots. This script:
  - collects snap_<date>_<HH-MM-SS>_<back|front>.jpg in [start, end)
  - scores each photo (mean brightness, blur via Laplacian variance)
  - drops near-black / blank frames from sampling (still counted in stats)
  - picks evenly spaced samples, at most --max, preferring clear frames and
    including front-camera shots when present
  - writes a labelled contact-sheet JPG (EXIF orientation applied; front
    frames taken by app v1.15.0 or earlier carry the back camera's EXIF
    value (6 instead of 8 in portrait) and get an extra 180-degree turn so
    they display upright; frames from v1.16.0 onward already carry the
    correct EXIF and are left alone) plus a JSON manifest with stats.

Usage:
  snapshot_sample.py --start "2026-11-07 10:00" --end "2026-11-07 14:00" \
      [--max 12] [--snapshots-root ~/workspace/muse-recordings/snapshots] \
      [--out-dir ~/workspace/muse-recordings/snapshots/analysis]

Output: prints the manifest JSON path, the sheet path, and the stats JSON.
"""
import argparse
import json
import math
import os
import re
import sys
from datetime import datetime, timedelta

import numpy as np
from PIL import Image, ImageDraw, ImageOps

NAME_RE = re.compile(r"snap_(\d{4}-\d{2}-\d{2})_(\d{2})-(\d{2})-(\d{2})_(back|front)\.jpg$")


def parse_args():
    p = argparse.ArgumentParser()
    p.add_argument("--start", required=True, help="window start 'YYYY-MM-DD HH:MM'")
    p.add_argument("--end", required=True, help="window end 'YYYY-MM-DD HH:MM' (exclusive)")
    p.add_argument("--max", type=int, default=12, help="max sampled photos (default 12)")
    p.add_argument("--snapshots-root", default=os.path.expanduser("~/workspace/muse-recordings/snapshots"))
    p.add_argument("--out-dir", default=None)
    return p.parse_args()


def collect(root, start, end):
    items = []
    day = start.date()
    while day <= end.date():
        folder = os.path.join(root, day.isoformat())
        if os.path.isdir(folder):
            for name in sorted(os.listdir(folder)):
                m = NAME_RE.match(name)
                if not m:
                    continue
                d, hh, mm, ss, cam = m.groups()
                ts = datetime.fromisoformat(f"{d}T{hh}:{mm}:{ss}")
                if start <= ts < end:
                    items.append({"path": os.path.join(folder, name), "ts": ts, "camera": cam, "name": name})
        day += timedelta(days=1)
    items.sort(key=lambda x: x["ts"])
    return items


def score(path):
    """Return (brightness 0-255, blur variance). Low-cost: downscale first."""
    try:
        with Image.open(path) as im:
            gray = im.convert("L")
            gray.thumbnail((320, 320))
            a = np.asarray(gray, dtype=np.float64)
        brightness = float(a.mean())
        # Laplacian variance on the small gray image as a blur proxy.
        lap = (
            -4.0 * a[1:-1, 1:-1]
            + a[:-2, 1:-1]
            + a[2:, 1:-1]
            + a[1:-1, :-2]
            + a[1:-1, 2:]
        )
        blur = float(lap.var()) if lap.size else 0.0
        grad = float((np.abs(np.diff(a, axis=1)).mean() + np.abs(np.diff(a, axis=0)).mean()) / 2)
        return brightness, blur, grad
    except Exception:
        return 0.0, 0.0, 0.0


def is_blank(brightness, blur, grad=0.0):
    # Near-black (lens covered / pocket / night), or out-of-focus / covered
    # frames: real scenes measured on 2026-11-07 snapshots have grad >= ~6
    # and lapvar in the hundreds, while covered/blurry frames sit at
    # grad < 2 and lapvar < 60.
    return brightness < 12 or grad < 2.0 or blur < 60


def pick(items, scored, limit):
    """Evenly spaced picks among usable frames; ensure front shots included."""
    usable = [it for it in items if not scored[it["name"]]["blank"]]
    if not usable:
        return []
    if len(usable) <= limit:
        return usable
    fronts = [it for it in usable if it["camera"] == "front"]
    chosen = []
    # Reserve up to 1/4 of the slots for front-camera frames, spread out.
    n_front = min(len(fronts), max(1, limit // 4)) if fronts else 0
    if n_front:
        step = len(fronts) / n_front
        chosen = [fronts[min(len(fronts) - 1, int(i * step))] for i in range(n_front)]
    rest = [it for it in usable if it not in chosen]
    n_rest = limit - len(chosen)
    if n_rest > 0 and rest:
        step = len(rest) / n_rest
        chosen += [rest[min(len(rest) - 1, int(i * step))] for i in range(n_rest)]
    chosen.sort(key=lambda x: x["ts"])
    return chosen


def build_sheet(chosen, out_path):
    cols = 3
    cell_w, cell_h, label_h = 480, 380, 26
    rows = math.ceil(len(chosen) / cols)
    sheet = Image.new("RGB", (cols * cell_w, rows * (cell_h + label_h)), "white")
    draw = ImageDraw.Draw(sheet)
    for i, it in enumerate(chosen):
        try:
            with Image.open(it["path"]) as im:
                exif_orientation = im.getexif().get(274)
                im = ImageOps.exif_transpose(im)
                if it["camera"] == "front" and exif_orientation == 6:
                    # Only pre-fix (v1.15.0 and earlier) front frames: the
                    # app wrote the back camera's EXIF value (6) instead of
                    # 8, so the content is 180 degrees off after transpose.
                    # v1.16.0 writes the correct EXIF (8 in portrait), so
                    # those frames must NOT be turned again.
                    im = im.rotate(180)
                im = im.convert("RGB")
                im.thumbnail((cell_w, cell_h))
                x = (i % cols) * cell_w + (cell_w - im.width) // 2
                y = (i // cols) * (cell_h + label_h) + (cell_h - im.height) // 2
                sheet.paste(im, (x, y))
        except Exception as e:
            draw.text(((i % cols) * cell_w + 8, (i // cols) * (cell_h + label_h) + 8), f"load error: {e}", fill="red")
        lx = (i % cols) * cell_w + 8
        ly = (i // cols) * (cell_h + label_h) + cell_h + 4
        draw.text((lx, ly), f"#{i + 1} {it['ts'].strftime('%H:%M:%S')} {it['camera']}", fill="black")
    sheet.save(out_path, quality=82)
    return out_path


def main():
    args = parse_args()
    start = datetime.fromisoformat(args.start)
    end = datetime.fromisoformat(args.end)
    out_dir = args.out_dir or os.path.join(args.snapshots_root, "analysis")
    os.makedirs(out_dir, exist_ok=True)

    items = collect(args.snapshots_root, start, end)
    scored = {}
    for it in items:
        b, v, g = score(it["path"])
        scored[it["name"]] = {"brightness": round(b, 1), "blur": round(v, 1), "grad": round(g, 2), "blank": is_blank(b, v, g)}

    chosen = pick(items, scored, args.max)
    tag = f"{start.strftime('%Y-%m-%d_%H%M')}_{end.strftime('%Y-%m-%d_%H%M')}"
    sheet_path = None
    if chosen:
        sheet_path = build_sheet(chosen, os.path.join(out_dir, f"sheet_{tag}.jpg"))

    stats = {
        "window": {"start": args.start, "end": args.end},
        "total": len(items),
        "back": sum(1 for it in items if it["camera"] == "back"),
        "front": sum(1 for it in items if it["camera"] == "front"),
        "blank_or_dark": sum(1 for it in items if scored[it["name"]]["blank"]),
        "unusable": sum(1 for it in items if scored[it["name"]]["blank"]),
        "sampled": [
            {"time": it["ts"].strftime("%Y-%m-%d %H:%M:%S"), "camera": it["camera"], "file": it["name"]}
            for it in chosen
        ],
        "sheet": sheet_path,
    }
    manifest_path = os.path.join(out_dir, f"manifest_{tag}.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
