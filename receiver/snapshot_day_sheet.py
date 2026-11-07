#!/usr/bin/env python3
"""Build ONE daily activity thumbnail sheet (~1 MB) from a day's snapshots.

For a given date, take all auto-snapshots under snapshots/<date>/, score and
filter out unusable frames (dark / covered / out-of-focus, same thresholds as
snapshot_sample.py), group the usable frames into activity clusters (a new
cluster starts after a >15 min gap), pick the clearest few frames per cluster
(spread across the cluster, front camera included when present), and lay them
out on a single labelled contact sheet. JPEG quality is tuned so the final
file lands near --target-kb (default ~1024 KB).

Usage:
  snapshot_day_sheet.py --date 2026-11-06 [--target-kb 1024] [--max 24]

Outputs (under snapshots/analysis/):
  daily_sheet_<date>.jpg   the sheet
  daily_manifest_<date>.json  clusters + chosen files + stats
Prints the manifest JSON to stdout.
"""
import argparse
import io
import json
import math
import os
import sys
from datetime import datetime, timedelta

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from snapshot_sample import NAME_RE, is_blank, score  # noqa: E402

from PIL import Image, ImageDraw, ImageOps  # noqa: E402

GAP_MIN = 15
PER_CLUSTER = 4


def collect_day(root, date_str):
    folder = os.path.join(root, date_str)
    items = []
    if os.path.isdir(folder):
        for name in sorted(os.listdir(folder)):
            m = NAME_RE.match(name)
            if not m:
                continue
            d, hh, mm, ss, cam = m.groups()
            ts = datetime.fromisoformat(f"{d}T{hh}:{mm}:{ss}")
            items.append({"path": os.path.join(folder, name), "ts": ts, "camera": cam, "name": name})
    items.sort(key=lambda x: x["ts"])
    return items


def cluster(items):
    clusters = []
    for it in items:
        if clusters and (it["ts"] - clusters[-1][-1]["ts"]) <= timedelta(minutes=GAP_MIN):
            clusters[-1].append(it)
        else:
            clusters.append([it])
    return clusters


def pick_from_cluster(frames, scored, limit):
    """Clearest frames, spread in time: rank by sharpness, then enforce spacing."""
    ranked = sorted(frames, key=lambda it: scored[it["name"]]["quality"], reverse=True)
    chosen = []
    for it in ranked:
        if len(chosen) >= limit:
            break
        if all(abs((it["ts"] - c["ts"]).total_seconds()) >= 60 for c in chosen):
            chosen.append(it)
    if len(chosen) < min(limit, len(frames)):  # relax spacing if needed
        for it in ranked:
            if len(chosen) >= limit:
                break
            if it not in chosen:
                chosen.append(it)
    # Always prefer including one front shot per cluster when available.
    fronts = [it for it in frames if it["camera"] == "front"]
    if fronts and not any(c["camera"] == "front" for c in chosen) and chosen:
        best_front = max(fronts, key=lambda it: scored[it["name"]]["quality"])
        chosen[-1] = best_front
    chosen.sort(key=lambda x: x["ts"])
    return chosen


