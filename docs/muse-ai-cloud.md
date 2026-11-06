# Muse AI Cloud (optional)

The default app still uploads straight to your own S3-compatible bucket. Muse AI Cloud is a second destination you turn on at build time: the phone signs in to a self-hosted shelf, stores the JWT, and uploads each finished segment and that day's markers with `Authorization: Bearer`.

Stock builds leave the flag off. Existing S3 settings, the pending-upload queue, and the release APK are unchanged until you opt in.

This repo does not contain the shelf. The client below is the contract the shelf should implement. Billing ($5/mo, 7-day trial) and “Connect Muse” / bring-your-own-model-key stay on the web. The app never sends an AI provider token.

## Turn the feature on

A non-empty `CLOUD_BASE_URL` enables the feature and prefills Settings. `MUSE_CLOUD_ENABLED=true` with an empty URL shows the form so the address can be typed on the phone. Leave both unset for an S3-only build.

The same two names are read by Gradle (`app/build.gradle` → `BuildConfig`) and by `build-apk.sh` (it writes `BuildConfig.java` because the release script does not run Gradle). First match wins:

1. Environment variable
2. `app-project/cloud.local.properties` (gitignored — copy `cloud.local.properties.example`)
3. `app-project/gradle.properties`, or Gradle `-PCLOUD_BASE_URL=…` / `-PMUSE_CLOUD_ENABLED=true` when the environment variable is unset

```bash
# Release-style APK (the CI path)
CLOUD_BASE_URL=http://HOST:8080 ./build-apk.sh

# Or a URL typed later on the phone
MUSE_CLOUD_ENABLED=true ./build-apk.sh
```

`CLOUD_BASE_URL` is the shelf's `PUBLIC_URL`, origin only, for example `http://HOST:8080`. Do not append `/api` (a trailing `/api` is stripped). Cleartext HTTP is already allowed so a LAN or Tailscale address works; prefer HTTPS when the shelf has a certificate.

There is no product flavor. Flavors would rename the `build-apk.sh` / CI output (`assembleRelease` becomes per-flavor) for a switch that is only two fields. The flag is `BuildConfig.MUSE_CLOUD_ENABLED` and `BuildConfig.CLOUD_BASE_URL`.

On the phone: open Settings, enter the URL if it is not already filled in, create an account or sign in, and leave **Upload via Muse AI Cloud** checked. The password is sent once and is not written to preferences. The JWT is stored in the app's private preferences, the same place as the S3 secret. Sign out deletes the token.

If that checkbox is off, or the build flag is off, uploads use S3 when S3 is filled in. If Cloud is checked but you are not signed in, S3 is used when it is configured; otherwise the upload line says you still need to sign in.

Wi-Fi-only and “delete local file after upload” apply to both destinations.

## What the app calls

Base: `{PUBLIC_URL}` such as `http://HOST:8080`. Every path below is under `/api/`. Redirects are not followed.

| Method | Path | Auth | Body | Success |
| --- | --- | --- | --- | --- |
| POST | `/api/auth/register` | none | `{"email","password"}` | 200 or 201, JSON token |
| POST | `/api/auth/login` | none | `{"email","password"}` | 200 or 201, JSON token |
| GET | `/api/auth/me` | `Bearer` | empty | 200 user JSON. Optional: 404 is ignored |
| PUT | `/api/days/{yyyy-MM-dd}/segments/{filename}` | `Bearer` | raw audio bytes | 2xx, or 409 if the object is already stored |
| PUT | `/api/days/{yyyy-MM-dd}/markers` | `Bearer` | raw `events-{date}.jsonl` | 2xx or 409 |
| PUT | `/api/days/{yyyy-MM-dd}/snapshots/{filename}` | `Bearer` | raw JPEG | 2xx or 409 |

Headers on every call: `Accept: application/json`, `X-Muse-Client: muse-recorder`.

Auth requests also send `Content-Type: application/json; charset=utf-8`.

Uploads send `Authorization: Bearer <jwt>`, `Content-Type` (`audio/mp4`, `application/x-ndjson`, or `image/jpeg`), and `Content-Length`. The body is the file itself, not multipart. The object key the shelf should write into its S3-compatible bucket is the same layout the S3 mode already uses:

- `{yyyy-MM-dd}/{filename}` for audio (`.m4a` segments and voice notes)
- `markers/events-{yyyy-MM-dd}.jsonl` for markers (the PUT replaces that day's log)
- `snapshot-{yyyy-MM-dd}/{filename}` for optional auto-snapshots

Re-PUT of the same segment overwrites it. The app retries the local queue after failures and treats HTTP 409 as “already stored”.

Token JSON, first string value wins: `token`, `access_token`, `accessToken`, or `jwt`, including one level down such as `{"data":{"access_token":"…"}}`.

Errors should be JSON with a string `message`, `detail`, or `error`. The text is shown on the upload line. `401` / `403` from `/api/auth/me` right after login is treated as a rejected token. `402` (trial over, subscription inactive) is shown as an upload failure and retried later; the app does not start checkout.

`GET /api/auth/me` may include `subscription_status` (or a nested `status`) of `trialing`, `active`, `past_due`, `canceled`, `inactive`, `incomplete`, `paused`, `none`, or `expired`. The value is only displayed. The app does not call billing, Connect Muse, or any model endpoint.

There is no refresh-token call. When the JWT expires, uploads start failing with the server's 401 text and the user signs in again.

## Files

- `app-project/app/src/main/java/personal/muse/recorder/MuseCloud.java` — HTTP client
- `Prefs` — `cloud_base_url`, `cloud_email`, `cloud_token`, `cloud_use`, `cloud_subscription`
- `Uploader` — cloud branch only when the flag is on, the checkbox is on, and a token is stored
- `app-project/app/src/test/java/personal/muse/recorder/MuseCloudManualTest.java` — desktop JVM check of the client against a local stand-in of this contract (not part of the APK)

```bash
javac -encoding UTF-8 -d /tmp/muse-cloud-test \
  app-project/app/src/main/java/personal/muse/recorder/S3.java \
  app-project/app/src/main/java/personal/muse/recorder/MuseCloud.java \
  app-project/app/src/test/java/personal/muse/recorder/MuseCloudManualTest.java
java -cp /tmp/muse-cloud-test personal.muse.recorder.MuseCloudManualTest
```
