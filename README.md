# Muse Daily Recorder v1.8.2 (S3 upload + event markers + daily summary)

English | [中文](README.zh-CN.md) | [日本語](README.ja.md) | [한국어](README.ko.md) | [Español](README.es.md) | [Français](README.fr.md) | [Deutsch](README.de.md) | [Português](README.pt.md) | [Русский](README.ru.md)

Muse itself cannot listen to the microphone continuously, so this setup has three parts:

1. **Android app** (`MuseRecorder.apk`): a foreground-service recorder that **cuts a new segment every 60 minutes** (.m4a, ~21 MB per segment) and uploads each finished segment automatically to your own **S3-compatible object storage**. Failed uploads are queued and retried; the app shows a **pending-upload list**.
2. **Event markers**: mark important moments while recording (see below); markers are uploaded to the bucket together with the audio.
3. **Daily summary** (on Muse's side): `receiver/fetch_day_from_s3.py` pulls the previous day's audio and markers from the bucket → transcribes → Muse turns it into a daily report.

Since v1.2 the app is **S3-only** (v1.0's self-hosted receiver and v1.1's Supabase direct upload were removed; the old receiver scripts are still kept in `receiver/` for reference).

## 1. Prepare an S3 bucket (Cloudflare R2 example)

1. Sign up at cloudflare.com and enable R2 (free tier: 10 GB, downloads free; activation may ask for a card, but nothing is charged within the free quota). Create a bucket, e.g. `recordings`.
2. Under "Manage R2 API Tokens", **create a token with read & write access to only that bucket** (Object Read & Write, scoped to the bucket). Note the Access Key ID, Secret Access Key, and your Account ID (the endpoint is `https://<AccountID>.r2.cloudflarestorage.com`).
3. In the app, pick the **Cloudflare R2** preset, replace `<AccountID>` in the endpoint with your real ID, and fill in the bucket name and the two keys, then save.

Other providers work the same way (all have presets in the app): Backblaze B2, Alibaba Cloud OSS (use S3-compatible mode + account AccessKey; create the bucket in the console), Tencent Cloud COS (bucket names carry an `-APPID` suffix; use SecretId/SecretKey), AWS S3, Qiniu Kodo, or any S3-compatible service via "Custom". Alibaba/Tencent/AWS/Qiniu need "virtual-host style" checked; R2/B2 do not.

> Security note: the Secret Key lives only inside the phone app — never paste it into a chat or anywhere public. Scoping the token to a single bucket limits the damage if it ever leaks.

## 2. Install and daily use

1. Send `MuseRecorder.apk` to the phone and install it (a sideloaded personal app, so allow "unknown sources" once). Installing over an older version keeps your settings.
2. Open the app → tap "⚙️ Config" and fill in S3, then save (saving triggers an upload right away) → tap "Allow background running (disable battery optimization)" → tap the red round button to start recording (it turns into a square while recording, with a live waveform and the minutes remaining in the current segment below), and grant microphone/notification permissions. On Samsung phones, also set the app's battery to "Unrestricted".
3. **Uploads are fully automatic**: the pending list shows each segment's status, live progress appears while uploading, and the result stays on screen afterwards — with the server's error message on failure. Segments that fail (e.g. offline) stay queued and retry on their own. A manual "retry upload now" button only appears after a settings change or after 30+ minutes of failed automatic retries. S3 and recording settings are collapsed by default; tap "⚙️ Config" to expand them.
4. **Language**: the picker at the top of Settings offers system default plus English, Chinese, Japanese, Korean, Spanish, French, German, Portuguese and Russian, and applies immediately. The app name is always "Muse Daily Recorder" in every language.
5. The blue ⓘ button at the top right opens a built-in help page (recording, upload, markers, settings, FAQ, privacy).

## 3. Event markers (highlighting things for the daily report)

- **Conversation switch**: turn it on when an important conversation starts, off when it ends.
- **Meeting switch**: turn it on before a meeting, off when it adjourns.
- **📌 Self-note button**: press and hold whenever something occurs to you — an idea, a todo, a thought. While recording it just marks the moment; while idle it records a standalone voice note.
- Markers are stored per day in the bucket at `markers/events-<date>.jsonl`; the daily report splits conversations/meetings by markers and pays special attention to content near self-notes. Switch states are remembered, so closing and reopening the app never scrambles them.

## 4. Daily summary (on Muse's side)

```bash
# Env vars: S3_ENDPOINT / S3_BUCKET / S3_ACCESS_KEY / S3_SECRET_KEY / S3_REGION / S3_STYLE (path|virtual)
# Optional: S3_DELETE_AFTER=1 → delete the cloud originals after pulling (saves cloud space; transcripts and reports stay on Muse's side)
python3 receiver/fetch_day_from_s3.py --date 2026-10-23
python3 receiver/summarize_day.py   --date 2026-10-23
```

Transcription, two options: set `OPENAI_API_KEY` (Whisper API), or `pip install faster-whisper` on Muse's machine (local, free). Then tell Muse a report time (e.g. 7:30 every morning) and it will set up a scheduled job that runs automatically and posts to the chat.

## 5. Reality check

- **Battery**: all-day recording uses roughly 3–6% per hour; record while charging overnight if you can. "Wi-Fi only upload" is available.
- **Storage**: about 500 MB per day. Local copies are deleted after a successful upload by default; for the cloud side, use `S3_DELETE_AFTER=1` or a bucket lifecycle rule that only keeps the last few days.
- **Stability**: Android restricts background recording strictly; the persistent status-bar notification is a system requirement (and also your proof that it is recording). A quick glance at "recorded for X minutes" in the app morning and night is enough.
- **Compliance**: recording conversations with other people requires their consent in many places — only use it where it is legal and where the other side knows.

## 6. Layout

- `app-project/`: the complete app source (Java + Android framework APIs; the S3 signature is a pure-Java implementation, verified against AWS's official SigV4 test vectors).
- `receiver/fetch_day_from_s3.py`: pulls audio + markers from the S3 bucket (Python standard library only; its signature implementation passed the same official test vectors).
- `receiver/summarize_day.py`: transcription + report draft; `receiver/server.py` and `fetch_day_from_supabase.py` are archives of the old approach.
- `build-apk.sh`: builds the APK directly with the Android SDK build-tools.
- `.github/workflows/build.yml`: CI builds on every push to master and publishes two releases holding the same APK — a rolling `latest` release (always the newest build) and a versioned release per version (tag `vX.Y.Z`, kept forever). Download the newest build any time at https://github.com/skyvense/muse-recorder/releases/tag/latest
- `muse-recorder.keystore` + `keystore-info.txt`: the signing key — **keep them safe**. Upgrades must be signed with the same key, or they cannot install over the old version. Encrypted copies live in `vault/` (AES-256-CBC + PBKDF2).
