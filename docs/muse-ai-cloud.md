# Muse AI Cloud (optional)

The default app still uploads straight to your own S3-compatible bucket. Muse AI Cloud is a second destination you turn on at build time: the phone signs in to a self-hosted shelf, stores the JWT, and uploads each finished segment and that day's markers with `Authorization: Bearer`.

Stock builds leave the flag off. Existing S3 settings, the pending-upload queue, and the release APK are unchanged until you opt in.

The paths below are the live Muse AI Cloud shelf (`docs/apk-integration.md` on that repo), not a guessed shape. Nginx on the shelf proxies `/api`. Billing ($5/mo, 7-day trial) and “Connect Muse” / bring-your-own-model-key stay on the web. The app never sends an AI provider token.

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

On the phone: open the Settings tab (not the Cloud tab — that tab browses an R2 bucket), enter the URL if it is not already filled in, create an account or sign in, and leave **Upload via Muse AI Cloud** checked. The password is sent once and is not written to preferences. The JWT is stored in the app's private preferences, the same place as the S3 secret. Sign out calls `POST /api/v1/auth/logout` and then deletes the local token.

If that checkbox is off, or the build flag is off, uploads use S3 when S3 is filled in. If Cloud is checked but you are not signed in, S3 is used when it is configured; otherwise the upload line says you still need to sign in.

Wi-Fi-only and “delete local file after upload” apply to both destinations.

## What the app calls

Base: `{PUBLIC_URL}` such as `http://HOST:8080`. Redirects are not followed. Do not call `/api/auth/*` or `/api/days/...` — those routes 404 on this shelf.

| Method | Path | Auth | Body | Success |
| --- | --- | --- | --- | --- |
| POST | `/api/v1/auth/register` | none | `{"email","password","client":"android"}` | 200 or 201 `{"token","user"}` |
| POST | `/api/v1/auth/login` | none | same | 200 or 201 `{"token","user"}` |
| GET | `/api/v1/me` | `Bearer` | empty | 200 user JSON. 404 is ignored. 401/403 rejects the token |
| POST | `/api/v1/auth/logout` | `Bearer` | `{}` | 2xx. Shelf increments `token_version`; the old JWT then 401s |
| POST | `/api/v1/auth/refresh` | `Bearer` | `{}` | optional. Implemented, not used on the upload path |
| PUT | `/api/v1/upload/{yyyy-MM-dd}/rec_{yyyy-MM-dd}_{HH-mm-ss}.m4a` | `Bearer` | raw audio, `Content-Type: audio/mp4` | 2xx, or 409 if already stored |
| PUT | `/api/v1/upload/{yyyy-MM-dd}/note_{yyyy-MM-dd}_{HH-mm-ss}.m4a` | `Bearer` | raw audio, `audio/mp4` | 2xx or 409 |
| PUT | `/api/v1/upload/markers/events-{yyyy-MM-dd}.jsonl` | `Bearer` | raw JSONL, `application/x-ndjson` | 2xx or 409 |
| PUT | `/api/v1/upload/snapshot-{yyyy-MM-dd}/snap_{yyyy-MM-dd}_{HH-mm-ss}_back.jpg` | `Bearer` | raw JPEG | 2xx or 409 |
| PUT | `/api/v1/upload/snapshot-{yyyy-MM-dd}/snap_{…}_front.jpg` | `Bearer` | raw JPEG | 2xx or 409 |

The upload URL is `PUT /api/v1/upload/{objectKey}` where `{objectKey}` is the same key S3 mode already writes:

