package personal.muse.recorder;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Minimal S3 client: SigV4-signed PUT Object, framework/pure Java only.
 *
 * Works with AWS S3, Cloudflare R2, Backblaze B2, Aliyun OSS (S3 mode),
 * Tencent COS (S3 mode) and Qiniu Kodo (S3 mode). Two addressing styles:
 *   path style:    https://endpoint/bucket/key
 *   virtual style: https://bucket.endpoint/key
 *
 * This class has no Android dependencies so the signer can be unit-tested
 * on a desktop JVM against AWS's published SigV4 test vector.
 */
public final class S3 {
    private S3() {}

    public static final class Config {
        public String endpoint;   // e.g. https://<account>.r2.cloudflarestorage.com
        public String region;     // e.g. auto / us-west-004 / cn-hangzhou
        public String bucket;
        public String accessKey;
        public String secretKey;
        public boolean virtualStyle;
    }

    // ---------- signing ----------

    static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    static String sha256Hex(byte[] data) throws Exception {
        return hex(MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String sha256Hex(File f) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (InputStream in = new FileInputStream(f)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
        }
        return hex(md.digest());
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format(Locale.US, "%02x", x));
        return sb.toString();
    }

    /** RFC 3986 encoding of one path segment (keeps -_.~ and alphanumerics). */
    static String encodeSegment(String s) {
        StringBuilder sb = new StringBuilder();
        for (byte raw : s.getBytes(StandardCharsets.UTF_8)) {
            int c = raw & 0xFF;
            boolean unreserved = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                    || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '~';
            if (unreserved) sb.append((char) c);
            else sb.append('%').append(String.format(Locale.US, "%02X", c));
        }
        return sb.toString();
    }

