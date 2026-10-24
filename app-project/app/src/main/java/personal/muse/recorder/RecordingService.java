package personal.muse.recorder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Foreground service that records microphone audio in fixed-length segments
 * (default 60 minutes). Each finished segment is handed to {@link Uploader}.
 *
 * Format: MPEG-4 / AAC, mono, 16 kHz, 48 kbps -> about 21 MB per hour.
 * Recording state is exposed via static fields so MainActivity can poll it.
 */
public class RecordingService extends Service {
    private static final String TAG = "MuseRecordingService";
    private static final String CHANNEL_ID = "muse_recording";
    private static final int NOTIFICATION_ID = 1001;

    public static final String ACTION_START = "personal.muse.recorder.START";
    public static final String ACTION_STOP = "personal.muse.recorder.STOP";

    // Polled by MainActivity.
    public static volatile boolean isRecording = false;
    public static volatile long recordingSinceMs = 0;
    /** The segment currently being written; not yet uploadable. */
    public static volatile File currentSegmentFile = null;
    private static volatile MediaRecorder activeRecorder = null;

    /** Peak amplitude since the last call (for the live waveform); 0 when idle. */
    public static int pollMaxAmplitude() {
        MediaRecorder r = activeRecorder;
        if (r == null) return 0;
        try {
            return r.getMaxAmplitude();
        } catch (Exception e) {
            return 0;
        }
    }
    public static volatile long currentSegmentStartMs = 0;
    public static volatile String currentSegmentName = "";

    private HandlerThread thread;
    private Handler handler;
    private MediaRecorder recorder;
    private File currentFile;
    private PowerManager.WakeLock wakeLock;
    private final SimpleDateFormat fileStamp =
            new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US);

    public static void start(Context ctx) {
        Intent i = new Intent(ctx, RecordingService.class).setAction(ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(i);
        } else {
            ctx.startService(i);
        }
    }

    public static void stop(Context ctx) {
        Intent i = new Intent(ctx, RecordingService.class).setAction(ACTION_STOP);
        ctx.startService(i);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        thread = new HandlerThread("muse-recorder");
        thread.start();
        handler = new Handler(thread.getLooper());
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            stopSelf();
            return START_NOT_STICKY;
        }
        if (!isRecording) {
            startRecording();
        }
        return START_STICKY;
    }

    private void startRecording() {
        Notification n = buildNotification("正在准备录音…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        } else {
            startForeground(NOTIFICATION_ID, n);
        }
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "MuseRecorder:record");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        }
        isRecording = true;
        recordingSinceMs = System.currentTimeMillis();
        Prefs.get(this).edit().putBoolean(Prefs.KEY_WAS_RECORDING, true).apply();
        handler.post(this::startSegment);
        // Periodic upload attempts also catch segments that failed earlier.
        handler.post(uploadTicker);
    }

    private final Runnable uploadTicker = new Runnable() {
        @Override
        public void run() {
            Uploader.kick(RecordingService.this);
            if (isRecording) handler.postDelayed(this, 60 * 1000L);
        }
    };

    private final Runnable rotateTask = () -> {
        // Fires when the current segment reaches its configured length.
        finishSegment();
        if (isRecording) startSegment();
    };

    private void startSegment() {
        try {
            String name = "rec_" + fileStamp.format(new Date()) + ".m4a";
            currentFile = new File(Prefs.segmentsDir(this), name);
            currentSegmentFile = currentFile;
            currentSegmentName = name;
            currentSegmentStartMs = System.currentTimeMillis();

            recorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                    ? new MediaRecorder(this) : new MediaRecorder();
            recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            recorder.setAudioChannels(1);
            recorder.setAudioSamplingRate(16000);
            recorder.setAudioEncodingBitRate(48_000);
            recorder.setOutputFile(currentFile.getAbsolutePath());
            recorder.setOnErrorListener((mr, what, extra) -> {
                Log.e(TAG, "MediaRecorder error " + what + "/" + extra + "; restarting segment");
                handler.post(() -> {
                    finishSegment();
                    if (isRecording) startSegment();
                });
            });
            recorder.prepare();
            recorder.start();
            activeRecorder = recorder;
            updateNotification("正在录音：" + name);
            long segMs = Prefs.segmentMinutes(this) * 60_000L;
            handler.postDelayed(rotateTask, segMs);
        } catch (IOException | RuntimeException e) {
            Log.e(TAG, "Failed to start segment", e);
            updateNotification("录音启动失败：" + e.getMessage());
        }
    }

    private void finishSegment() {
        handler.removeCallbacks(rotateTask);
        if (recorder != null) {
            try {
                recorder.stop();
            } catch (RuntimeException e) {
                // stop() too early after start() throws; drop the stub file.
                Log.w(TAG, "Recorder stop failed; deleting stub", e);
                if (currentFile != null && currentFile.length() < 4096) {
                    //noinspection ResultOfMethodCallIgnored
                    currentFile.delete();
                    currentFile = null;
                }
            }
            try { recorder.release(); } catch (Exception ignored) {}
            recorder = null;
            activeRecorder = null;
        }
        if (currentFile != null && currentFile.exists() && currentFile.length() < 4096) {
            // A sub-4KB file at 48 kbps holds under a second of audio: it is a
            // header-only stub from a failed/stopped-too-early recorder, never
            // real content. Drop it instead of uploading a 40-byte husk.
            Log.w(TAG, "Dropping stub segment " + currentFile.getName()
                    + " (" + currentFile.length() + " bytes)");
            //noinspection ResultOfMethodCallIgnored
            currentFile.delete();
            currentFile = null;
        }
        if (currentFile != null && currentFile.exists() && currentFile.length() > 0) {
            Uploader.kick(this);
        }
        currentFile = null;
        currentSegmentFile = null;
    }

    private void stopRecording() {
        if (!isRecording) return;
        isRecording = false;
        Prefs.get(this).edit().putBoolean(Prefs.KEY_WAS_RECORDING, false).apply();
        handler.removeCallbacks(uploadTicker);
        finishSegment();
        Uploader.kick(this);
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
        stopForeground(STOP_FOREGROUND_REMOVE);
    }

    @Override
    public void onDestroy() {
        if (isRecording) stopRecording();
        if (thread != null) thread.quitSafely();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ---- notifications ----

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "录音状态", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("录音进行中的常驻通知");
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RecordingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(
                this, 1, stop, PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle("Muse 录音助手")
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentIntent(pi)
                .setOngoing(true)
                .addAction(0, "停止录音", stopPi)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification(text));
    }
}
