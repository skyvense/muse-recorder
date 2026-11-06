package personal.muse.recorder;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Minimal client for a self-hosted Muse AI Cloud shelf.
 *
 * <p>The shelf was not in this repo, so the app speaks the REST shape
 * documented in {@code docs/muse-ai-cloud.md}. The phone never holds an AI
 * provider key: it only exchanges an email and password for a JWT, then
 * uploads the day's files with {@code Authorization: Bearer}.
 *
 * <p>No Android dependencies, so the parser and HTTP calls can be exercised
 * on a desktop JVM ({@code MuseCloudManualTest}).
 */
public final class MuseCloud {
    static final String CLIENT = "muse-recorder";

    public static final String AUTH_REGISTER = "/api/auth/register";
    public static final String AUTH_LOGIN = "/api/auth/login";
    public static final String AUTH_ME = "/api/auth/me";

    private static final String[] TOKEN_KEYS = {"token", "access_token", "accessToken", "jwt"};
    private static final String[] PLAN_VALUES = {
            "trialing", "active", "past_due", "canceled", "cancelled",
            "inactive", "incomplete", "paused", "none", "expired"
    };

    private MuseCloud() {}

    /** Result of register, login, or a follow-up {@code /api/auth/me}. */
    public static final class AuthResult {
        public final boolean ok;
        public final int httpCode;
        public final String token;
        public final String message;
        /** Subscription status when the server sent one; otherwise null. */
        public final String plan;

        AuthResult(boolean ok, int httpCode, String token, String message, String plan) {
            this.ok = ok;
            this.httpCode = httpCode;
            this.token = token;
            this.message = message;
            this.plan = plan;
        }
    }

