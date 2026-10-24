#!/usr/bin/env python3
"""
Muse Recorder - daily transcription (+ optional draft summary).

Collects one day's segments from inbox/<date>/, transcribes each, and writes:
    summaries/transcript_<date>.md    full transcript, one section per segment
    summaries/summary_<date>.md       draft summary (only when an OpenAI key
                                      is configured; otherwise Muse writes
                                      the final summary from the transcript)

Transcription engines, tried in order:
  1. OpenAI audio API when OPENAI_API_KEY is set (model from
     OPENAI_TRANSCRIBE_MODEL, default "whisper-1"). Files must be < 25 MB;
     the app's 48 kbps hourly segments are ~21 MB.
  2. Local faster-whisper when importable (model from WHISPER_MODEL,
     default "small"; pip install faster-whisper).
  3. Otherwise segments are listed and marked UNTRANSCRIBED so the gap is
     explicit instead of silently producing an empty summary.

Usage:
    python3 summarize_day.py [--date YYYY-MM-DD] [--base ~/workspace/muse-recordings]
Default date is yesterday (local time).
"""
import argparse
import json
import os
import sys
import urllib.request
import uuid
from datetime import datetime, timedelta
from pathlib import Path


def transcribe_openai(path: Path) -> str:
    key = os.environ["OPENAI_API_KEY"]
    model = os.environ.get("OPENAI_TRANSCRIBE_MODEL", "whisper-1")
    boundary = "----MuseBoundary" + uuid.uuid4().hex
    body = b""
    def field(name, value):
        nonlocal body
        body += (f"--{boundary}\r\nContent-Disposition: form-data; "
                 f"name=\"{name}\"\r\n\r\n{value}\r\n").encode()
    field("model", model)
    body += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"file\"; "
             f"filename=\"{path.name}\"\r\nContent-Type: audio/mp4\r\n\r\n").encode()
    body += path.read_bytes()
    body += f"\r\n--{boundary}--\r\n".encode()
    req = urllib.request.Request(
        "https://api.openai.com/v1/audio/transcriptions",
        data=body, method="POST",
        headers={"Authorization": f"Bearer {key}",
                 "Content-Type": f"multipart/form-data; boundary={boundary}"})
    with urllib.request.urlopen(req, timeout=900) as resp:
        return json.loads(resp.read().decode())["text"]


