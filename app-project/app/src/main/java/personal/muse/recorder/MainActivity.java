package personal.muse.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final int REQ_PERMS = 42;

    // Presets: name, endpoint, region, virtualStyle
    private static final String[][] PRESETS = {
        {"Cloudflare R2", "https://<你的AccountID>.r2.cloudflarestorage.com", "auto", "path"},
        {"Backblaze B2 · 美西", "https://s3.us-west-004.backblazeb2.com", "us-west-004", "path"},
        {"Backblaze B2 · 美东", "https://s3.us-east-005.backblazeb2.com", "us-east-005", "path"},
        {"Backblaze B2 · 欧洲", "https://s3.eu-central-003.backblazeb2.com", "eu-central-003", "path"},
        {"阿里云 OSS · 杭州（S3兼容）", "https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "virtual"},
        {"阿里云 OSS · 上海（S3兼容）", "https://oss-cn-shanghai.aliyuncs.com", "cn-shanghai", "virtual"},
        {"阿里云 OSS · 北京（S3兼容）", "https://oss-cn-beijing.aliyuncs.com", "cn-beijing", "virtual"},
        {"阿里云 OSS · 深圳（S3兼容）", "https://oss-cn-shenzhen.aliyuncs.com", "cn-shenzhen", "virtual"},
        {"腾讯云 COS · 广州（S3兼容）", "https://cos.ap-guangzhou.myqcloud.com", "ap-guangzhou", "virtual"},
        {"腾讯云 COS · 上海（S3兼容）", "https://cos.ap-shanghai.myqcloud.com", "ap-shanghai", "virtual"},
        {"腾讯云 COS · 北京（S3兼容）", "https://cos.ap-beijing.myqcloud.com", "ap-beijing", "virtual"},
        {"AWS S3 · 美东", "https://s3.us-east-1.amazonaws.com", "us-east-1", "virtual"},
        {"AWS S3 · 新加坡", "https://s3.ap-southeast-1.amazonaws.com", "ap-southeast-1", "virtual"},
        {"七牛云 Kodo · 华东（S3兼容）", "https://s3.cn-east-1.qiniucs.com", "cn-east-1", "virtual"},
        {"自定义", "", "", "path"},
    };

    private RecordButtonView recordButton;
    private WaveformView waveform;
    private TextView statusText, lastEventText, filesTitle, uploadStatusText;
    private LinearLayout convCard, meetingCard, pendingContainer;
    private TextView convCardTitle, convCardState, meetingCardTitle, meetingCardState;
    private Button selfNoteButton, retryUploadButton, settingsToggleButton;
    private ProgressBar uploadProgress;
    private View settingsContainer;
    private Spinner presetSpinner;
    private EditText endpointInput, regionInput, bucketInput, accessKeyInput, secretKeyInput;
    private EditText segmentMinutesInput;
    private CheckBox virtualStyleCheck, wifiOnlyCheck, deleteAfterUploadCheck, autoResumeCheck;
    private boolean spinnerTouched = false;
    private long lastConfigSavedAt = 0;
    // Press-and-hold self-note state
    private MediaRecorder noteRecorder = null;
    private File noteFile = null;
    private boolean noteActive = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final Runnable refresh = new Runnable() {
        @Override
        public void run() {
            try {
                refreshStatus();
            } catch (Exception e) {
                // Never let one bad tick kill the refresh loop; a dead loop
                // shows up as a UI that only fixes itself on rotation.
                android.util.Log.w("MainActivity", "refreshStatus failed", e);
            }
            ui.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.recordButton);
        waveform = findViewById(R.id.waveform);
        statusText = findViewById(R.id.statusText);
        lastEventText = findViewById(R.id.lastEventText);
        filesTitle = findViewById(R.id.filesTitle);
        uploadStatusText = findViewById(R.id.uploadStatusText);
        convCard = findViewById(R.id.convCard);
        meetingCard = findViewById(R.id.meetingCard);
        convCardTitle = findViewById(R.id.convCardTitle);
        convCardState = findViewById(R.id.convCardState);
        meetingCardTitle = findViewById(R.id.meetingCardTitle);
        meetingCardState = findViewById(R.id.meetingCardState);
        selfNoteButton = findViewById(R.id.selfNoteButton);
        retryUploadButton = findViewById(R.id.retryUploadButton);
        pendingContainer = findViewById(R.id.pendingContainer);
        uploadProgress = findViewById(R.id.uploadProgress);
        presetSpinner = findViewById(R.id.presetSpinner);
        endpointInput = findViewById(R.id.endpointInput);
        regionInput = findViewById(R.id.regionInput);
        bucketInput = findViewById(R.id.bucketInput);
        accessKeyInput = findViewById(R.id.accessKeyInput);
        secretKeyInput = findViewById(R.id.secretKeyInput);
        virtualStyleCheck = findViewById(R.id.virtualStyleCheck);
        segmentMinutesInput = findViewById(R.id.segmentMinutesInput);
        wifiOnlyCheck = findViewById(R.id.wifiOnlyCheck);
        deleteAfterUploadCheck = findViewById(R.id.deleteAfterUploadCheck);
        autoResumeCheck = findViewById(R.id.autoResumeCheck);
        settingsContainer = findViewById(R.id.settingsContainer);
        settingsToggleButton = findViewById(R.id.settingsToggleButton);

        selfNoteButton.setBackground(roundedBg(0xFF1C1C1E, 12));
        selfNoteButton.setTextColor(Color.WHITE);
        selfNoteButton.setAllCaps(false);

        settingsToggleButton.setOnClickListener(v -> {
            boolean show = settingsContainer.getVisibility() != View.VISIBLE;
            settingsContainer.setVisibility(show ? View.VISIBLE : View.GONE);
            settingsToggleButton.setText(show ? "收起配置" : "⚙️ 配置（S3 与录音设置）");
        });

        List<String> presetNames = new ArrayList<>();
        for (String[] p : PRESETS) presetNames.add(p[0]);
        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, presetNames);
        presetSpinner.setAdapter(presetAdapter);
        presetSpinner.setOnTouchListener((v, e) -> {
            spinnerTouched = true;
            return false;
        });
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!spinnerTouched) return;
                String[] p = PRESETS[pos];
                if ("自定义".equals(p[0])) return;
                endpointInput.setText(p[1]);
                regionInput.setText(p[2]);
                virtualStyleCheck.setChecked("virtual".equals(p[3]));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadSettings();

        recordButton.setOnClickListener(v -> {
            if (RecordingService.isRecording) {
                RecordingService.stop(this);
                toast("已停止");
            } else if (ensurePermissions()) {
                RecordingService.start(this);
            }
            ui.postDelayed(this::refreshStatus, 300);
        });

        convCard.setOnClickListener(v -> toggleEvent(true));
        meetingCard.setOnClickListener(v -> toggleEvent(false));

        selfNoteButton.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    v.setPressed(true);
                    startSelfNote();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.setPressed(false);
                    stopSelfNote();
                    return true;
                default:
                    return true;
            }
        });

        retryUploadButton.setOnClickListener(v -> {
            Uploader.kick(this);
            toast("开始重试上传…");
            refreshStatus();
        });

        findViewById(R.id.saveSettingsButton).setOnClickListener(v -> {
            saveSettings();
            lastConfigSavedAt = System.currentTimeMillis();
            Uploader.kick(this);
            toast("设置已保存，开始上传");
            refreshStatus();
        });

        findViewById(R.id.batteryButton).setOnClickListener(v -> openBatterySettings());
    }

    private void toggleEvent(boolean conversation) {
        SharedPreferences p = Prefs.get(this);
        String activeKey = conversation ? Prefs.KEY_CONV_ACTIVE : Prefs.KEY_MEETING_ACTIVE;
        String sinceKey = conversation ? Prefs.KEY_CONV_SINCE : Prefs.KEY_MEETING_SINCE;
        boolean now = !p.getBoolean(activeKey, false);
        EventLog.add(this, conversation
                ? (now ? EventLog.CONVERSATION_START : EventLog.CONVERSATION_END)
                : (now ? EventLog.MEETING_START : EventLog.MEETING_END));
        p.edit().putBoolean(activeKey, now)
                .putLong(sinceKey, now ? System.currentTimeMillis() : 0)
                .apply();
        onEventMarked(conversation
                ? (now ? "对话开始" : "对话结束")
                : (now ? "会议开始" : "会议结束"));
        refreshStatus();
    }

    /**
     * Press-and-hold self-note. While the main recording runs, the audio is
     * already being captured, so a hold just brackets it with start/end
     * markers. When idle, a dedicated note segment is recorded for the
     * duration of the hold and uploaded like any other segment.
     */
    private void startSelfNote() {
        if (noteActive) return;
        if (!RecordingService.isRecording && !ensurePermissions()) return;
        EventLog.add(this, EventLog.SELF_NOTE_START);
        if (!RecordingService.isRecording) {
            try {
                String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(new Date());
                noteFile = new File(Prefs.segmentsDir(this), "note_" + stamp + ".m4a");
                noteRecorder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                        ? new MediaRecorder(this) : new MediaRecorder();
                noteRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
                noteRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
                noteRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
                noteRecorder.setAudioChannels(1);
                noteRecorder.setAudioSamplingRate(16000);
                noteRecorder.setAudioEncodingBitRate(48_000);
                noteRecorder.setOutputFile(noteFile.getAbsolutePath());
                noteRecorder.prepare();
                noteRecorder.start();
                Uploader.extraExcludedFile = noteFile;
                WaveformView.amplitudeOverride = () -> {
                    try {
                        return noteRecorder != null ? noteRecorder.getMaxAmplitude() : 0;
                    } catch (Exception e) {
                        return 0;
                    }
                };
            } catch (IOException | RuntimeException e) {
                noteRecorder = null;
                noteFile = null;
                toast("自述录音启动失败");
                return;
            }
        }
        noteActive = true;
        vibrateTick();
        selfNoteButton.setBackground(roundedBg(0xFFFF3B30, 12));
        selfNoteButton.setText("正在自述…松开结束");
        refreshStatus();
    }

    private void stopSelfNote() {
        if (!noteActive) return;
        noteActive = false;
        EventLog.add(this, EventLog.SELF_NOTE_END);
        onEventMarked("自述录音");
        if (noteRecorder != null) {
            boolean kept = false;
            try {
                noteRecorder.stop();
                kept = noteFile != null && noteFile.length() >= 2048;
            } catch (RuntimeException e) {
                kept = false; // stopped too early; stub
            }
            try { noteRecorder.release(); } catch (Exception ignored) {}
            noteRecorder = null;
            WaveformView.amplitudeOverride = null;
            Uploader.extraExcludedFile = null;
            if (noteFile != null) {
                if (kept) {
                    toast("自述已保存，会自动上传");
                    Uploader.kick(this);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    noteFile.delete();
                    toast("太短了，没保存");
                }
                noteFile = null;
            }
        }
        selfNoteButton.setBackground(roundedBg(0xFF1C1C1E, 12));
        selfNoteButton.setText("🎙 自述录音 · 按住说话，松开结束");
        refreshStatus();
    }

    private void vibrateTick() {
        try {
            Vibrator v = (Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(30, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    //noinspection deprecation
                    v.vibrate(30);
                }
            }
        } catch (Exception ignored) {}
    }

    private void onEventMarked(String label) {
        String time = new SimpleDateFormat("HH:mm:ss", Locale.US).format(new Date());
        lastEventText.setText("最近标记：" + label + " · " + time);
        toast("已标记：" + label + " " + time);
        Uploader.kick(this);
    }

    @Override
    protected void onResume() {
        super.onResume();
        Uploader.kick(this);
        refresh.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopSelfNote();
        ui.removeCallbacks(refresh);
        waveform.stopAnimating();
    }

    private void loadSettings() {
        SharedPreferences p = Prefs.get(this);
        String savedPreset = p.getString(Prefs.KEY_S3_PRESET, PRESETS[0][0]);
        for (int i = 0; i < PRESETS.length; i++) {
            if (PRESETS[i][0].equals(savedPreset)) {
                presetSpinner.setSelection(i);
                break;
            }
        }
        endpointInput.setText(p.getString(Prefs.KEY_S3_ENDPOINT, PRESETS[0][1]));
        regionInput.setText(p.getString(Prefs.KEY_S3_REGION, PRESETS[0][2]));
        bucketInput.setText(p.getString(Prefs.KEY_S3_BUCKET, ""));
        accessKeyInput.setText(p.getString(Prefs.KEY_S3_ACCESS_KEY, ""));
        secretKeyInput.setText(p.getString(Prefs.KEY_S3_SECRET_KEY, ""));
        virtualStyleCheck.setChecked(p.getBoolean(Prefs.KEY_S3_VIRTUAL, false));
        segmentMinutesInput.setText(String.valueOf(p.getInt(Prefs.KEY_SEGMENT_MINUTES, 60)));
        wifiOnlyCheck.setChecked(p.getBoolean(Prefs.KEY_WIFI_ONLY, false));
        deleteAfterUploadCheck.setChecked(p.getBoolean(Prefs.KEY_DELETE_AFTER_UPLOAD, true));
        autoResumeCheck.setChecked(p.getBoolean(Prefs.KEY_AUTO_RESUME, false));
    }

    private void saveSettings() {
        int minutes = 60;
        try {
            minutes = Integer.parseInt(segmentMinutesInput.getText().toString().trim());
        } catch (NumberFormatException ignored) {}
        Prefs.get(this).edit()
                .putString(Prefs.KEY_S3_PRESET, String.valueOf(presetSpinner.getSelectedItem()))
                .putString(Prefs.KEY_S3_ENDPOINT, endpointInput.getText().toString().trim())
                .putString(Prefs.KEY_S3_REGION, regionInput.getText().toString().trim())
                .putString(Prefs.KEY_S3_BUCKET, bucketInput.getText().toString().trim())
                .putString(Prefs.KEY_S3_ACCESS_KEY, accessKeyInput.getText().toString().trim())
                .putString(Prefs.KEY_S3_SECRET_KEY, secretKeyInput.getText().toString().trim())
                .putBoolean(Prefs.KEY_S3_VIRTUAL, virtualStyleCheck.isChecked())
                .putInt(Prefs.KEY_SEGMENT_MINUTES, minutes)
                .putBoolean(Prefs.KEY_WIFI_ONLY, wifiOnlyCheck.isChecked())
                .putBoolean(Prefs.KEY_DELETE_AFTER_UPLOAD, deleteAfterUploadCheck.isChecked())
                .putBoolean(Prefs.KEY_AUTO_RESUME, autoResumeCheck.isChecked())
                .apply();
    }

    private boolean ensurePermissions() {
        boolean micOk = checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
        boolean notifOk = Build.VERSION.SDK_INT < 33
                || checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
        if (micOk && notifOk) return true;
        if (Build.VERSION.SDK_INT >= 33) {
            requestPermissions(new String[]{
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS}, REQ_PERMS);
        } else {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_PERMS);
        }
        toast("请先允许麦克风权限，然后再点一次按钮");
        return false;
    }

    private void openBatterySettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast("已经允许后台运行了");
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast("打不开电池设置，请到系统设置里手动允许本应用后台运行");
        }
    }

    private GradientDrawable ovalBg(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    private GradientDrawable roundedBg(int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(color);
        d.setCornerRadius(radiusDp * getResources().getDisplayMetrics().density);
        return d;
    }

    private void styleEventCard(LinearLayout card, TextView title, TextView state,
                                boolean active, int activeColor, long sinceMs) {
        if (active) {
            card.setBackground(roundedBg(activeColor, 14));
            title.setTextColor(Color.WHITE);
            long mins = sinceMs > 0 ? (System.currentTimeMillis() - sinceMs) / 60000 : 0;
            state.setText("进行中 · 已 " + mins + " 分钟，点一下结束");
            state.setTextColor(0xE6FFFFFF);
        } else {
            GradientDrawable d = roundedBg(Color.WHITE, 14);
            d.setStroke((int) (1 * getResources().getDisplayMetrics().density), 0xFFE0E0E6);
            card.setBackground(d);
            title.setTextColor(0xFF1C1C1E);
            state.setText("点一下开始");
            state.setTextColor(0xFF8E8E93);
        }
    }

    private void refreshStatus() {
        SharedPreferences prefs = Prefs.get(this);
        boolean recording = RecordingService.isRecording;

        recordButton.setRecording(recording);
        if (recording || noteActive) {
            waveform.setVisibility(View.VISIBLE);
            waveform.startAnimating();
        } else {
            // INVISIBLE, not GONE: the space stays reserved so starting or
            // stopping a recording never shifts the layout.
            waveform.setVisibility(View.INVISIBLE);
            waveform.stopAnimating();
        }
        if (recording) {
            long now = System.currentTimeMillis();
            long totalMins = (now - RecordingService.recordingSinceMs) / 60000;
            long segMs = Prefs.segmentMinutes(this) * 60_000L;
            long segElapsed = RecordingService.currentSegmentStartMs > 0
                    ? now - RecordingService.currentSegmentStartMs : 0;
            long remainMin = Math.max(0, (long) Math.ceil((segMs - segElapsed) / 60000.0));
            statusText.setText("剩余 " + remainMin + " 分钟 · 已录 " + totalMins + " 分钟");
        } else if (noteActive) {
            statusText.setText("自述录音中…松开按钮结束");
        } else {
            statusText.setText(Prefs.s3Configured(this)
                    ? "点红色按钮开始录音"
                    : "点红色按钮开始录音（S3 还没配置好，录音会先存手机里）");
        }

        styleEventCard(convCard, convCardTitle, convCardState,
                prefs.getBoolean(Prefs.KEY_CONV_ACTIVE, false), 0xFF007AFF,
                prefs.getLong(Prefs.KEY_CONV_SINCE, 0));
        styleEventCard(meetingCard, meetingCardTitle, meetingCardState,
                prefs.getBoolean(Prefs.KEY_MEETING_ACTIVE, false), 0xFF5856D6,
                prefs.getLong(Prefs.KEY_MEETING_SINCE, 0));

        // File list: the in-progress segment first, then pending uploads.
        File[] pending = Uploader.pendingFiles(this);
        long bytes = 0;
        List<String> rows = new ArrayList<>();
        List<Boolean> rowIsRecording = new ArrayList<>();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        File cur = RecordingService.currentSegmentFile;
        if (recording && cur != null && cur.exists()) {
            String name = cur.getName();
            String label = name.length() > 15 ? name.substring(4, 14) + " " + hm.format(new Date(cur.lastModified())) : name;
            rows.add("🔴 " + label + " · " + String.format(Locale.US, "%.1f MB", cur.length() / 1_000_000.0) + " · 录音中");
            rowIsRecording.add(true);
        }
        for (File f : pending) {
            bytes += f.length();
            String name = f.getName();
            boolean isNote = name.startsWith("note_");
            String datePart = "";
            if (name.startsWith("rec_") && name.length() > 14) datePart = name.substring(4, 14);
            else if (isNote && name.length() > 15) datePart = name.substring(5, 15);
            String label = datePart.isEmpty() ? name : datePart + " " + hm.format(new Date(f.lastModified()));
            rows.add(label + (isNote ? " 的自述 · " : " 的片段 · ")
                    + String.format(Locale.US, "%.1f MB", f.length() / 1_000_000.0) + " · 待上传");
            rowIsRecording.add(false);
        }
        if (rows.isEmpty()) rows.add(Prefs.s3Configured(this) ? "没有待上传片段 ✓" : "还没有录音文件");
        if (Uploader.eventsPending(this)) rows.add("事件标记 · 待同步");
        while (rowIsRecording.size() < rows.size()) rowIsRecording.add(false);
        // Rebuild every tick: the list is short, and caching by content
        // signature proved to be a staleness trap.
        pendingContainer.removeAllViews();
        for (int i = 0; i < rows.size(); i++) {
            TextView tv = new TextView(this);
            tv.setText(rows.get(i));
            tv.setTextSize(14);
            if (rowIsRecording.get(i)) tv.setTextColor(0xFFFF3B30);
            tv.setPadding(0, 6, 0, 6);
            pendingContainer.addView(tv);
        }
        filesTitle.setText(pending.length > 0
                ? String.format(Locale.US, "录音文件（待上传 %d 段 · %.1f MB）", pending.length, bytes / 1_000_000.0)
                : "录音文件");

        long nowMs = System.currentTimeMillis();
        boolean failingLong = Uploader.failingSinceMs > 0
                && nowMs - Uploader.failingSinceMs >= 30 * 60_000L;
        if (Uploader.isRunning()) {
            long total = Uploader.currentTotal;
            int pct = total > 0 ? (int) (Uploader.currentSent * 100 / total) : 0;
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setProgress(pct);
            uploadStatusText.setText("正在上传 第 " + Math.min(Uploader.runDone + 1, Math.max(Uploader.runTotal, 1))
                    + "/" + Math.max(Uploader.runTotal, 1) + " 个：" + Uploader.currentFileName
                    + " · " + pct + "%");
        } else {
            uploadProgress.setVisibility(View.GONE);
            String text = "";
            if (!Uploader.lastResult.isEmpty()) {
                String when = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(Uploader.lastResultAt));
                text = "上次上传（" + when + "）：" + Uploader.lastResult;
            }
            if (failingLong) {
                text = "⚠️ 上传已持续失败超过 30 分钟，请检查配置与网络\n" + text;
            }
            uploadStatusText.setText(text);
        }
        boolean configJustSaved = lastConfigSavedAt > Uploader.lastResultAt;
        retryUploadButton.setVisibility(
                (!Uploader.isRunning() && (configJustSaved || failingLong) && pending.length > 0)
                        ? View.VISIBLE : View.GONE);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
