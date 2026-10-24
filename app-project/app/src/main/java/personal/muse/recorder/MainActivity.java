package personal.muse.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
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
import android.view.Menu;
import android.view.MenuItem;
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

    // Presets: endpoint, region, virtualStyle. Display names live in the
    // preset_names string-array so they follow the app language.
    private static final String[][] PRESETS = {
        {"https://<AccountID>.r2.cloudflarestorage.com", "auto", "path"},
        {"https://s3.us-west-004.backblazeb2.com", "us-west-004", "path"},
        {"https://s3.us-east-005.backblazeb2.com", "us-east-005", "path"},
        {"https://s3.eu-central-003.backblazeb2.com", "eu-central-003", "path"},
        {"https://oss-cn-hangzhou.aliyuncs.com", "cn-hangzhou", "virtual"},
        {"https://oss-cn-shanghai.aliyuncs.com", "cn-shanghai", "virtual"},
        {"https://oss-cn-beijing.aliyuncs.com", "cn-beijing", "virtual"},
        {"https://oss-cn-shenzhen.aliyuncs.com", "cn-shenzhen", "virtual"},
        {"https://cos.ap-guangzhou.myqcloud.com", "ap-guangzhou", "virtual"},
        {"https://cos.ap-shanghai.myqcloud.com", "ap-shanghai", "virtual"},
        {"https://cos.ap-beijing.myqcloud.com", "ap-beijing", "virtual"},
        {"https://s3.us-east-1.amazonaws.com", "us-east-1", "virtual"},
        {"https://s3.ap-southeast-1.amazonaws.com", "ap-southeast-1", "virtual"},
        {"https://s3.cn-east-1.qiniucs.com", "cn-east-1", "virtual"},
        {"", "", "path"},
    };

    // Pre-localization preset names (always Chinese): old installs saved the
    // preset by display name, so loadSettings still recognizes those values.
    private static final String[] LEGACY_PRESET_NAMES = {
        "Cloudflare R2", "Backblaze B2 · 美西", "Backblaze B2 · 美东", "Backblaze B2 · 欧洲",
        "阿里云 OSS · 杭州（S3兼容）", "阿里云 OSS · 上海（S3兼容）", "阿里云 OSS · 北京（S3兼容）",
        "阿里云 OSS · 深圳（S3兼容）", "腾讯云 COS · 广州（S3兼容）", "腾讯云 COS · 上海（S3兼容）",
        "腾讯云 COS · 北京（S3兼容）", "AWS S3 · 美东", "AWS S3 · 新加坡",
        "七牛云 Kodo · 华东（S3兼容）", "自定义",
    };

    private RecordButtonView recordButton;
    private WaveformView waveform;
    private TextView statusText, lastEventText, filesTitle, uploadStatusText;
    private SegmentProgressView segmentProgress;
    private LinearLayout convCard, meetingCard, pendingContainer;
    private TextView convCardTitle, convCardState, meetingCardTitle, meetingCardState;
    private Button selfNoteButton, retryUploadButton, settingsToggleButton;
    private ProgressBar uploadProgress;
    private View settingsContainer;
    private Spinner presetSpinner, languageSpinner;
    private EditText endpointInput, regionInput, bucketInput, accessKeyInput, secretKeyInput;
    private EditText segmentMinutesInput;
    private CheckBox virtualStyleCheck, wifiOnlyCheck, deleteAfterUploadCheck, autoResumeCheck;
    private boolean spinnerTouched = false;
    private boolean languageTouched = false;
    private long lastConfigSavedAt = 0;
    // Press-and-hold self-note state
    private MediaRecorder noteRecorder = null;
    private File noteFile = null;
    private boolean noteActive = false;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private long lastIdleKickMs = 0;
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
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(LocaleHelper.wrap(newBase));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        recordButton = findViewById(R.id.recordButton);
        waveform = findViewById(R.id.waveform);
        statusText = findViewById(R.id.statusText);
        segmentProgress = findViewById(R.id.segmentProgress);
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
        languageSpinner = findViewById(R.id.languageSpinner);
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
            settingsToggleButton.setText(show
                    ? getString(R.string.settings_collapse)
                    : getString(R.string.settings_button));
        });

        ArrayAdapter<String> presetAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.preset_names));
        presetSpinner.setAdapter(presetAdapter);
        presetSpinner.setOnTouchListener((v, e) -> {
            spinnerTouched = true;
            return false;
        });
        presetSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!spinnerTouched) return;
                if (pos == PRESETS.length - 1) return; // Custom: leave the fields alone
                String[] p = PRESETS[pos];
                endpointInput.setText(p[0]);
                regionInput.setText(p[1]);
                virtualStyleCheck.setChecked("virtual".equals(p[2]));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        ArrayAdapter<String> languageAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.language_names));
        languageSpinner.setAdapter(languageAdapter);
        languageSpinner.setOnTouchListener((v, e) -> {
            languageTouched = true;
            return false;
        });
        languageSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!languageTouched) return;
                String code = getResources().getStringArray(R.array.language_codes)[pos];
                if (code.equals(LocaleHelper.savedCode(MainActivity.this))) return;
                Prefs.get(MainActivity.this).edit()
                        .putString(Prefs.KEY_APP_LANGUAGE, code).apply();
                recreate();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        loadSettings();

        recordButton.setOnClickListener(v -> {
            if (RecordingService.isRecording) {
                RecordingService.stop(this);
                toast(getString(R.string.toast_stopped));
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
            toast(getString(R.string.toast_retry_started));
            refreshStatus();
        });

        findViewById(R.id.saveSettingsButton).setOnClickListener(v -> {
            saveSettings();
            lastConfigSavedAt = System.currentTimeMillis();
            Uploader.kick(this);
            toast(getString(R.string.toast_settings_saved));
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
        onEventMarked(getString(conversation
                ? (now ? R.string.event_conv_start : R.string.event_conv_end)
                : (now ? R.string.event_meeting_start : R.string.event_meeting_end)));
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
                toast(getString(R.string.toast_note_start_failed));
                return;
            }
        }
        noteActive = true;
        vibrateTick();
        selfNoteButton.setBackground(roundedBg(0xFFFF3B30, 12));
        selfNoteButton.setText(R.string.self_note_active);
        refreshStatus();
    }

    private void stopSelfNote() {
        if (!noteActive) return;
        noteActive = false;
        EventLog.add(this, EventLog.SELF_NOTE_END);
        onEventMarked(getString(R.string.event_self_note));
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
                    toast(getString(R.string.toast_note_saved));
                    Uploader.kick(this);
                } else {
                    //noinspection ResultOfMethodCallIgnored
                    noteFile.delete();
                    toast(getString(R.string.toast_note_too_short));
                }
                noteFile = null;
            }
        }
        selfNoteButton.setBackground(roundedBg(0xFF1C1C1E, 12));
        selfNoteButton.setText(R.string.self_note_button);
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
        lastEventText.setText(getString(R.string.last_event, label, time));
        toast(getString(R.string.marked_toast, label, time));
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

    // Help button on the system title bar (ActionBar): the bar already shows
    // the left-aligned "Muse Daily Recorder" title from the app label, so
    // the ⓘ item sits on that same bar instead of floating in the content.
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.main_menu, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_help) {
            startActivity(new Intent(this, HelpActivity.class));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSettings() {
        SharedPreferences p = Prefs.get(this);
        // Language: position of the saved code in language_codes.
        String savedLang = LocaleHelper.savedCode(this);
        String[] codes = getResources().getStringArray(R.array.language_codes);
        for (int i = 0; i < codes.length; i++) {
            if (codes[i].equals(savedLang)) {
                languageSpinner.setSelection(i);
                break;
            }
        }
        // Preset: new installs save the index; old installs saved the
        // display name (always Chinese), so both are recognized.
        String savedPreset = p.getString(Prefs.KEY_S3_PRESET, "0");
        try {
            int idx = Integer.parseInt(savedPreset);
            if (idx >= 0 && idx < PRESETS.length) presetSpinner.setSelection(idx);
        } catch (NumberFormatException e) {
            String[] names = getResources().getStringArray(R.array.preset_names);
            for (int i = 0; i < PRESETS.length; i++) {
                if (names[i].equals(savedPreset) || LEGACY_PRESET_NAMES[i].equals(savedPreset)) {
                    presetSpinner.setSelection(i);
                    break;
                }
            }
        }
        endpointInput.setText(p.getString(Prefs.KEY_S3_ENDPOINT, PRESETS[0][0]));
        regionInput.setText(p.getString(Prefs.KEY_S3_REGION, PRESETS[0][1]));
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
                .putString(Prefs.KEY_S3_PRESET, String.valueOf(presetSpinner.getSelectedItemPosition()))
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
        toast(getString(R.string.toast_mic_permission));
        return false;
    }

    private void openBatterySettings() {
        try {
            PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
            if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
                toast(getString(R.string.toast_battery_already));
                return;
            }
            Intent i = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (Exception e) {
            toast(getString(R.string.toast_battery_open_failed));
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
            state.setText(getString(R.string.card_active, mins));
            state.setTextColor(0xE6FFFFFF);
        } else {
            GradientDrawable d = roundedBg(Color.WHITE, 14);
            d.setStroke((int) (1 * getResources().getDisplayMetrics().density), 0xFFE0E0E6);
            card.setBackground(d);
            title.setTextColor(0xFF1C1C1E);
            state.setText(R.string.tap_to_start);
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
            // The minutes line becomes a full-width progress bar: elapsed /
            // segment seconds overlaid on it (e.g. 120/600s, 120/600秒 in zh).
            long now = System.currentTimeMillis();
            long segMs = Prefs.segmentMinutes(this) * 60_000L;
            long segElapsed = RecordingService.currentSegmentStartMs > 0
                    ? now - RecordingService.currentSegmentStartMs : 0;
            statusText.setVisibility(View.GONE);
            segmentProgress.setVisibility(View.VISIBLE);
            segmentProgress.setProgress(segElapsed / 1000, segMs / 1000,
                    getString(R.string.seconds_short));
        } else if (noteActive) {
            statusText.setVisibility(View.VISIBLE);
            segmentProgress.setVisibility(View.GONE);
            statusText.setText(R.string.self_note_recording);
        } else {
            statusText.setVisibility(View.VISIBLE);
            segmentProgress.setVisibility(View.GONE);
            statusText.setText(Prefs.s3Configured(this)
                    ? getString(R.string.status_tap_record)
                    : getString(R.string.status_tap_record_no_s3));
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
            rows.add(getString(R.string.row_recording, label,
                    String.format(Locale.US, "%.1f", cur.length() / 1_000_000.0)));
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
            rows.add(getString(isNote ? R.string.row_note : R.string.row_segment, label,
                    String.format(Locale.US, "%.1f", f.length() / 1_000_000.0)));
            rowIsRecording.add(false);
        }
        if (rows.isEmpty()) rows.add(Prefs.s3Configured(this)
                ? getString(R.string.no_pending_ok) : getString(R.string.no_files_yet));
        if (Uploader.eventsPending(this)) rows.add(getString(R.string.events_pending_row));
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
                ? getString(R.string.files_title_pending, pending.length,
                        String.format(Locale.US, "%.1f", bytes / 1_000_000.0))
                : getString(R.string.files_title));

        long nowMs = System.currentTimeMillis();
        boolean failingLong = Uploader.failingSinceMs > 0
                && nowMs - Uploader.failingSinceMs >= 30 * 60_000L;
        if (Uploader.isRunning()) {
            long total = Uploader.currentTotal;
            int pct = total > 0 ? (int) (Uploader.currentSent * 100 / total) : 0;
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setProgress(pct);
            uploadStatusText.setText(getString(R.string.uploading,
                    Math.min(Uploader.runDone + 1, Math.max(Uploader.runTotal, 1)),
                    Math.max(Uploader.runTotal, 1), Uploader.currentFileName, pct));
        } else {
            uploadProgress.setVisibility(View.GONE);
            String text = "";
            if (!Uploader.lastResult.isEmpty()) {
                String when = new SimpleDateFormat("HH:mm:ss", Locale.US)
                        .format(new Date(Uploader.lastResultAt));
                text = getString(R.string.last_upload, when, Uploader.lastResult);
            }
            if (failingLong) {
                text = getString(R.string.failing_warning, text);
            }
            uploadStatusText.setText(text);
        }
        boolean configJustSaved = lastConfigSavedAt > Uploader.lastResultAt;
        retryUploadButton.setVisibility(
                (!Uploader.isRunning() && (configJustSaved || failingLong) && pending.length > 0)
                        ? View.VISIBLE : View.GONE);

        // Idle retry: the service only retries while recording, so when the
        // app is open but idle, a failed pass used to sit frozen until the
        // next rotation (onResume's kick). Retry once a minute here too,
        // so the upload status refreshes on its own.
        if (!recording && !Uploader.isRunning() && pending.length > 0
                && nowMs - lastIdleKickMs >= 60_000L) {
            lastIdleKickMs = nowMs;
            Uploader.kick(this);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