def transcribe_local(path: Path, cache: dict) -> str:
    from faster_whisper import WhisperModel
    if "model" not in cache:
        cache["model"] = WhisperModel(os.environ.get("WHISPER_MODEL", "small"),
                                       device="cpu", compute_type="int8")
    segments, _info = cache["model"].transcribe(
        str(path), language="zh", vad_filter=True,
        vad_parameters={"min_silence_duration_ms": 800})
    return " ".join(s.text.strip() for s in segments)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--date", default=None)
    ap.add_argument("--base", default=os.path.expanduser("~/workspace/muse-recordings"))
    args = ap.parse_args()

    date = args.date or (datetime.now() - timedelta(days=1)).strftime("%Y-%m-%d")
    base = Path(args.base)
    day_dir = base / "inbox" / date
    out_dir = base / "summaries"
    out_dir.mkdir(parents=True, exist_ok=True)

    # Skip sub-4KB header-only stubs (failed recorder starts); they hold no audio.
    files = sorted(f for f in day_dir.glob("*.m4a") if f.stat().st_size >= 4096) if day_dir.exists() else []
    if not files:
        print(f"NO_FILES for {date} in {day_dir}")
        return 0

    use_openai = bool(os.environ.get("OPENAI_API_KEY"))
    local_ok = False
    if not use_openai:
        try:
            import faster_whisper  # noqa: F401
            local_ok = True
        except Exception:
            local_ok = False

    cache: dict = {}
    # Per-file transcript cache: re-runs (e.g. hourly during testing) only
    # transcribe segments that have no cached text yet.
    seg_cache_dir = out_dir / "segments" / date
    seg_cache_dir.mkdir(parents=True, exist_ok=True)
    sections, total_bytes = [], 0
    for f in files:
        total_bytes += f.stat().st_size
        start = f.name.replace("rec_", "").replace(".m4a", "")
        cache_file = seg_cache_dir / (f.stem + ".txt")
        if cache_file.exists() and cache_file.stat().st_size > 0:
            text = cache_file.read_text(encoding="utf-8")
        else:
            try:
                if use_openai:
                    text = transcribe_openai(f)
                elif local_ok:
                    text = transcribe_local(f, cache)
                else:
                    text = "[UNTRANSCRIBED: 未配置转写引擎 — 设置 OPENAI_API_KEY 或 pip install faster-whisper]"
            except Exception as e:  # keep going; one bad segment must not lose the day
                text = f"[TRANSCRIPTION_FAILED: {e}]"
            if not text.startswith("[TRANSCRIPTION_FAILED") and not text.startswith("[UNTRANSCRIBED"):
                cache_file.write_text(text, encoding="utf-8")
        sections.append(f"## {start}\n\n{text.strip()}\n")

    # Event markers (conversation/meeting boundaries, self-notes) structure
    # the day; include them at the top of the transcript when present.
    markers_text = ""
    markers_file = base / "markers" / f"events-{date}.jsonl"
    if markers_file.exists():
        labels = {"conversation_start": "对话开始", "conversation_end": "对话结束",
                  "meeting_start": "会议开始", "meeting_end": "会议结束",
                  "self_note": "自我记录 ⭐",
                  "self_note_start": "自述开始 🎙", "self_note_end": "自述结束"}
        lines = []
        for raw in markers_file.read_text(encoding="utf-8").splitlines():
            try:
                ev = json.loads(raw)
                lines.append(f"- {ev.get('ts', '')} · {labels.get(ev.get('type', ''), ev.get('type', ''))}")
            except Exception:
                continue
        if lines:
            markers_text = "## 事件标记\n\n" + "\n".join(lines) + "\n\n"

    transcript_path = out_dir / f"transcript_{date}.md"
    transcript_path.write_text(
        f"# {date} 录音转写（{len(files)} 段，合计 {total_bytes/1_000_000:.1f} MB）\n\n"
        + markers_text
        + "\n".join(sections), encoding="utf-8")
    print(f"WROTE {transcript_path}")

    if use_openai:
        # Draft a summary with the chat API; Muse can refine it afterwards.
        try:
            key = os.environ["OPENAI_API_KEY"]
            model = os.environ.get("OPENAI_SUMMARY_MODEL", "gpt-4o-mini")
            transcript = transcript_path.read_text(encoding="utf-8")[:120_000]
            payload = json.dumps({
                "model": model,
                "messages": [
                    {"role": "system", "content":
                     "你负责把一天的录音转写整理成中文日报：1) 一句话总览；"
                     "2) 按时间顺序的关键事件/对话要点；3) 提到的待办、承诺和决定；"
                     "4) 值得注意的灵感或情绪变化。转写稿开头的「事件标记」是用户"
                     "手动标记的对话/会议起止和自述时刻，请据此切分；「自述开始/结束」"
                     "之间（或 note_ 开头的文件）是用户对着麦克风的自述，请尽量完整"
                     "保留其要点。没有内容的部分直接省略，不要编造。"},
                    {"role": "user", "content": transcript},
                ]}).encode()
            req = urllib.request.Request(
                "https://api.openai.com/v1/chat/completions", data=payload,
                method="POST",
                headers={"Authorization": f"Bearer {key}",
                         "Content-Type": "application/json"})
            with urllib.request.urlopen(req, timeout=600) as resp:
                summary = json.loads(resp.read().decode())["choices"][0]["message"]["content"]
            sp = out_dir / f"summary_{date}.md"
            sp.write_text(f"# {date} 录音日报（草稿）\n\n{summary}\n", encoding="utf-8")
            print(f"WROTE {sp}")
        except Exception as e:
            print(f"SUMMARY_FAILED: {e}", file=sys.stderr)
    else:
        print("SUMMARY_SKIPPED: no OPENAI_API_KEY; Muse should summarize "
              "from the transcript file.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