- `{yyyy-MM-dd}/rec_….m4a` and `{yyyy-MM-dd}/note_….m4a`
- `markers/events-{yyyy-MM-dd}.jsonl` (the PUT replaces that day's log)
- `snapshot-{yyyy-MM-dd}/snap_…_back.jpg` or `_front.jpg`

Auth requests send `Content-Type: application/json; charset=utf-8` and `Accept: application/json`. Uploads send `Authorization: Bearer <jwt>`, the content type above, and `Content-Length`. The body is the file itself, not multipart.

Re-PUT of the same key overwrites it. The app retries the local queue after failures and treats HTTP 409 as “already stored”.

The token field is `token` (a nested `access_token` is still accepted). `GET /api/v1/me` may include `subscription_status` (or a nested `status`) of `trialing`, `active`, `past_due`, `canceled`, `inactive`, `incomplete`, `paused`, `none`, or `expired`. That value is only displayed.

`402` means uploads are closed. The upload line says so and points at billing on the web. The app does not start checkout. `401` on upload means sign in again (`/api/v1/auth/refresh` is not called automatically).

## Curl smoke

Replace `HOST` with the shelf. This environment had no shelf listening on port 8080, so these are the exact requests the client sends. A live check should show `/api/auth/register` and `/api/days/...` as **404**, and `/api/v1/upload/...` as **401** without a token.

```bash
BASE=http://HOST:8080
EMAIL=apk-smoke@example.com
PASS='smoke-pass-1'

curl -sS -D- -o /tmp/reg.json -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"client\":\"android\"}"

curl -sS -D- -o /tmp/login.json -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"$PASS\",\"client\":\"android\"}"
TOKEN=$(python3 -c 'import json;print(json.load(open("/tmp/login.json"))["token"])')

curl -sS -D- -H "Authorization: Bearer $TOKEN" "$BASE/api/v1/me"

printf 'not-a-real-m4a' > /tmp/rec.m4a
curl -sS -D- -o /tmp/put.json -X PUT \
  "$BASE/api/v1/upload/2026-11-06/rec_2026-11-06_12-00-00.m4a" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: audio/mp4' \
  --data-binary @/tmp/rec.m4a

printf '%s\n' '{"type":"note"}' > /tmp/events.jsonl
curl -sS -D- -X PUT \
  "$BASE/api/v1/upload/markers/events-2026-11-06.jsonl" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/x-ndjson' \
  --data-binary @/tmp/events.jsonl

printf 'jpeg' > /tmp/snap.jpg
curl -sS -D- -X PUT \
  "$BASE/api/v1/upload/snapshot-2026-11-06/snap_2026-11-06_12-00-00_back.jpg" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: image/jpeg' \
  --data-binary @/tmp/snap.jpg

curl -sS -D- -X POST "$BASE/api/v1/auth/refresh" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'
curl -sS -D- -X POST "$BASE/api/v1/auth/logout" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{}'

# These must 404 on the live shelf:
curl -sS -o /dev/null -w 'old-auth %{http_code}\n' -X POST "$BASE/api/auth/register" \
  -H 'Content-Type: application/json' -d '{"email":"a@b.c","password":"x"}'
curl -sS -o /dev/null -w 'old-upload %{http_code}\n' -X PUT \
  "$BASE/api/days/2026-11-06/segments/rec_2026-11-06_12-00-00.m4a" \
  -H "Authorization: Bearer $TOKEN" -H 'Content-Type: audio/mp4' --data-binary @/tmp/rec.m4a
curl -sS -o /dev/null -w 'v1-no-token %{http_code}\n' -X PUT \
  "$BASE/api/v1/upload/2026-11-06/rec_2026-11-06_12-00-00.m4a" \
  -H 'Content-Type: audio/mp4' --data-binary @/tmp/rec.m4a
```

## Files

- `app-project/app/src/main/java/personal/muse/recorder/MuseCloud.java` — HTTP client
- `Prefs` — `cloud_base_url`, `cloud_email`, `cloud_token`, `cloud_use`, `cloud_subscription`
- `Uploader` — cloud branch only when the flag is on, the checkbox is on, and a token is stored
- `app-project/app/src/test/java/personal/muse/recorder/MuseCloudManualTest.java` — desktop JVM check of the client against a local stand-in of this contract (not part of the APK)

```bash
javac -encoding UTF-8 -d /tmp/muse-cloud-test \
  app-project/app/src/test/java/org/xmlpull/v1/XmlPullParser.java \
  app-project/app/src/test/java/org/xmlpull/v1/XmlPullParserFactory.java \
  app-project/app/src/main/java/personal/muse/recorder/S3.java \
  app-project/app/src/main/java/personal/muse/recorder/MuseCloud.java \
  app-project/app/src/test/java/personal/muse/recorder/MuseCloudManualTest.java
java -cp /tmp/muse-cloud-test personal.muse.recorder.MuseCloudManualTest
```

The two `org.xmlpull` files are desktop stubs so this JDK command can compile `S3.java` (the R2 browser lists objects with Android's XML pull parser). They are under `src/test` and are not in the APK.