    /**
     * Origin only, no trailing slash and no {@code /api} suffix.
     * A missing scheme becomes {@code http://} because the shelf's PUBLIC_URL
     * is commonly {@code http://HOST:8080} on a private network.
     */
    public static String normalizeBaseUrl(String raw) {
        if (raw == null) return "";
        String u = raw.trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.endsWith("/api")) u = u.substring(0, u.length() - 4);
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        if (u.isEmpty()) return "";
        if (u.indexOf("://") < 0) u = "http://" + u;
        if (u.indexOf('\r') >= 0 || u.indexOf('\n') >= 0 || u.indexOf(' ') >= 0) return "";
        return u;
    }

    public static String segmentPath(String date, String filename) {
        return "/api/days/" + date + "/segments/" + filename;
    }

    public static String markersPath(String date) {
        return "/api/days/" + date + "/markers";
    }

    public static String snapshotPath(String date, String filename) {
        return "/api/days/" + date + "/snapshots/" + filename;
    }

    public static AuthResult register(String baseUrl, String email, String password) throws IOException {
        return authenticate(baseUrl, AUTH_REGISTER, email, password);
    }

    public static AuthResult login(String baseUrl, String email, String password) throws IOException {
        return authenticate(baseUrl, AUTH_LOGIN, email, password);
    }

    private static AuthResult authenticate(String baseUrl, String path, String email, String password)
            throws IOException {
        String base = normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) {
            return new AuthResult(false, 0, null, "Missing cloud base URL", null);
        }
        String json = "{\"email\":\"" + escapeJson(email) + "\",\"password\":\""
                + escapeJson(password) + "\"}";
        HttpResult http = postJson(base, path, json);
        if (http.code < 200 || http.code >= 300) {
            return new AuthResult(false, http.code, null, http.message, null);
        }
        Parsed parsed = parseBody(http.body);
        if (parsed.token == null || parsed.token.isEmpty()) {
            return new AuthResult(false, http.code, null,
                    "Signed response had no token. Expected JSON token or access_token.", null);
        }
        String plan = parsed.plan;
        try {
            HttpResult me = get(base, AUTH_ME, parsed.token);
            if (me.code == 401 || me.code == 403) {
                return new AuthResult(false, me.code, null,
                        me.message == null ? "Token was rejected" : me.message, null);
            }
            if (me.code >= 200 && me.code < 300) {
                Parsed meParsed = parseBody(me.body);
                if (meParsed.plan != null) plan = meParsed.plan;
            }
            // 404 (or any other non-auth failure) — login itself succeeded.
        } catch (IOException ignored) {
            // /api/auth/me is optional. A network blip after a 200 login
            // still leaves the caller with a usable token.
        }
        return new AuthResult(true, http.code, parsed.token, null, plan);
    }

    /**
     * Raw PUT of one file. 2xx and 409 (already stored) are success.
     * Redirects are not followed, so the bearer token stays on this host.
     */
    public static void putFile(String baseUrl, String token, String apiPath, File file,
                                String contentType, S3.ProgressListener listener) throws IOException {
        String base = normalizeBaseUrl(baseUrl);
        if (base.isEmpty()) throw new IOException("Missing cloud base URL");
        String bearer = safeHeader(token);
        if (bearer.isEmpty()) throw new IOException("Missing cloud token");
        String safePath = apiPath.startsWith("/") ? apiPath : "/" + apiPath;
        URL url = new URL(base + S3.encodePath(safePath));

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", "Bearer " + bearer);
        conn.setRequestProperty("Content-Type", contentType);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Muse-Client", CLIENT);
        conn.setFixedLengthStreamingMode(file.length());
        try (OutputStream out = conn.getOutputStream();
             InputStream in = new FileInputStream(file)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            long sent = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                sent += n;
                if (listener != null) listener.onProgress(sent, file.length());
            }
            out.flush();
        }
        int code = conn.getResponseCode();
        if (code == 409 || (code >= 200 && code < 300)) {
            conn.disconnect();
            return;
        }
        String body = readBody(conn, code);
        conn.disconnect();
        String detail = serverMessage(body);
        throw new IOException("Muse AI Cloud PUT " + safePath + " HTTP " + code
                + (detail.isEmpty() ? "" : " " + detail));
    }

    // ---------- HTTP ----------

    private static final class HttpResult {
        final int code;
        final String body;
        final String message;

        HttpResult(int code, String body) {
            this.code = code;
            this.body = body == null ? "" : body;
            String parsed = serverMessage(this.body);
            this.message = parsed.isEmpty() ? "HTTP " + code : parsed;
        }
    }

    private static HttpResult postJson(String base, String path, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        HttpURLConnection conn = open(base, path, "POST", null);
        conn.setDoOutput(true);
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        // Auth bodies are tiny. Leaving streaming mode off lets the JDK return
        // the error JSON (wrong password, trial ended on a later call, etc.).
        try (OutputStream out = conn.getOutputStream()) {
            out.write(bytes);
        }
        int code = conn.getResponseCode();
        String body = readBody(conn, code);
        conn.disconnect();
        return new HttpResult(code, body);
    }

    private static HttpResult get(String base, String path, String token) throws IOException {
        HttpURLConnection conn = open(base, path, "GET", token);
        int code = conn.getResponseCode();
        String body = readBody(conn, code);
        conn.disconnect();
        return new HttpResult(code, body);
    }

    private static HttpURLConnection open(String base, String path, String method, String token)
            throws IOException {
        String safePath = path.startsWith("/") ? path : "/" + path;
        URL url = new URL(base + S3.encodePath(safePath));
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setInstanceFollowRedirects(false);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(30_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("X-Muse-Client", CLIENT);
        if (token != null && !token.isEmpty()) {
            conn.setRequestProperty("Authorization", "Bearer " + safeHeader(token));
        }
        return conn;
    }

    private static String readBody(HttpURLConnection conn, int code) {
        InputStream in = null;
        try {
            if (code >= 200 && code < 300) in = conn.getInputStream();
            else in = conn.getErrorStream();
        } catch (IOException ignored) {
            return "";
        }
        if (in == null) return "";
        try (InputStream stream = in) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[2048];
            int n;
            while ((n = stream.read(buf)) > 0 && bos.size() < 8192) {
                bos.write(buf, 0, n);
            }
            return bos.toString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    // ---------- JSON (just the fields this client reads) ----------

    static final class Parsed {
        String token;
        String plan;
        final Map<String, List<String>> strings = new LinkedHashMap<>();
    }

    /** First string value of each key, plus every value of {@code status}-like keys. */
    static Parsed parseBody(String body) {
        Parsed parsed = new Parsed();
        if (body == null || body.isEmpty()) return parsed;
        Map<String, List<String>> found = scanStrings(body);
        parsed.strings.putAll(found);
        for (String key : TOKEN_KEYS) {
            String value = first(found, key);
            if (value != null && !value.isEmpty()) {
                parsed.token = value;
                break;
            }
        }
        List<String> plans = new ArrayList<>();
        addAll(plans, found.get("subscription_status"));
        addAll(plans, found.get("subscriptionStatus"));
        addAll(plans, found.get("status"));
        for (String value : plans) {
            for (String known : PLAN_VALUES) {
                if (known.equalsIgnoreCase(value)) {
                    parsed.plan = known;
                    return parsed;
                }
            }
        }
        return parsed;
    }

    private static void addAll(List<String> dest, List<String> src) {
        if (src != null) dest.addAll(src);
    }

    private static String first(Map<String, List<String>> found, String key) {
        List<String> values = found.get(key);
        if (values == null || values.isEmpty()) return null;
        return values.get(0);
    }

    static Map<String, List<String>> scanStrings(String json) {
        Map<String, List<String>> found = new LinkedHashMap<>();
        String pendingKey = null;
        boolean expectValue = false;
        int i = 0;
        int n = json.length();
        int[] end = new int[1];
        while (i < n) {
            char c = json.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') {
                i++;
                continue;
            }
            if (c == '"') {
                String s = readJsonString(json, i, end);
                if (s == null) break;
                i = end[0];
                if (expectValue) {
                    if (pendingKey != null) {
                        List<String> list = found.get(pendingKey);
                        if (list == null) {
                            list = new ArrayList<>();
                            found.put(pendingKey, list);
                        }
                        list.add(s);
                    }
                    expectValue = false;
                    pendingKey = null;
                } else {
                    pendingKey = s;
                }
                continue;
            }
            if (c == ':' && pendingKey != null && !expectValue) {
                expectValue = true;
                i++;
                continue;
            }
            if (expectValue) {
                expectValue = false;
                pendingKey = null;
            } else if (c == ',' || c == '{' || c == '}' || c == '[' || c == ']') {
                pendingKey = null;
            }
            i++;
        }
        return found;
    }

    /** {@code endOut[0]} is the index just past the closing quote. */
    static String readJsonString(String json, int quoteStart, int[] endOut) {
        StringBuilder sb = new StringBuilder();
        for (int i = quoteStart + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') {
                endOut[0] = i + 1;
                return sb.toString();
            }
            if (c == '\\' && i + 1 < json.length()) {
                char n = json.charAt(++i);
                switch (n) {
                    case '"':
                    case '\\':
                    case '/':
                        sb.append(n);
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (i + 4 < json.length()) {
                            String hex = json.substring(i + 1, i + 5);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException e) {
                                sb.append(hex);
                            }
                            i += 4;
                        }
                        break;
                    default:
                        sb.append(n);
                        break;
                }
            } else {
                sb.append(c);
            }
        }
        endOut[0] = json.length();
        return null;
    }

    static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\':
                    sb.append("\\\\");
                    break;
                case '"':
                    sb.append("\\\"");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (c < 0x20) sb.append(String.format(Locale.US, "\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        return sb.toString();
    }

    /** Server text from a JSON body, or a short raw snippet. Empty if there is none. */
    private static String serverMessage(String body) {
        Parsed parsed = parseBody(body);
        String message = first(parsed.strings, "message");
        if (message == null || message.isEmpty()) message = first(parsed.strings, "detail");
        if (message == null || message.isEmpty()) message = first(parsed.strings, "error");
        if (message != null && !message.isEmpty()) return message;
        if (body == null) return "";
        String snippet = body.replace('\n', ' ').replace('\r', ' ').trim();
        if (snippet.isEmpty()) return "";
        if (snippet.length() > 180) snippet = snippet.substring(0, 180) + "…";
        return snippet;
    }

    /** Drop CR/LF so a token cannot inject extra headers. */
    private static String safeHeader(String value) {
        if (value == null) return "";
        return value.replace("\r", "").replace("\n", "").trim();
    }
}
