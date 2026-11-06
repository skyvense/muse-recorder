package personal.muse.recorder;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop check for the Muse AI Cloud client. Not packaged in the APK
 * ({@code build-apk.sh} compiles {@code src/main} only).
 *
 * Run from the repo root:
 * javac -encoding UTF-8 -d /tmp/muse-cloud-test \
 *   app-project/app/src/main/java/personal/muse/recorder/S3.java \
 *   app-project/app/src/main/java/personal/muse/recorder/MuseCloud.java \
 *   app-project/app/src/test/java/personal/muse/recorder/MuseCloudManualTest.java
 * java -cp /tmp/muse-cloud-test personal.muse.recorder.MuseCloudManualTest
 */
public final class MuseCloudManualTest {
    private static int failures = 0;
    private static final AtomicInteger evilHits = new AtomicInteger();

    public static void main(String[] args) throws Exception {
        checkNormalize();
        checkParse();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", MuseCloudManualTest::handle);
        server.start();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        try {
            checkHttp(base);
        } finally {
            server.stop(0);
        }
        if (failures > 0) {
            System.err.println(failures + " failure(s)");
            System.exit(1);
        }
        System.out.println("MuseCloudManualTest OK");
    }

    private static void checkNormalize() {
        eq("http://HOST:8080", MuseCloud.normalizeBaseUrl("http://HOST:8080/"));
        eq("http://HOST:8080", MuseCloud.normalizeBaseUrl("http://HOST:8080/api"));
        eq("http://HOST:8080", MuseCloud.normalizeBaseUrl("HOST:8080"));
        eq("https://example.com", MuseCloud.normalizeBaseUrl("  https://example.com/api/ "));
        eq("", MuseCloud.normalizeBaseUrl(""));
        eq("", MuseCloud.normalizeBaseUrl("http://bad host"));
    }

    private static void checkParse() {
        MuseCloud.Parsed token = MuseCloud.parseBody("{\"token\":\"abc\"}");
        eq("abc", token.token);
        MuseCloud.Parsed nested = MuseCloud.parseBody("{\"data\":{\"access_token\":\"xyz\"}}");
        eq("xyz", nested.token);
        MuseCloud.Parsed plan = MuseCloud.parseBody(
                "{\"status\":\"ok\",\"subscription\":{\"status\":\"trialing\"}}");
        eq("trialing", plan.plan);
        MuseCloud.Parsed named = MuseCloud.parseBody("{\"subscription_status\":\"active\"}");
        eq("active", named.plan);
        eq("p\"a\\ss", MuseCloud.parseBody(
                "{\"password\":\"" + MuseCloud.escapeJson("p\"a\\ss") + "\"}").strings.get("password").get(0));
    }

