package personal.muse.recorder;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Uploads finished segments (and event-marker files) to the configured
 * S3-compatible bucket.
 *
 * Bucket layout:
 *   <yyyy-MM-dd>/rec_<yyyy-MM-dd_HH-mm-ss>.m4a   audio segments
 *   markers/events-<yyyy-MM-dd>.jsonl           event markers (re-PUT on change)
 *
 * Segments get a local "<name>.done" marker after a successful PUT (and are
 * deleted locally when that setting is on). Marker files are tiny and are
 * simply re-uploaded whenever their size changes.
 */
public final class Uploader {
    private static final String TAG = "MuseUploader";

    private static final ExecutorService EXEC = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "muse-uploader");
        t.setDaemon(true);
        return t;
    });
    private static final AtomicBoolean RUNNING = new AtomicBoolean(false);

    /** A file currently being written outside the service (a held self-note). */
    public static volatile File extraExcludedFile = null;

    // Live upload state, polled by MainActivity to render progress + result.
    public static volatile String currentFileName = "";
    public static volatile long currentSent = 0;
    public static volatile long currentTotal = 0;
    public static volatile int runTotal = 0;
    public static volatile int runDone = 0;
    public static volatile String lastResult = "";
    public static volatile long lastResultAt = 0;
    /** When uploads started failing continuously (0 = not failing). */
    public static volatile long failingSinceMs = 0;

    private static void markFail() {
        if (failingSinceMs == 0) failingSinceMs = System.currentTimeMillis();
    }

    private static void markOk() {
        failingSinceMs = 0;
    }

    public static boolean isRunning() {
        return RUNNING.get();
    }

    private static void setResult(String msg) {
        lastResult = msg;
        lastResultAt = System.currentTimeMillis();
    }

    /** Shows exactly what was addressed, so config mismatches are obvious. */
    private static String targetInfo(S3.Config cfg) {
        String host;
        try {
            host = new java.net.URL(cfg.endpoint).getHost();
        } catch (Exception e) {
            host = cfg.endpoint;
        }
        return "\n实际请求：桶「" + cfg.bucket + "」@ " + host
                + "（" + (cfg.virtualStyle ? "虚拟主机" : "路径") + "风格，区域 " + cfg.region + "）";
    }

    private static String trimErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.toString();
        return m.length() > 220 ? m.substring(0, 220) + "…" : m;
    }

    private Uploader() {}

    public static void kick(Context ctx) {
        final Context app = ctx.getApplicationContext();
        if (!RUNNING.compareAndSet(false, true)) return;
        EXEC.execute(() -> {
            try {
                uploadPending(app);
            } finally {
                RUNNING.set(false);
                currentFileName = "";
                currentSent = 0;
                currentTotal = 0;
            }
        });
    }

    public static int pendingCount(Context ctx) {
        return pendingFiles(ctx).length;
    }

    public static long pendingBytes(Context ctx) {
        long total = 0;
        for (File f : pendingFiles(ctx)) total += f.length();
        return total;
    }

    static File[] pendingFiles(Context ctx) {
        File dir = Prefs.segmentsDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.endsWith(".m4a"));
        if (files == null) return new File[0];
        File current = RecordingService.currentSegmentFile;
        String currentPath = current == null ? null : current.getAbsolutePath();
        File note = extraExcludedFile;
        String notePath = note == null ? null : note.getAbsolutePath();
        File[] pending = Arrays.stream(files)
                .filter(f -> currentPath == null || !f.getAbsolutePath().equals(currentPath))
                .filter(f -> notePath == null || !f.getAbsolutePath().equals(notePath))
                .filter(f -> {
                    File marker = Prefs.markerFor(f);
                    if (!marker.exists()) return true;
                    // Uploaded once — unless the segment kept growing after
                    // the marker was written (it was uploaded mid-recording
                    // by an older version); then the cloud copy is a stub
                    // and the full file must be re-uploaded over it.
                    return f.lastModified() > marker.lastModified();
                })
                .toArray(File[]::new);
        Arrays.sort(pending);
        return pending;
    }

    /** True when any events file differs from its last synced size. */
    public static boolean eventsPending(Context ctx) {
        File dir = Prefs.markersDir(ctx);
        File[] files = dir.listFiles((d, name) -> name.startsWith("events-") && name.endsWith(".jsonl"));
        if (files == null) return false;
        for (File f : files) {
            if (f.length() != syncedLength(f)) return true;
        }
        return false;
    }

    private static long syncedLength(File eventsFile) {
        File s = new File(eventsFile.getParentFile(), eventsFile.getName() + ".synced");
        if (!s.exists()) return -1;
        try (Scanner sc = new Scanner(new FileInputStream(s))) {
            return sc.hasNextLong() ? sc.nextLong() : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    private static void writeSyncedLength(File eventsFile, long len) {
        File s = new File(eventsFile.getParentFile(), eventsFile.getName() + ".synced");
        try (FileWriter w = new FileWriter(s)) {
            w.write(Long.toString(len));
        } catch (Exception ignored) {}
    }

    private static void uploadPending(Context app) {
        String rawEndpoint = Prefs.get(app).getString(Prefs.KEY_S3_ENDPOINT, "");
        if (rawEndpoint != null && (rawEndpoint.contains("<") || rawEndpoint.contains(">"))) {
            setResult("未上传：Endpoint 里还是占位符 <你的AccountID>，请换成你的真实 R2 Account ID");
            return;
        }
        if (!Prefs.s3Configured(app)) {
            Log.i(TAG, "S3 not fully configured; keeping files on device.");
            setResult("未上传：S3 设置还没填完整（点“配置”检查 Endpoint、桶名和两个 Key）");
            markFail();
            return;
        }
        if (Prefs.wifiOnly(app) && !isUnmetered(app)) {
            Log.i(TAG, "Wi-Fi-only is on and network is metered; postponing upload.");
            setResult("未上传：已开启“仅 Wi‑Fi 上传”，当前网络是计流量网络");
            markFail();
            return;
        }
        S3.Config cfg = Prefs.s3Config(app);
        File[] allPending = pendingFiles(app);
        int stubCount = 0;
        List<File> real = new ArrayList<>();
        for (File f : allPending) {
            if (f.length() < 4096) {
                // Header-only stub (under a second of audio): never upload.
                //noinspection ResultOfMethodCallIgnored
                f.delete();
                //noinspection ResultOfMethodCallIgnored
                Prefs.markerFor(f).delete();
                stubCount++;
            } else {
                real.add(f);
            }
        }
        File[] pending = real.toArray(new File[0]);
        boolean hadEvents = eventsPending(app);
        runTotal = pending.length;
        runDone = 0;
        if (pending.length == 0 && !hadEvents) {
            setResult(stubCount > 0
                    ? "没有有效的待上传片段（已清理 " + stubCount + " 个空片段）"
                    : "没有待上传的文件");
            markOk();
            return;
        }
        for (File f : pending) {
            String name = f.getName();
            String date = dateOf(name, f);
            currentFileName = name;
            currentSent = 0;
            currentTotal = f.length();
            try {
                S3.putObject(cfg, date + "/" + name, f, "audio/mp4",
                        (sent, total) -> {
                            currentSent = sent;
                            currentTotal = total;
                        });
                markDone(app, f);
                runDone++;
                Log.i(TAG, "Uploaded " + name);
            } catch (Exception e) {
                Log.w(TAG, "Upload failed, will retry later: " + name + " (" + e.getMessage() + ")");
                setResult("上传失败：" + name + " — " + trimErr(e) + targetInfo(cfg));
                markFail();
                return; // stop this pass; a dead endpoint should not be hammered
            }
        }
        if (!syncEvents(app, cfg) && hadEvents) {
            setResult("录音已传完，但事件标记同步失败，下次会重试（已成功 " + runDone + " 段）");
            markFail();
            return;
        }
        setResult("上传完成 ✓ 成功 " + runDone + " 段" + (hadEvents ? "，事件标记已同步" : "")
                + (stubCount > 0 ? "，已清理空片段 " + stubCount + " 个" : ""));
        markOk();
    }

    /** Bucket folder for a segment: the date in its name (rec_/note_), else its mtime day. */
    private static String dateOf(String name, File f) {
        if (name.startsWith("rec_") && name.length() > 14) return name.substring(4, 14);
        if (name.startsWith("note_") && name.length() > 15) return name.substring(5, 15);
        return new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
                .format(new java.util.Date(f.lastModified()));
    }

    /** Returns false when an events file failed to sync. */
    private static boolean syncEvents(Context app, S3.Config cfg) {
        File dir = Prefs.markersDir(app);
        File[] files = dir.listFiles((d, name) -> name.startsWith("events-") && name.endsWith(".jsonl"));
        if (files == null) return true;
        Arrays.sort(files);
        for (File f : files) {
            if (f.length() == syncedLength(f)) continue;
            currentFileName = f.getName() + "（事件标记）";
            currentSent = 0;
            currentTotal = f.length();
            try {
                S3.putObject(cfg, "markers/" + f.getName(), f, "application/x-ndjson",
                        (sent, total) -> {
                            currentSent = sent;
                            currentTotal = total;
                        });
                writeSyncedLength(f, f.length());
                Log.i(TAG, "Synced " + f.getName());
            } catch (Exception e) {
                Log.w(TAG, "Event sync failed for " + f.getName() + " (" + e.getMessage() + ")");
                return false;
            }
        }
        return true;
    }

    private static void markDone(Context app, File f) {
        try {
            File marker = Prefs.markerFor(f);
            if (!marker.exists()) {
                if (!marker.createNewFile()) {
                    Log.w(TAG, "Could not create marker for " + f.getName());
                }
            } else if (!marker.setLastModified(System.currentTimeMillis())) {
                // A re-uploaded file keeps its old marker; without a fresh
                // timestamp the pending check would keep treating the file
                // as "grew after upload" and list it as pending forever.
                Log.w(TAG, "Could not refresh marker time for " + f.getName());
            }
        } catch (Exception e) {
            Log.w(TAG, "Marker error for " + f.getName(), e);
        }
        if (Prefs.deleteAfterUpload(app)) {
            if (!f.delete()) Log.w(TAG, "Could not delete uploaded file " + f.getName());
        }
    }

    private static boolean isUnmetered(Context ctx) {
        ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        Network net = cm.getActiveNetwork();
        if (net == null) return false;
        NetworkCapabilities caps = cm.getNetworkCapabilities(net);
        return caps != null && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED);
    }
}
