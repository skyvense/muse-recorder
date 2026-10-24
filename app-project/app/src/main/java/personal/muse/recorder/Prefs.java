package personal.muse.recorder;

import android.content.Context;
import android.content.SharedPreferences;

import java.io.File;

/** Central access to settings and the segments directory. */
public final class Prefs {
    private static final String NAME = "muse_recorder_prefs";

    public static final String KEY_SERVER_URL = "server_url";
    public static final String KEY_TOKEN = "token";
    public static final String KEY_WIFI_ONLY = "wifi_only";
    public static final String KEY_DELETE_AFTER_UPLOAD = "delete_after_upload";
    public static final String KEY_AUTO_RESUME = "auto_resume";
    public static final String KEY_SEGMENT_MINUTES = "segment_minutes";
    public static final String KEY_WAS_RECORDING = "was_recording";
    public static final String KEY_UPLOAD_MODE = "upload_mode"; // legacy (v1.0/v1.1)
    public static final String KEY_SB_URL = "supabase_url";
    public static final String KEY_SB_BUCKET = "supabase_bucket";
    public static final String KEY_SB_KEY = "supabase_key";
    // v1.2: S3-only mode
    public static final String KEY_APP_LANGUAGE = "app_language"; // "system" or a language code
    public static final String KEY_S3_PRESET = "s3_preset";
    public static final String KEY_S3_ENDPOINT = "s3_endpoint";
    public static final String KEY_S3_REGION = "s3_region";
    public static final String KEY_S3_BUCKET = "s3_bucket";
    public static final String KEY_S3_ACCESS_KEY = "s3_access_key";
    public static final String KEY_S3_SECRET_KEY = "s3_secret_key";
    public static final String KEY_S3_VIRTUAL = "s3_virtual_style";
    public static final String KEY_CONV_ACTIVE = "conv_active";
    public static final String KEY_MEETING_ACTIVE = "meeting_active";
    public static final String KEY_CONV_SINCE = "conv_since";
    public static final String KEY_MEETING_SINCE = "meeting_since";

    private Prefs() {}

    public static SharedPreferences get(Context ctx) {
        return ctx.getSharedPreferences(NAME, Context.MODE_PRIVATE);
    }

    public static String serverUrl(Context ctx) {
        return get(ctx).getString(KEY_SERVER_URL, "").trim();
    }

    public static String token(Context ctx) {
        return get(ctx).getString(KEY_TOKEN, "").trim();
    }

    public static boolean isSupabaseMode(Context ctx) {
        return "supabase".equals(get(ctx).getString(KEY_UPLOAD_MODE, "server"));
    }

    public static String supabaseUrl(Context ctx) {
        String u = get(ctx).getString(KEY_SB_URL, "").trim();
        while (u.endsWith("/")) u = u.substring(0, u.length() - 1);
        return u;
    }

    public static String supabaseBucket(Context ctx) {
        String b = get(ctx).getString(KEY_SB_BUCKET, "recordings").trim();
        return b.isEmpty() ? "recordings" : b;
    }

    public static String supabaseKey(Context ctx) {
        return get(ctx).getString(KEY_SB_KEY, "").trim();
    }

    public static boolean wifiOnly(Context ctx) {
        return get(ctx).getBoolean(KEY_WIFI_ONLY, false);
    }

    public static boolean deleteAfterUpload(Context ctx) {
        return get(ctx).getBoolean(KEY_DELETE_AFTER_UPLOAD, true);
    }

    public static boolean autoResume(Context ctx) {
        return get(ctx).getBoolean(KEY_AUTO_RESUME, false);
    }

    public static int segmentMinutes(Context ctx) {
        int m = get(ctx).getInt(KEY_SEGMENT_MINUTES, 60);
        if (m < 5) m = 5;
        if (m > 240) m = 240;
        return m;
    }

    /** Directory holding finished/pending .m4a segments. */
    public static File segmentsDir(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "segments");
        if (!dir.exists()) dir.mkdirs();
        if (!dir.exists()) {
            dir = new File(ctx.getFilesDir(), "segments");
            dir.mkdirs();
        }
        return dir;
    }

    public static S3.Config s3Config(Context ctx) {
        SharedPreferences p = get(ctx);
        S3.Config c = new S3.Config();
        c.endpoint = p.getString(KEY_S3_ENDPOINT, "").trim();
        while (c.endpoint.endsWith("/")) c.endpoint = c.endpoint.substring(0, c.endpoint.length() - 1);
        if (!c.endpoint.isEmpty() && !c.endpoint.contains("://")) c.endpoint = "https://" + c.endpoint;
        c.region = p.getString(KEY_S3_REGION, "auto").trim();
        if (c.region.isEmpty()) c.region = "auto";
        c.bucket = p.getString(KEY_S3_BUCKET, "").trim();
        c.accessKey = p.getString(KEY_S3_ACCESS_KEY, "").trim();
        c.secretKey = p.getString(KEY_S3_SECRET_KEY, "").trim();
        c.virtualStyle = p.getBoolean(KEY_S3_VIRTUAL, false);
        return c;
    }

    public static boolean s3Configured(Context ctx) {
        S3.Config c = s3Config(ctx);
        return !c.endpoint.isEmpty() && !c.bucket.isEmpty()
                && !c.accessKey.isEmpty() && !c.secretKey.isEmpty();
    }

    /** Directory holding event-marker files (events-YYYY-MM-DD.jsonl). */
    public static File markersDir(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "markers");
        if (!dir.exists()) dir.mkdirs();
        if (!dir.exists()) {
            dir = new File(ctx.getFilesDir(), "markers");
            dir.mkdirs();
        }
        return dir;
    }

    /** A segment counts as uploaded once a "<name>.done" marker exists next to it. */
    public static File markerFor(File segment) {
        return new File(segment.getParentFile(), segment.getName() + ".done");
    }
}