    static String encodePath(String path) {
        String[] parts = path.split("/", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) sb.append('/');
            sb.append(encodeSegment(parts[i]));
        }
        return sb.toString();
    }

    /**
     * Builds the Authorization header value for one request.
     * headersToSign must include "host"; keys are lower-cased before use.
     */
    public static String authorization(String method, String canonicalUri, String canonicalQuery,
                                       TreeMap<String, String> headersToSign, String payloadHash,
                                       String region, String service, String accessKey,
                                       String secretKey, String amzDate) throws Exception {
        String dateStamp = amzDate.substring(0, 8);
        StringBuilder canonHeaders = new StringBuilder();
        StringBuilder signedNames = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : headersToSign.entrySet()) {
            canonHeaders.append(e.getKey()).append(':')
                    .append(e.getValue().trim()).append('\n');
            if (signedNames.length() > 0) signedNames.append(';');
            signedNames.append(e.getKey());
        }
        String canonicalRequest = method + "\n" + canonicalUri + "\n" + canonicalQuery + "\n"
                + canonHeaders + "\n" + signedNames + "\n" + payloadHash;
        String scope = dateStamp + "/" + region + "/" + service + "/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n"
                + sha256Hex(canonicalRequest.getBytes(StandardCharsets.UTF_8));
        byte[] kDate = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), dateStamp);
        byte[] kRegion = hmac(kDate, region);
        byte[] kService = hmac(kRegion, service);
        byte[] kSigning = hmac(kService, "aws4_request");
        String signature = hex(hmac(kSigning, stringToSign));
        return "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope
                + ", SignedHeaders=" + signedNames + ", Signature=" + signature;
    }

    static String amzDateNow() {
        SimpleDateFormat f = new SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(new Date());
    }

    // ---------- PUT object ----------

    public interface ProgressListener {
        void onProgress(long sent, long total);
    }

    /** Uploads a file; returns true on HTTP 200. */
    public static boolean putObject(Config cfg, String key, File file, String contentType)
            throws Exception {
        return putObject(cfg, key, file, contentType, null);
    }

    public static boolean putObject(Config cfg, String key, File file, String contentType,
                                    ProgressListener listener) throws Exception {
        String payloadHash = sha256Hex(file);
        String amzDate = amzDateNow();

        URL base = new URL(cfg.endpoint);
        String endpointHost = base.getHost()
                + (base.getPort() > 0 ? ":" + base.getPort() : "");
        String host;
        String rawPath;
        if (cfg.virtualStyle) {
            host = cfg.bucket + "." + endpointHost;
            rawPath = "/" + key;
        } else {
            host = endpointHost;
            rawPath = "/" + cfg.bucket + "/" + key;
        }
        String canonicalUri = encodePath(rawPath);
        String scheme = base.getProtocol();
        URL url = new URL(scheme + "://" + host + canonicalUri);

        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("content-type", contentType);
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String auth = authorization("PUT", canonicalUri, "", headers, payloadHash,
                cfg.region, "s3", cfg.accessKey, cfg.secretKey, amzDate);

        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(180_000);
        conn.setDoOutput(true);
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Authorization", auth);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        conn.setRequestProperty("Content-Type", contentType);
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
        if (code < 200 || code >= 300) {
            // Drain error body for logging by caller via exception message.
            String err = "";
            try (InputStream es = conn.getErrorStream()) {
                if (es != null) {
                    java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                    byte[] b = new byte[4096];
                    int n;
                    while ((n = es.read(b)) > 0) bos.write(b, 0, n);
                    err = bos.toString("UTF-8");
                }
            } catch (Exception ignored) {}
            conn.disconnect();
            throw new java.io.IOException("S3 PUT HTTP " + code + " " + err);
        }
        conn.disconnect();
        return true;
    }

    // ---------- GET: list + download (used by the cloud browser tab) ----------

    public static final class Entry {
        public String key;
        public long size;
    }

    public static final class ListResult {
        public final java.util.List<Entry> entries = new java.util.ArrayList<>();
        public final java.util.List<String> prefixes = new java.util.ArrayList<>();
    }

    private static String emptyHash() throws Exception {
        return sha256Hex(new byte[0]);
    }

    /** Builds a signed connection for a bucket-level (key == null) or object request. */
    private static HttpURLConnection openSigned(Config cfg, String method, String key,
                                                TreeMap<String, String> query) throws Exception {
        String payloadHash = emptyHash();
        String amzDate = amzDateNow();
        URL base = new URL(cfg.endpoint);
        String endpointHost = base.getHost() + (base.getPort() > 0 ? ":" + base.getPort() : "");
        String host;
        String rawPath;
        if (cfg.virtualStyle) {
            host = cfg.bucket + "." + endpointHost;
            rawPath = key == null ? "/" : "/" + key;
        } else {
            host = endpointHost;
            rawPath = key == null ? "/" + cfg.bucket : "/" + cfg.bucket + "/" + key;
        }
        String canonicalUri = encodePath(rawPath);
        StringBuilder qs = new StringBuilder();
        for (java.util.Map.Entry<String, String> e : query.entrySet()) {
            if (qs.length() > 0) qs.append('&');
            qs.append(encodeSegment(e.getKey())).append('=').append(encodeSegment(e.getValue()));
        }
        String canonicalQuery = qs.toString();
        URL url = new URL(base.getProtocol() + "://" + host + canonicalUri
                + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery));
        TreeMap<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        String auth = authorization(method, canonicalUri, canonicalQuery, headers, payloadHash,
                cfg.region, "s3", cfg.accessKey, cfg.secretKey, amzDate);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);
        conn.setRequestMethod(method);
        conn.setRequestProperty("Authorization", auth);
        conn.setRequestProperty("x-amz-date", amzDate);
        conn.setRequestProperty("x-amz-content-sha256", payloadHash);
        return conn;
    }

    private static String readBody(HttpURLConnection conn) throws Exception {
        InputStream in = conn.getInputStream();
        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
        byte[] b = new byte[8192];
        int n;
        while ((n = in.read(b)) > 0) bos.write(b, 0, n);
        return bos.toString("UTF-8");
    }

    /** Lists one "folder" level: objects directly under prefix plus sub-prefixes. */
    public static ListResult list(Config cfg, String prefix, String delimiter) throws Exception {
        ListResult result = new ListResult();
        String token = null;
        for (int page = 0; page < 20; page++) {
            TreeMap<String, String> q = new TreeMap<>();
            q.put("list-type", "2");
            if (prefix != null && !prefix.isEmpty()) q.put("prefix", prefix);
            if (delimiter != null && !delimiter.isEmpty()) q.put("delimiter", delimiter);
            if (token != null) q.put("continuation-token", token);
            HttpURLConnection conn = openSigned(cfg, "GET", null, q);
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) {
                String err = "";
                try (InputStream es = conn.getErrorStream()) {
                    if (es != null) {
                        java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                        byte[] b = new byte[4096];
                        int n;
                        while ((n = es.read(b)) > 0) bos.write(b, 0, n);
                        err = bos.toString("UTF-8");
                    }
                } catch (Exception ignored) {}
                conn.disconnect();
                throw new java.io.IOException("S3 LIST HTTP " + code + " " + err);
            }
            String xml = readBody(conn);
            conn.disconnect();
            token = parseListPage(xml, result);
            if (token == null) break;
        }
        return result;
    }

    /** Parses one ListObjectsV2 page into result; returns the next continuation token or null. */
    private static String parseListPage(String xml, ListResult result) throws Exception {
        org.xmlpull.v1.XmlPullParserFactory factory = org.xmlpull.v1.XmlPullParserFactory.newInstance();
        factory.setNamespaceAware(false);
        org.xmlpull.v1.XmlPullParser p = factory.newPullParser();
        p.setInput(new java.io.StringReader(xml));
        String nextToken = null;
        String currentKey = null;
        long currentSize = 0;
        boolean inCommonPrefix = false;
        int ev;
        while ((ev = p.next()) != org.xmlpull.v1.XmlPullParser.END_DOCUMENT) {
            if (ev == org.xmlpull.v1.XmlPullParser.START_TAG) {
                String name = p.getName();
                if ("CommonPrefixes".equals(name)) inCommonPrefix = true;
                else if ("Key".equals(name)) currentKey = p.nextText();
                else if ("Size".equals(name)) {
                    try { currentSize = Long.parseLong(p.nextText().trim()); }
                    catch (Exception ignored) { currentSize = 0; }
                } else if ("Prefix".equals(name) && inCommonPrefix) {
                    result.prefixes.add(p.nextText());
                } else if ("NextContinuationToken".equals(name)) {
                    nextToken = p.nextText();
                }
            } else if (ev == org.xmlpull.v1.XmlPullParser.END_TAG) {
                String name = p.getName();
                if ("Contents".equals(name) && currentKey != null) {
                    Entry e = new Entry();
                    e.key = currentKey;
                    e.size = currentSize;
                    result.entries.add(e);
                    currentKey = null;
                    currentSize = 0;
                } else if ("CommonPrefixes".equals(name)) inCommonPrefix = false;
            }
        }
        return nextToken;
    }

    /** Downloads one object to a local file (streaming). */
    public static void download(Config cfg, String key, File dest) throws Exception {
        HttpURLConnection conn = openSigned(cfg, "GET", key, new TreeMap<>());
        int code = conn.getResponseCode();
        if (code < 200 || code >= 300) {
            conn.disconnect();
            throw new java.io.IOException("S3 GET HTTP " + code + " for " + key);
        }
        try (InputStream in = conn.getInputStream();
             OutputStream out = new java.io.FileOutputStream(dest)) {
            byte[] buf = new byte[128 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        conn.disconnect();
    }
}