    private static void checkHttp(String base) throws Exception {
        MuseCloud.AuthResult bad = MuseCloud.login(base, "a@b.c", "bad");
        eq(false, bad.ok);
        check(bad.message != null && bad.message.contains("Wrong email"), "bad login message: " + bad.message);

        MuseCloud.AuthResult stale = MuseCloud.login(base, "a@b.c", "stale");
        eq(false, stale.ok);
        check(stale.message != null && stale.message.contains("Token expired"), "stale: " + stale.message);

        MuseCloud.AuthResult registered = MuseCloud.register(base, "a@b.c", "p\"a\\ss");
        eq(true, registered.ok);
        eq("reg-token", registered.token);
        eq(null, registered.plan);

        MuseCloud.AuthResult signedIn = MuseCloud.login(base, "a@b.c", "good");
        eq(true, signedIn.ok);
        eq("login-token", signedIn.token);
        eq("trialing", signedIn.plan);

        MuseCloud.AuthResult redirected = MuseCloud.login(base, "a@b.c", "redirect");
        eq(false, redirected.ok);
        eq(0, evilHits.get());

        eq("/api/v1/upload/2026-11-06/rec_2026-11-06_12-00-00.m4a",
                MuseCloud.segmentPath("2026-11-06", "rec_2026-11-06_12-00-00.m4a"));
        eq("/api/v1/upload/2026-11-06/note_2026-11-06_12-00-00.m4a",
                MuseCloud.segmentPath("2026-11-06", "note_2026-11-06_12-00-00.m4a"));
        eq("/api/v1/upload/markers/events-2026-11-06.jsonl",
                MuseCloud.markersPath("events-2026-11-06.jsonl"));
        eq("/api/v1/upload/snapshot-2026-11-06/snap_2026-11-06_12-00-00_back.jpg",
                MuseCloud.snapshotPath("2026-11-06", "snap_2026-11-06_12-00-00_back.jpg"));
        eq("/api/v1/upload/snapshot-2026-11-06/snap_2026-11-06_12-00-00_front.jpg",
                MuseCloud.snapshotPath("2026-11-06", "snap_2026-11-06_12-00-00_front.jpg"));

        MuseCloud.AuthResult loggedOut = MuseCloud.logout(base, "login-token");
        eq(true, loggedOut.ok);
        MuseCloud.AuthResult refreshed = MuseCloud.refresh(base, "login-token");
        eq(true, refreshed.ok);
        eq("refreshed-token", refreshed.token);

        File audio = File.createTempFile("rec_2026-11-06_12-00-00", ".m4a");
        audio.deleteOnExit();
        byte[] bytes = "audio-bytes".getBytes(StandardCharsets.UTF_8);
        try (FileOutputStream out = new FileOutputStream(audio)) {
            out.write(bytes);
        }
        String path = MuseCloud.segmentPath("2026-11-06", audio.getName());
        MuseCloud.putFile(base, "login-token", path, audio, "audio/mp4", null);

        boolean threw = false;
        try {
            MuseCloud.putFile(base, "", path, audio, "audio/mp4", null);
        } catch (IOException e) {
            threw = true;
        }
        check(threw, "empty token should fail before the request");

        threw = false;
        try {
            MuseCloud.putFile(base, "nope", path, audio, "audio/mp4", null);
        } catch (IOException e) {
            // The desktop JDK drops error bodies on streaming PUTs. Android's
            // HttpURLConnection (same pattern as S3.java) returns them.
            // The status code is the part this check locks in.
            threw = e.getMessage() != null && e.getMessage().contains("HTTP 401");
        }
        check(threw, "missing bearer should be HTTP 401");

        File dup = File.createTempFile("dup_", ".m4a");
        dup.deleteOnExit();
        Files.write(dup.toPath(), bytes);
        MuseCloud.putFile(base, "login-token", MuseCloud.segmentPath("2026-11-06", dup.getName()),
                dup, "audio/mp4", null);

        File markers = File.createTempFile("events-2026-11-06", ".jsonl");
        markers.deleteOnExit();
        Files.write(markers.toPath(), "{\"type\":\"note\"}\n".getBytes(StandardCharsets.UTF_8));
        MuseCloud.putFile(base, "login-token", MuseCloud.markersPath("events-2026-11-06.jsonl"),
                markers, "application/x-ndjson", null);

        File snap = File.createTempFile("snap_2026-11-06_12-00-00", ".jpg");
        snap.deleteOnExit();
        Files.write(snap.toPath(), new byte[]{1, 2, 3, 4});
        MuseCloud.putFile(base, "login-token", MuseCloud.snapshotPath("2026-11-06", snap.getName()),
                snap, "image/jpeg", null);

        File closed = File.createTempFile("bill_", ".m4a");
        closed.deleteOnExit();
        Files.write(closed.toPath(), bytes);
        threw = false;
        try {
            MuseCloud.putFile(base, "login-token",
                    MuseCloud.segmentPath("2026-11-06", closed.getName()),
                    closed, "audio/mp4", null);
        } catch (IOException e) {
            threw = e.getMessage() != null
                    && e.getMessage().contains("HTTP 402")
                    && e.getMessage().contains("Uploads are closed");
        }
        check(threw, "402 should say uploads are closed");
    }

