# Muse Daily Recorder

English · [简体中文](README.zh-CN.md) · [日本語](README.ja.md) · [한국어](README.ko.md) · [Español](README.es.md) · [Français](README.fr.md) · [Deutsch](README.de.md) · [Português](README.pt.md) · [Русский](README.ru.md)

**Current version: v1.14.0**

A companion Android app for round-the-clock daily recording. Your phone is always with you, so it replaces a voice recorder pen: recordings are auto-segmented, backed up to your own cloud storage, and — with event markers, transcripts and a daily summary — turned into something you can actually search and review. No cable transfers, no lost files.

Muse itself cannot listen to a microphone continuously, so the system has three parts:

1. **Android app** (`MuseRecorder.apk`): a foreground-service recorder that cuts a new segment every 60 minutes and uploads each finished segment to your own S3-compatible storage. Transcription and summaries run on the Muse side.
2. **Event markers**: mark important moments while recording; markers are uploaded to the bucket together with the audio.
3. **Receiver + daily summary**: scripts in `receiver/` pull a day's audio and markers from the bucket, transcribe them, and store per-segment transcripts, analyses and a daily summary in Supabase.

## Features

- **Segmented recording + automatic upload** — a new .m4a segment every 60 minutes (~21 MB each), uploaded automatically to S3-compatible object storage (Cloudflare R2, Backblaze B2, Alibaba Cloud OSS, Tencent Cloud COS, AWS S3, Qiniu Kodo, or any custom endpoint; presets included). Failed uploads queue and retry on their own; a pending-upload list shows what is still waiting.
- **Event markers** — a Conversation switch and a Meeting switch bracket the moments that matter; the daily report splits content by them.
- **Press-and-hold self notes** — hold the button whenever something occurs to you; while recording it marks the moment, while idle it records a standalone voice note.
- **Auto snapshot (opt-in)** — raise the phone from flat to upright and it quietly takes a photo (back camera, front camera, or both). While recording is running, snapshots also work with the screen off. Off by default; the camera choice applies immediately.
- **Cloud tab** — browse your bucket by date: a storage-usage card (against the 10 GB R2 free tier), per-day photo/audio counts, a photo thumbnail grid, and tap-to-play audio.
- **Reports tab** — daily summaries from Supabase, and for each day a "daily activity sheet": one ~1 MB collage of the day's clearest snapshots, grouped by activity period, with a short caption per period.
- **Dark mode** — follow system, always light, or always dark (Settings → Language & system).
- **9-language UI** — English, Simplified Chinese, Japanese, Korean, Spanish, French, German, Portuguese, Russian; switching applies immediately. The app name is always "Muse Daily Recorder".

## Design

Design v2 "Cloud White" — clean cards, one blue accent, bottom tab bar:

![Design v2 light](docs/images/design-v2-light.png)

![Design v2 dark](docs/images/design-v2-dark.png)

Light and dark variants of the same design. Note: the Record tab ships in the classic compact layout shown here (record button and waveform on one row, fixed footer cards) — an earlier tall-card redesign was reverted in v1.14.0 because it took too much vertical space.

## Getting started

### 1. Prepare an S3 bucket (Cloudflare R2 example)

1. Sign up at cloudflare.com and enable R2 (free tier: 10 GB, downloads free; activation may ask for a card, but nothing is charged within the free quota). Create a bucket, e.g. `recordings`.
2. Under "Manage R2 API Tokens", **create a token with read & write access to only that bucket** (Object Read & Write, scoped to the bucket). Note the Access Key ID, Secret Access Key, and your Account ID (the endpoint is `https://<AccountID>.r2.cloudflarestorage.com`).
3. In the app, pick the **Cloudflare R2** preset, replace `<AccountID>` in the endpoint with your real ID, and fill in the bucket name and the two keys, then save.

Other providers work the same way (all have presets in the app). Alibaba/Tencent/AWS/Qiniu need "virtual-host style" checked; R2/B2 do not. Tencent bucket names carry an `-APPID` suffix and use SecretId/SecretKey.

