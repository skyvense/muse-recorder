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
    public static final String KEY_DARK_MODE = "dark_mode"; // 0=follow system, 1=light, 2=dark
    public static final String KEY_S3_PRESET = "s3_preset";
    public static final String KEY_S3_ENDPOINT = "s3_endpoint";
    public static final String KEY_S3_REGION = "s3_region";
    public static final String KEY_S3_BUCKET = "s3_bucket";
    public static final String KEY_S3_ACCESS_KEY = "s3_access_key";
    public static final String KEY_S3_SECRET_KEY = "s3_secret_key";
    public static final String KEY_S3_VIRTUAL = "s3_virtual_style";
    public static final String KEY_SNAPSHOT_ENABLED = "snapshot_enabled";
    public static final String KEY_SNAPSHOT_CAMERA = "snapshot_camera"; // 0=both, 1=back only, 2=front only
    public static final String KEY_SNAPSHOT_FILTER = "snapshot_filter"; // drop blank frames on-device
    public static final String KEY_SNAPSHOT_REPEAT_MINUTES = "snapshot_repeat_minutes"; // held-upright auto-repeat
    public static final String KEY_SEG_TODAY_DATE = "seg_today_date"; // yyyy-MM-dd
    public static final String KEY_SEG_TODAY_COUNT = "seg_today_count";
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

    /** Dark-mode preference: 0 = follow system (default), 1 = light, 2 = dark. */
    public static int darkMode(Context ctx) {
        int m = get(ctx).getInt(KEY_DARK_MODE, 0);
        return (m >= 0 && m <= 2) ? m : 0;
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

    /** Auto-snapshot on the raise gesture; strictly opt-in, default OFF. */
    public static boolean snapshotEnabled(Context ctx) {
        return get(ctx).getBoolean(KEY_SNAPSHOT_ENABLED, false);
    }

    // The reports tab reuses the legacy supabaseUrl()/supabaseKey() above
    // (KEY_SB_URL / KEY_SB_KEY) — same SharedPreferences keys.

    /**
     * On-device blank-frame filter for snapshots (v1.17.0): frames that are
     * near-black or detail-free (lens covered / in a pocket / badly blurred)
     * are scored on the phone and never uploaded. Default ON; the scoring
     * itself is a few ms on a downscaled copy, far cheaper than the upload
     * it saves.
     */
    public static boolean snapshotFilterEnabled(Context ctx) {
        return get(ctx).getBoolean(KEY_SNAPSHOT_FILTER, true);
    }

    /**
     * Held-upright auto-repeat interval in minutes (v1.21.0): while the
     * phone stays raised after a snapshot, shoot again every this many
     * minutes instead of waiting for it to be laid flat. Default 3;
     * 0 = off (the pre-v1.21 behaviour).
     */
    public static int snapshotRepeatMinutes(Context ctx) {
        int m = get(ctx).getInt(KEY_SNAPSHOT_REPEAT_MINUTES, 3);
        if (m < 0) m = 0;
        if (m > 120) m = 120;
        return m;
    }

    public static long snapshotRepeatMs(Context ctx) {
        return snapshotRepeatMinutes(ctx) * 60_000L;
    }

    /** Which cameras a snapshot uses: 0 = both, 1 = back only, 2 = front only. */
    public static int snapshotCameraMode(Context ctx) {
        int m = get(ctx).getInt(KEY_SNAPSHOT_CAMERA, 0);
        return (m >= 0 && m <= 2) ? m : 0;
    }

    /** Directory holding snapshot JPEGs waiting for upload. */
    public static File snapshotsDir(Context ctx) {
        File dir = new File(ctx.getExternalFilesDir(null), "snapshots");
        if (!dir.exists()) dir.mkdirs();
        if (!dir.exists()) {
            dir = new File(ctx.getFilesDir(), "snapshots");
            dir.mkdirs();
        }
        return dir;
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

    private static String todayStamp() {
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date());
    }

    /** rec_<today>_*.m4a files currently in the segments directory. */
    private static int localSegmentsToday(Context ctx) {
        String prefix = "rec_" + todayStamp() + "_";
        File[] fs = segmentsDir(ctx).listFiles();
        int n = 0;
        if (fs != null) for (File f : fs) if (f.getName().startsWith(prefix)) n++;
        return n;
    }

    /**
     * Bookkeeping for the record-page subtitle ("segments recorded today"):
     * called by RecordingService each time a segment actually starts. On a
     * new day the counter seeds from the segment files already on disk for
     * today (the just-created current file included), then increments.
     */
    public static void noteSegmentStarted(Context ctx) {
        SharedPreferences p = get(ctx);
        String today = todayStamp();
        if (!today.equals(p.getString(KEY_SEG_TODAY_DATE, ""))) {
            p.edit().putString(KEY_SEG_TODAY_DATE, today)
                    .putInt(KEY_SEG_TODAY_COUNT, localSegmentsToday(ctx)).apply();
        } else {
            p.edit().putInt(KEY_SEG_TODAY_COUNT,
                    p.getInt(KEY_SEG_TODAY_COUNT, 0) + 1).apply();
        }
    }

    /** Segments started today (persisted counter; local files as fallback). */
    public static int segmentsStartedToday(Context ctx) {
        SharedPreferences p = get(ctx);
        if (todayStamp().equals(p.getString(KEY_SEG_TODAY_DATE, ""))) {
            return Math.max(p.getInt(KEY_SEG_TODAY_COUNT, 0), localSegmentsToday(ctx));
        }
        return localSegmentsToday(ctx);
    }

    /** A segment counts as uploaded once a "<name>.done" marker exists next to it. */
    public static File markerFor(File segment) {
        return new File(segment.getParentFile(), segment.getName() + ".done");
    }
}