    private static void handle(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        String method = ex.getRequestMethod();
        byte[] request = readAll(ex.getRequestBody());
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        if ("/evil".equals(path)) {
            evilHits.incrementAndGet();
            send(ex, 500, "{\"message\":\"followed redirect\"}");
            return;
        }
        if ("POST".equals(method) && (MuseCloud.AUTH_LOGIN.equals(path)
                || MuseCloud.AUTH_REGISTER.equals(path))) {
            Map<String, List<String>> fields = MuseCloud.scanStrings(new String(request, StandardCharsets.UTF_8));
            String client = fields.containsKey("client") ? fields.get("client").get(0) : "";
            if (!"android".equals(client)) {
                send(ex, 400, "{\"message\":\"client must be android\"}");
                return;
            }
        }
        if ("POST".equals(method) && MuseCloud.AUTH_LOGIN.equals(path)) {
            Map<String, List<String>> fields = MuseCloud.scanStrings(new String(request, StandardCharsets.UTF_8));
            String password = fields.containsKey("password") ? fields.get("password").get(0) : "";
            if ("bad".equals(password)) {
                send(ex, 401, "{\"message\":\"Wrong email or password\"}");
            } else if ("stale".equals(password)) {
                send(ex, 200, "{\"token\":\"stale-token\"}");
            } else if ("redirect".equals(password)) {
                ex.getResponseHeaders().set("Location", "/evil");
                ex.sendResponseHeaders(302, -1);
                ex.close();
            } else if ("good".equals(password)) {
                send(ex, 200, "{\"token\":\"login-token\"}");
            } else {
                send(ex, 400, "{\"message\":\"unexpected password\"}");
            }
            return;
        }
        if ("POST".equals(method) && MuseCloud.AUTH_REGISTER.equals(path)) {
            Map<String, List<String>> fields = MuseCloud.scanStrings(new String(request, StandardCharsets.UTF_8));
            String password = fields.containsKey("password") ? fields.get("password").get(0) : "";
            if (!"p\"a\\ss".equals(password)) {
                send(ex, 400, "{\"message\":\"password was not decoded\"}");
                return;
            }
            send(ex, 201, "{\"token\":\"reg-token\",\"user\":{\"email\":\"a@b.c\"}}");
            return;
        }
        if ("POST".equals(method) && MuseCloud.AUTH_LOGOUT.equals(path)) {
            if (!"Bearer login-token".equals(auth)) {
                send(ex, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            send(ex, 200, "{\"ok\":true}");
            return;
        }
        if ("POST".equals(method) && MuseCloud.AUTH_REFRESH.equals(path)) {
            if (!"Bearer login-token".equals(auth)) {
                send(ex, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            send(ex, 200, "{\"token\":\"refreshed-token\",\"user\":{\"email\":\"a@b.c\"}}");
            return;
        }
        if ("GET".equals(method) && MuseCloud.AUTH_ME.equals(path)) {
            if ("Bearer login-token".equals(auth)) {
                send(ex, 200, "{\"email\":\"a@b.c\",\"subscription_status\":\"trialing\"}");
            } else if ("Bearer stale-token".equals(auth)) {
                send(ex, 401, "{\"message\":\"Token expired\"}");
            } else if ("Bearer reg-token".equals(auth)) {
                send(ex, 404, "{\"message\":\"not implemented\"}");
            } else {
                send(ex, 401, "{\"message\":\"unauthorized\"}");
            }
            return;
        }
        if ("PUT".equals(method) && path.startsWith("/api/v1/upload/")) {
            if (!"Bearer login-token".equals(auth)) {
                send(ex, 401, "{\"message\":\"unauthorized\"}");
                return;
            }
            String name = path.substring(path.lastIndexOf('/') + 1);
            if (name.startsWith("bill_")) {
                send(ex, 402, "{\"message\":\"subscription required\"}");
                return;
            }
            if (name.startsWith("dup_")) {
                send(ex, 409, "{\"message\":\"already stored\"}");
                return;
            }
            boolean audio = name.endsWith(".m4a");
            boolean markers = path.contains("/markers/events-");
            boolean snap = path.contains("/snapshot-");
            if (audio && !"audio/mp4".equals(ex.getRequestHeaders().getFirst("Content-Type"))) {
                send(ex, 400, "{\"message\":\"content type\"}");
                return;
            }
            if (markers) {
                String body = new String(request, StandardCharsets.UTF_8);
                if (!body.contains("\"type\":\"note\"")) {
                    send(ex, 400, "{\"message\":\"markers body\"}");
                    return;
                }
            }
            if (audio && request.length != "audio-bytes".length()) {
                send(ex, 400, "{\"message\":\"short body\"}");
                return;
            }
            if (!audio && !markers && !snap) {
                send(ex, 400, "{\"message\":\"unexpected key " + path + "\"}");
                return;
            }
            send(ex, 200, "{\"ok\":true}");
            return;
        }
        if ("PUT".equals(method) && (path.startsWith("/api/days/") || path.startsWith("/api/auth/"))) {
            send(ex, 404, "{\"message\":\"old contract\"}");
            return;
        }
        send(ex, 404, "{\"message\":\"not found " + path + "\"}");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream stream = in) {
            return stream.readAllBytes();
        }
    }

    private static void send(HttpExchange ex, int code, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json");
        ex.sendResponseHeaders(code, bytes.length);
        try (OutputStream out = ex.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static void eq(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual)) {
            failures++;
            System.err.println("expected <" + expected + "> but was <" + actual + ">");
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) {
            failures++;
            System.err.println(message);
        }
    }
}
