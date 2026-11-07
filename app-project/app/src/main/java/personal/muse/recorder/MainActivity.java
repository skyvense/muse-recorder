package personal.muse.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
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
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.Switch;
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
    private static final int REQ_CAMERA = 43;

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
    private TextView recordTimer, segInfoText, recordStatusLine;
    private TextView selfNoteTitle, selfNoteSub;
    private View recordCard, convDot, meetingDot, selfNoteButton;
    private LinearLayout convCard, meetingCard, pendingContainer;
    private TextView convCardTitle, convCardState, meetingCardTitle, meetingCardState;
    private Button retryUploadButton;
    private ProgressBar uploadProgress, segProgressBar;
    private View settingsContainer;
    // Tabs
    private Button tabRecordButton, tabCloudButton, tabReportsButton, tabSettingsButton;
    private View pageRecord, pageCloud, pageReports, pageSettings;
    private int currentTab = 0;
    private R2Browser r2Browser;
    private SupabaseReports supabaseReports;
    private EditText supabaseUrlInput, supabaseKeyInput;
    private Spinner presetSpinner, languageSpinner, snapshotCameraSpinner, darkModeSpinner;
    private boolean snapshotSpinnerTouched = false;
    private boolean darkModeTouched = false;
    private Ui.Palette pal;
    private EditText endpointInput, regionInput, bucketInput, accessKeyInput, secretKeyInput;
    private EditText segmentMinutesInput;
    private Switch virtualStyleCheck, wifiOnlyCheck, deleteAfterUploadCheck, autoResumeCheck;
    private Switch snapshotCheck;
    private boolean spinnerTouched = false;
    private boolean languageTouched = false;
    private long lastConfigSavedAt = 0;
    // Press-and-hold self-note state
    private MediaRecorder noteRecorder = null;
    private File noteFile = null;
    private boolean noteActive = false;
    private long noteStartMs = 0;

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
        // Pick the matching framework theme before inflating, so native
        // widgets (spinners, dialogs, menus) follow the app's palette
        // instead of always rendering light.
        setTheme(Ui.isDark(this) ? android.R.style.Theme_Material
                : android.R.style.Theme_Material_Light);
        setContentView(R.layout.activity_main);
        pal = Ui.of(this);

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
        selfNoteTitle = findViewById(R.id.selfNoteTitle);
        selfNoteSub = findViewById(R.id.selfNoteSub);
        recordCard = findViewById(R.id.recordCard);
        recordTimer = findViewById(R.id.recordTimer);
        segInfoText = findViewById(R.id.segInfoText);
        recordStatusLine = findViewById(R.id.recordStatusLine);
        segProgressBar = findViewById(R.id.segProgressBar);
        convDot = findViewById(R.id.convDot);
        meetingDot = findViewById(R.id.meetingDot);
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
        snapshotCheck = findViewById(R.id.snapshotCheck);
        snapshotCameraSpinner = findViewById(R.id.snapshotCameraSpinner);
        settingsContainer = findViewById(R.id.settingsContainer);
        supabaseUrlInput = findViewById(R.id.supabaseUrlInput);
        supabaseKeyInput = findViewById(R.id.supabaseKeyInput);
        darkModeSpinner = findViewById(R.id.darkModeSpinner);

        applyTheme();

        // Tabs: record (control) / cloud (R2 browser) / reports (Supabase)
        // / settings. The settings that used to hide behind a toggle button
        // on the record page now live on their own tab, categorized.
        tabRecordButton = findViewById(R.id.tabRecordButton);
        tabCloudButton = findViewById(R.id.tabCloudButton);
        tabReportsButton = findViewById(R.id.tabReportsButton);
        tabSettingsButton = findViewById(R.id.tabSettingsButton);
        pageRecord = findViewById(R.id.pageRecord);
        pageCloud = findViewById(R.id.pageCloud);
        pageReports = findViewById(R.id.pageReports);
        pageSettings = findViewById(R.id.pageSettings);
        r2Browser = new R2Browser(this);
        supabaseReports = new SupabaseReports(this);
        tabRecordButton.setOnClickListener(v -> selectTab(0));
        tabCloudButton.setOnClickListener(v -> selectTab(1));
        tabReportsButton.setOnClickListener(v -> selectTab(2));
        tabSettingsButton.setOnClickListener(v -> selectTab(3));
        selectTab(0);

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

        // Dark mode applies immediately (like the snapshot switch): save
        // and recreate so every view re-resolves the palette.
        ArrayAdapter<String> darkModeAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.dark_mode_options));
        darkModeSpinner.setAdapter(darkModeAdapter);
        darkModeSpinner.setOnTouchListener((v, e) -> {
            darkModeTouched = true;
            return false;
        });
        darkModeSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!darkModeTouched) return;
                if (pos == Prefs.darkMode(MainActivity.this)) return;
                Prefs.get(MainActivity.this).edit()
                        .putInt(Prefs.KEY_DARK_MODE, pos).apply();
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

        // Enabling auto-snapshot needs the camera permission; ask right
        // away so the feature does not silently stay dead after saving.
        // The switch (and the camera choice below) apply IMMEDIATELY —
        // they used to be written only by "Save settings", so toggling
        // and then killing the app silently lost the change.
        snapshotCheck.setOnClickListener(v -> {
            Prefs.get(this).edit()
                    .putBoolean(Prefs.KEY_SNAPSHOT_ENABLED, snapshotCheck.isChecked())
                    .apply();
            // If recording is already running, upgrade/downgrade the
            // service's camera foreground type right away — that type is
            // what makes lock-screen / screen-off snapshots possible.
            RecordingService.refreshForeground(this);
            if (snapshotCheck.isChecked()
                    && checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                toast(getString(R.string.toast_camera_permission));
            }
        });

        ArrayAdapter<String> snapshotCameraAdapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item,
                getResources().getStringArray(R.array.snapshot_camera_options));
        snapshotCameraSpinner.setAdapter(snapshotCameraAdapter);
        snapshotCameraSpinner.setOnTouchListener((v, e) -> {
            snapshotSpinnerTouched = true;
            return false;
        });
        snapshotCameraSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (!snapshotSpinnerTouched) return;
                Prefs.get(MainActivity.this).edit()
                        .putInt(Prefs.KEY_SNAPSHOT_CAMERA, pos).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        findViewById(R.id.snapshotTestButton).setOnClickListener(v -> {
            if (checkSelfPermission(Manifest.permission.CAMERA)
                    != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
                toast(getString(R.string.toast_camera_permission));
                return;
            }
            Snapshotter.capturePair(this);
            toast(getString(R.string.toast_snapshot_testing));
            ui.postDelayed(this::refreshStatus, 4000);
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_CAMERA) {
            boolean granted = grantResults.length > 0
                    && grantResults[0] == PackageManager.PERMISSION_GRANTED;
            if (!granted && snapshotCheck != null) {
                snapshotCheck.setChecked(false);
                Prefs.get(this).edit()
                        .putBoolean(Prefs.KEY_SNAPSHOT_ENABLED, false).apply();
                toast(getString(R.string.toast_camera_denied));
            }
            if (granted) RecordingService.refreshForeground(this);
        }
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
        noteStartMs = System.currentTimeMillis();
        vibrateTick();
        selfNoteButton.setBackground(Ui.rounded(this, pal.danger, 16));
        selfNoteTitle.setText(R.string.self_note_active);
        selfNoteSub.setVisibility(View.GONE);
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
        selfNoteButton.setBackground(Ui.rounded(this, pal.accent, 16));
        selfNoteTitle.setText(R.string.self_note_title);
        selfNoteSub.setVisibility(View.VISIBLE);
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
        // Covers the reboot case: if the service was auto-started before
        // the app was opened, claiming the camera type may have been
        // refused then; retry now that the app is in the foreground.
        RecordingService.refreshForeground(this);
        SnapshotWatcher.start(this);
        Uploader.kick(this);
        refresh.run();
    }

    @Override
    protected void onPause() {
        super.onPause();
        SnapshotWatcher.stop(this);
        if (r2Browser != null) r2Browser.onHide();
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

    /**
     * Paints the whole activity with the current palette: page and tab-bar
     * backgrounds, text colors, buttons, checkboxes, progress bar. Views
     * created later (event cards, pending rows, list rows) read the same
     * palette in refreshStatus / the tab helpers.
     */
    private void applyTheme() {
        findViewById(R.id.rootLayout).setBackgroundColor(pal.bg);
        findViewById(R.id.tabBar).setBackgroundColor(pal.tabBar);
        findViewById(R.id.tabDivider).setBackgroundColor(pal.divider);
        // Record page: header + big card + status lines.
        ((TextView) findViewById(R.id.recordAppName)).setTextColor(pal.sub);
        ((TextView) findViewById(R.id.recordTitleText)).setTextColor(pal.text);
        statusText.setTextColor(pal.sub);
        recordCard.setBackground(Ui.cardBg(this, pal, 16));
        recordTimer.setTextColor(pal.text);
        segInfoText.setTextColor(pal.sub);
        recordStatusLine.setTextColor(pal.text);
        lastEventText.setTextColor(pal.sub);
        filesTitle.setTextColor(pal.text);
        uploadStatusText.setTextColor(pal.sub);
        if (uploadProgress.getProgressDrawable() != null) {
            uploadProgress.getProgressDrawable().setTint(pal.accent);
        }
        if (segProgressBar.getProgressDrawable() != null) {
            segProgressBar.getProgressDrawable().setTint(pal.accent);
        }
        styleSettingsText(settingsContainer);
        selfNoteButton.setBackground(Ui.rounded(this, pal.accent, 16));
        selfNoteTitle.setTextColor(Color.WHITE);
        selfNoteSub.setTextColor(0xE6FFFFFF);
        Ui.stylePrimary(retryUploadButton, pal);
        Ui.stylePrimary((Button) findViewById(R.id.saveSettingsButton), pal);
        Ui.styleGhost((Button) findViewById(R.id.batteryButton), pal);
        Ui.styleGhost((Button) findViewById(R.id.snapshotTestButton), pal);
    }

    /**
     * Recolors the settings form: group cards get the card background,
     * divider rows the divider color, labels/hints/inputs the text colors,
     * switches the accent tint. Views tagged "sub" (section titles, hint
     * lines, the header subtitle) always use the sub color.
     */
    private void styleSettingsText(View v) {
        Object tag = v.getTag();
        if ("groupCard".equals(tag)) {
            v.setBackground(Ui.cardBg(this, pal, 16));
        } else if ("divider".equals(tag)) {
            v.setBackgroundColor(pal.divider);
        }
        if (v instanceof Button) {
            // Buttons are styled separately in applyTheme.
        } else if (v instanceof Switch) {
            Ui.styleSwitch((Switch) v, pal);
        } else if (v instanceof EditText) {
            EditText et = (EditText) v;
            et.setTextColor(pal.text);
            et.setHintTextColor(pal.sub);
        } else if (v instanceof TextView) {
            TextView tv = (TextView) v;
            float sp = tv.getTextSize() / getResources().getDisplayMetrics().scaledDensity;
            // Small print (hints) is subdued; labels and titles full-strength.
            tv.setTextColor("sub".equals(tag) || sp <= 12.5f ? pal.sub : pal.text);
        }
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) styleSettingsText(g.getChildAt(i));
        }
    }

    private void loadSettings() {
        SharedPreferences p = Prefs.get(this);
        darkModeSpinner.setSelection(Prefs.darkMode(this));
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
        snapshotCheck.setChecked(p.getBoolean(Prefs.KEY_SNAPSHOT_ENABLED, false));
        snapshotCameraSpinner.setSelection(Prefs.snapshotCameraMode(this));
        String sbUrl = Prefs.supabaseUrl(this);
        supabaseUrlInput.setText(sbUrl.isEmpty()
                ? "https://xpmorezsouevzihgjpxe.supabase.co" : sbUrl);
        supabaseKeyInput.setText(Prefs.supabaseKey(this));
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
                .putBoolean(Prefs.KEY_SNAPSHOT_ENABLED, snapshotCheck.isChecked())
                .putInt(Prefs.KEY_SNAPSHOT_CAMERA, snapshotCameraSpinner.getSelectedItemPosition())
                .putString(Prefs.KEY_SB_URL, supabaseUrlInput.getText().toString().trim())
                .putString(Prefs.KEY_SB_KEY, supabaseKeyInput.getText().toString().trim())
                .apply();
        SnapshotWatcher.sync(this);
    }

    // ---- tabs ----

    private void selectTab(int tab) {
        if (currentTab == 1 && tab != 1 && r2Browser != null) r2Browser.onHide();
        currentTab = tab;
        pageRecord.setVisibility(tab == 0 ? View.VISIBLE : View.GONE);
        pageCloud.setVisibility(tab == 1 ? View.VISIBLE : View.GONE);
        pageReports.setVisibility(tab == 2 ? View.VISIBLE : View.GONE);
        pageSettings.setVisibility(tab == 3 ? View.VISIBLE : View.GONE);
        Button[] tabs = {tabRecordButton, tabCloudButton, tabReportsButton, tabSettingsButton};
        for (int i = 0; i < tabs.length; i++) {
            boolean activeTab = i == tab;
            tabs[i].setTypeface(null, activeTab
                    ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            tabs[i].setAllCaps(false);
            tabs[i].setTextColor(activeTab ? pal.accent : pal.sub);
            tabs[i].setBackground(activeTab
                    ? Ui.rounded(this, pal.card2, 20)
                    : Ui.rounded(this, Color.TRANSPARENT, 20));
        }
        if (tab == 1 && r2Browser != null) r2Browser.onShow();
        if (tab == 2 && supabaseReports != null) supabaseReports.onShow();
    }

    @Override
    public void onBackPressed() {
        if (currentTab == 1 && r2Browser != null && r2Browser.onBack()) return;
        if (currentTab == 2 && supabaseReports != null && supabaseReports.onBack()) return;
        if (currentTab != 0) {
            selectTab(0);
            return;
        }
        super.onBackPressed();
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

    private void styleEventCard(LinearLayout card, TextView title, TextView state,
                                View dot, boolean active, int activeColor, long sinceMs,
                                int descRes) {
        if (active) {
            card.setBackground(Ui.rounded(this, activeColor, 16));
            title.setTextColor(Color.WHITE);
            dot.setBackground(Ui.oval(Color.WHITE));
            long mins = sinceMs > 0 ? (System.currentTimeMillis() - sinceMs) / 60000 : 0;
            state.setText(getString(R.string.card_active, mins));
            state.setTextColor(0xE6FFFFFF);
        } else {
            card.setBackground(Ui.cardBg(this, pal, 16));
            title.setTextColor(pal.text);
            dot.setBackground(Ui.oval(activeColor));
            // Mockup: the second line is the scene description when idle.
            state.setText(descRes);
            state.setTextColor(pal.sub);
        }
    }

    /** HH:MM:SS with unbounded hours (a session can run past a day). */
    private static String fmtClock(long ms) {
        long s = Math.max(0, ms / 1000);
        return String.format(Locale.US, "%02d:%02d:%02d", s / 3600, (s % 3600) / 60, s % 60);
    }

    /** MM:SS, or H:MM:SS once past an hour. */
    private static String fmtDur(long sec) {
        if (sec >= 3600) {
            return String.format(Locale.US, "%d:%02d:%02d", sec / 3600, (sec % 3600) / 60, sec % 60);
        }
        return String.format(Locale.US, "%02d:%02d", sec / 60, sec % 60);
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
        // Header subtitle: live state, per the approved mockup.
        if (recording) {
            statusText.setText(getString(R.string.header_recording_today,
                    Prefs.segmentsStartedToday(this)));
        } else if (noteActive) {
            statusText.setText(R.string.self_note_recording);
        } else {
            statusText.setText(Prefs.s3Configured(this)
                    ? getString(R.string.status_tap_record)
                    : getString(R.string.status_tap_record_no_s3));
        }
        // Big card: session timer, current-segment line, segment progress.
        long sessionMs = recording && RecordingService.recordingSinceMs > 0
                ? System.currentTimeMillis() - RecordingService.recordingSinceMs
                : (noteActive && noteStartMs > 0
                        ? System.currentTimeMillis() - noteStartMs : 0);
        recordTimer.setText(fmtClock(sessionMs));
        long segTotalSec = Prefs.segmentMinutes(this) * 60L;
        if (recording && RecordingService.currentSegmentStartMs > 0) {
            long segElapsedSec = Math.max(0,
                    (System.currentTimeMillis() - RecordingService.currentSegmentStartMs) / 1000);
            segProgressBar.setProgress((int) Math.min(1000,
                    segElapsedSec * 1000 / Math.max(1, segTotalSec)));
            String state;
            if (Uploader.isRunning()) state = getString(R.string.seg_state_syncing);
            else if (Uploader.pendingCount(this) == 0) state = getString(R.string.seg_state_synced);
            else state = getString(R.string.record_pending_count, Uploader.pendingCount(this));
            File curSeg = RecordingService.currentSegmentFile;
            if (curSeg != null && curSeg.exists()) {
                state = String.format(Locale.US, "%.1f MB · ", curSeg.length() / 1_000_000.0) + state;
            }
            segInfoText.setText(getString(R.string.seg_info,
                    fmtDur(segElapsedSec), fmtDur(segTotalSec), state));
        } else {
            segProgressBar.setProgress(0);
            segInfoText.setText(getString(R.string.seg_info_idle,
                    fmtDur(0), fmtDur(segTotalSec)));
        }

        styleEventCard(convCard, convCardTitle, convCardState, convDot,
                prefs.getBoolean(Prefs.KEY_CONV_ACTIVE, false), pal.accent,
                prefs.getLong(Prefs.KEY_CONV_SINCE, 0), R.string.conv_desc);
        styleEventCard(meetingCard, meetingCardTitle, meetingCardState, meetingDot,
                prefs.getBoolean(Prefs.KEY_MEETING_ACTIVE, false), pal.accent2,
                prefs.getLong(Prefs.KEY_MEETING_SINCE, 0), R.string.meeting_desc);

        // File list: the in-progress segment first, then pending uploads.
        File[] pending = Uploader.pendingFiles(this);
        File[] pendingSnaps = Uploader.pendingSnapshots(this);
        long bytes = 0;
        List<String> rows = new ArrayList<>();
        List<Boolean> rowIsRecording = new ArrayList<>();
        SimpleDateFormat hm = new SimpleDateFormat("HH:mm", Locale.US);
        File cur = RecordingService.currentSegmentFile;
        String recLabel = null;
        String recMb = null;
        if (recording && cur != null && cur.exists()) {
            String name = cur.getName();
            recLabel = name.length() > 15 ? name.substring(4, 14) + " " + hm.format(new Date(cur.lastModified())) : name;
            recMb = String.format(Locale.US, "%.1f", cur.length() / 1_000_000.0);
            rows.add(getString(R.string.row_recording, recLabel, recMb));
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
        for (File f : pendingSnaps) {
            bytes += f.length();
            String name = f.getName();
            String datePart = name.startsWith("snap_") && name.length() > 15
                    ? name.substring(5, 15) : "";
            String label = datePart.isEmpty() ? name : datePart + " " + hm.format(new Date(f.lastModified()));
            rows.add(getString(R.string.row_snapshot, label,
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
            if (rowIsRecording.get(i) && recLabel != null) {
                // In-progress row: label + progress bar in place of the
                // ever-growing size text. The bar overlays elapsed/total
                // seconds plus the current size, e.g. 256/600秒(1.5MB).
                float density = getResources().getDisplayMetrics().density;
                LinearLayout row = new LinearLayout(this);
                row.setOrientation(LinearLayout.HORIZONTAL);
                row.setGravity(android.view.Gravity.CENTER_VERTICAL);
                row.setPadding(0, 6, 0, 6);
                TextView tv = new TextView(this);
                tv.setText(getString(R.string.row_recording_label, recLabel));
                tv.setTextSize(14);
                tv.setTextColor(pal.danger);
                row.addView(tv);
                SegmentProgressView bar = new SegmentProgressView(this);
                long segMs = Prefs.segmentMinutes(this) * 60_000L;
                long segElapsed = RecordingService.currentSegmentStartMs > 0
                        ? System.currentTimeMillis() - RecordingService.currentSegmentStartMs : 0;
                bar.setProgress(segElapsed / 1000, segMs / 1000,
                        getString(R.string.seconds_short), "(" + recMb + "MB)");
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        0, (int) (24 * density + 0.5f), 1f);
                lp.setMarginStart((int) (8 * density + 0.5f));
                row.addView(bar, lp);
                pendingContainer.addView(row);
                continue;
            }
            TextView tv = new TextView(this);
            tv.setText(rows.get(i));
            tv.setTextSize(14);
            tv.setTextColor(rowIsRecording.get(i) ? pal.danger : pal.text);
            tv.setPadding(0, 6, 0, 6);
            pendingContainer.addView(tv);
        }
        filesTitle.setText(pending.length + pendingSnaps.length > 0
                ? getString(R.string.files_title_pending, pending.length + pendingSnaps.length,
                        String.format(Locale.US, "%.1f", bytes / 1_000_000.0))
                : getString(R.string.files_title));

        long nowMs = System.currentTimeMillis();
        boolean failingLong = Uploader.failingSinceMs > 0
                && nowMs - Uploader.failingSinceMs >= 30 * 60_000L;
        int pendingTotal = pending.length + pendingSnaps.length;
        if (Uploader.isRunning()) {
            long total = Uploader.currentTotal;
            int pct = total > 0 ? (int) (Uploader.currentSent * 100 / total) : 0;
            uploadProgress.setVisibility(View.VISIBLE);
            uploadProgress.setProgress(pct);
            // Mockup status line carries the live uploading text while a
            // run is in flight; the small line below stays for afterwards.
            recordStatusLine.setText(getString(R.string.uploading,
                    Math.min(Uploader.runDone + 1, Math.max(Uploader.runTotal, 1)),
                    Math.max(Uploader.runTotal, 1), Uploader.currentFileName, pct));
            uploadStatusText.setVisibility(View.GONE);
        } else {
            uploadProgress.setVisibility(View.GONE);
            recordStatusLine.setText(pendingTotal > 0
                    ? getString(R.string.record_pending_count, pendingTotal)
                    : getString(R.string.record_pending_synced));
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
            uploadStatusText.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
        }
        boolean configJustSaved = lastConfigSavedAt > Uploader.lastResultAt;
        retryUploadButton.setVisibility(
                (!Uploader.isRunning() && (configJustSaved || failingLong)
                        && pending.length + pendingSnaps.length > 0)
                        ? View.VISIBLE : View.GONE);

        // Idle retry: the service only retries while recording, so when the
        // app is open but idle, a failed pass used to sit frozen until the
        // next rotation (onResume's kick). Retry once a minute here too,
        // so the upload status refreshes on its own.
        if (!recording && !Uploader.isRunning() && pending.length + pendingSnaps.length > 0
                && nowMs - lastIdleKickMs >= 60_000L) {
            lastIdleKickMs = nowMs;
            Uploader.kick(this);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