### 2. Install and daily use

1. Download the APK from [Releases](https://github.com/skyvense/muse-recorder/releases) (the rolling `latest` release always holds the newest build; versioned releases are kept forever), send it to the phone and install it (a sideloaded personal app, so allow "unknown sources" once). Installing over an older version keeps your settings.
2. Open the app → ⚙️ Settings: fill in S3 and save (saving triggers an upload right away) → allow background running (disable battery optimization; on Samsung phones also set the app's battery to "Unrestricted") → back on the Record tab, tap the red round button to start (it becomes a square while recording, with a live waveform and the current segment's progress).
3. **Uploads are fully automatic**: the pending list shows each segment's status, live progress appears while uploading, and the result stays on screen afterwards — with the server's error message on failure. A manual "retry upload now" button only appears after a settings change or after 30+ minutes of failed automatic retries.
4. The ⓘ button at the top right opens a built-in help page (recording, upload, markers, settings, FAQ, privacy).

Markers are stored per day in the bucket at `markers/events-<date>.jsonl`. Switch states are remembered, so closing and reopening the app never scrambles them.

## Daily summary pipeline (Muse side)

```bash
# Env vars: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Optional: S3_DELETE_AFTER=1 → delete the cloud originals after pulling (transcripts and reports stay)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcription, two options: set `OPENAI_API_KEY` (Whisper API), or `pip install faster-whisper` and transcribe locally for free. Tell Muse a report time (e.g. 7:30 every morning) and a scheduled job can run the pipeline automatically and post the report to the chat.

## Privacy & security

- Audio goes **only to your own bucket** — there is no third-party server in the loop.
- Transcription can run **entirely locally** on your machine (faster-whisper); nothing has to leave your hardware except the storage you chose.
- **No credentials live in this repository.** The S3 secret key exists only inside the phone app's settings; the Supabase keys used by the receiver scripts live in local env files outside the repo. Scope storage tokens to a single bucket so a leak stays contained.
- Recording other people requires their consent in many places — only record where it is legal and where the other side knows.

## Reality check

- **Battery**: all-day recording uses roughly 3–6% per hour; record while charging overnight if you can. "Wi-Fi only upload" is available.
- **Storage**: about 500 MB per day. Local copies are deleted after a successful upload by default; for the cloud side, use `S3_DELETE_AFTER=1` or a bucket lifecycle rule that only keeps the last few days.
- **Stability**: Android restricts background recording strictly; the persistent status-bar notification is a system requirement (and also your proof that it is recording).

## Build & release

- `build-apk.sh` builds the APK directly with the Android SDK build-tools (no Gradle required for the release build).
- Every push to `master` triggers GitHub Actions, which builds a **signed** APK and publishes two releases holding the same file: a rolling `latest` release and a versioned `vX.Y.Z` release. Install from [Releases](https://github.com/skyvense/muse-recorder/releases).
- `muse-recorder.keystore` + `keystore-info.txt` are the signing key — **keep them safe**. Upgrades must be signed with the same key or they cannot install over the old version. Encrypted copies live in `vault/` (AES-256-CBC + PBKDF2).

## Repository layout

- `app-project/`: the complete app source (Java + Android framework APIs; the S3 signing is a pure-Java SigV4 implementation, verified against AWS's official test vectors).
- `receiver/`: the Muse-side pipeline — `fetch_day_from_s3.py` (pull audio + markers + snapshots from the bucket; Python standard library only, same SigV4 test vectors), `summarize_day.py` (transcription + report draft), Supabase writers, and the snapshot-sheet builders. `receiver/server.py` and `fetch_day_from_supabase.py` are archives of the old v1.0/v1.1 approaches.
- `docs/images/`: the design mockups used above.
- `README.<lang>.md`: this file in the other 8 languages.

Since v1.2 the app is **S3-only** (the v1.0 self-hosted receiver and v1.1 Supabase direct upload were removed from the app; the scripts remain in `receiver/` for reference).