def build_sheet(chosen, date_str, stats, target_kb):
    cols = 4
    cell_w, cell_h, label_h, header_h = 620, 465, 26, 46
    rows = math.ceil(len(chosen) / cols)
    W, H = cols * cell_w, header_h + rows * (cell_h + label_h)
    sheet = Image.new("RGB", (W, H), "white")
    draw = ImageDraw.Draw(sheet)
    draw.text((10, 12), f"{date_str}  activities: {stats['clusters']}  photos: {stats['total']}  usable: {stats['usable']}", fill="black")
    for i, it in enumerate(chosen):
        try:
            with Image.open(it["path"]) as im:
                im = ImageOps.exif_transpose(im)
                if it["camera"] == "front":
                    im = im.rotate(180)  # front EXIF bug workaround for display
                im = im.convert("RGB")
                im.thumbnail((cell_w, cell_h))
                x = (i % cols) * cell_w + (cell_w - im.width) // 2
                y = header_h + (i // cols) * (cell_h + label_h) + (cell_h - im.height) // 2
                sheet.paste(im, (x, y))
        except Exception as e:
            draw.text(((i % cols) * cell_w + 8, header_h + (i // cols) * (cell_h + label_h) + 8), f"load error: {e}", fill="red")
        lx = (i % cols) * cell_w + 8
        ly = header_h + (i // cols) * (cell_h + label_h) + cell_h + 3
        draw.text((lx, ly), f"#{i + 1} {it['ts'].strftime('%H:%M:%S')} {it['camera']}", fill="black")

    # Tune JPEG quality toward the target size.
    best = None
    for q in (95, 92, 88, 84, 80, 76, 72, 68, 64, 60, 55, 50):
        buf = io.BytesIO()
        sheet.save(buf, format="JPEG", quality=q)
        size_kb = buf.tell() / 1024
        if best is None or abs(size_kb - target_kb) < abs(best[1] - target_kb):
            best = (q, size_kb, buf.getvalue())
        if size_kb <= target_kb:
            break
    return best  # (quality, size_kb, bytes)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--date", required=True)
    p.add_argument("--target-kb", type=int, default=1024)
    p.add_argument("--max", type=int, default=28)
    p.add_argument("--snapshots-root", default=os.path.expanduser("~/workspace/muse-recordings/snapshots"))
    args = p.parse_args()

    out_dir = os.path.join(args.snapshots_root, "analysis")
    os.makedirs(out_dir, exist_ok=True)

    items = collect_day(args.snapshots_root, args.date)
    scored = {}
    for it in items:
        b, v, g = score(it["path"])
        scored[it["name"]] = {
            "brightness": round(b, 1),
            "blur": round(v, 1),
            "grad": round(g, 2),
            "blank": is_blank(b, v, g),
            "quality": round(g * 10 + min(v, 1000) / 50.0, 2),
        }
    usable = [it for it in items if not scored[it["name"]]["blank"]]
    clusters = cluster(usable)

    chosen = []
    cluster_meta = []
    for cl in clusters:
        picks = pick_from_cluster(cl, scored, PER_CLUSTER)
        cluster_meta.append({
            "start": cl[0]["ts"].strftime("%H:%M"),
            "end": cl[-1]["ts"].strftime("%H:%M"),
            "usable_frames": len(cl),
            "picked": [p_["name"] for p_ in picks],
        })
        chosen.extend(picks)
    chosen.sort(key=lambda x: x["ts"])
    if len(chosen) > args.max:  # trim evenly if a very busy day overflows
        step = len(chosen) / args.max
        chosen = [chosen[min(len(chosen) - 1, int(i * step))] for i in range(args.max)]

    stats = {
        "date": args.date,
        "total": len(items),
        "back": sum(1 for it in items if it["camera"] == "back"),
        "front": sum(1 for it in items if it["camera"] == "front"),
        "usable": len(usable),
        "unusable": len(items) - len(usable),
        "clusters": len(clusters),
        "cluster_list": cluster_meta,
        "sheet": None,
        "sheet_size_kb": None,
        "thumbnails": [
            {"n": i + 1, "time": it["ts"].strftime("%H:%M:%S"), "camera": it["camera"], "file": it["name"]}
            for i, it in enumerate(chosen)
        ],
    }
    if chosen:
        q, size_kb, data = build_sheet(chosen, args.date, stats, args.target_kb)
        sheet_path = os.path.join(out_dir, f"daily_sheet_{args.date}.jpg")
        with open(sheet_path, "wb") as f:
            f.write(data)
        stats["sheet"] = sheet_path
        stats["sheet_size_kb"] = round(size_kb, 1)
        stats["jpeg_quality"] = q
    manifest_path = os.path.join(out_dir, f"daily_manifest_{args.date}.json")
    with open(manifest_path, "w", encoding="utf-8") as f:
        json.dump(stats, f, ensure_ascii=False, indent=2)
    print(json.dumps(stats, ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    sys.exit(main())
