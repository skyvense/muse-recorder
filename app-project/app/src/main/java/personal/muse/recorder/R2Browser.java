package personal.muse.recorder;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Path;
import android.graphics.RectF;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "cloud" tab: browses the recording bucket on Cloudflare R2 by date —
 * audio segments (rec_/note_) and auto snapshots (snapshot-&lt;date&gt;/).
 *
 * v1.13 layout (per the approved mockups): header block, a storage-usage
 * card (bucket total from one full ListObjectsV2 pass, which also yields
 * the per-date photo/audio counts shown on the date rows), then the date's
 * photos as a 3-column rounded-thumbnail grid and its audio as play cards.
 * Tapping a photo downloads and shows it full-size; tapping an audio card
 * downloads and plays it. Read-only; downloads go to a small cache.
 */
public final class R2Browser {
    private final Activity act;
    private final Ui.Palette pal;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final TextView subtitle, status, storageTotal;
    private final Button backButton, refreshButton;
    private final ScrollView scroll;
    private final LinearLayout container;
    private final View detail;
    private final TextView detailTitle, playLabel;
    private final ImageView image;
    private final Button playButton;
    private final View playerRow;
    private final ProgressBar storageBar;

    private static final int LEVEL_DATES = 0, LEVEL_FOLDER = 1, LEVEL_DETAIL = 2;
    private int level = LEVEL_DATES;
    private String currentDate;
    private int generation = 0;

    private MediaPlayer player;
    private boolean playing = false;
    private File currentAudio;

    private static final class DateInfo {
        String date;
        int photos, audio;
        boolean countsKnown;
    }

    private static final class Item {
        String key;
        String name;
        long size;
        boolean photo;
    }

    // Cached results of the last full-bucket pass (dates + totals + counts).
    private final List<DateInfo> dateInfos = new ArrayList<>();
    private long totalBytes = -1;
    private long cacheAtMs = 0;

    public R2Browser(Activity act) {
        this.act = act;
        this.pal = Ui.of(act);
        ((TextView) act.findViewById(R.id.cloudAppName)).setTextColor(pal.sub);
        ((TextView) act.findViewById(R.id.cloudTitle)).setTextColor(pal.text);
        subtitle = act.findViewById(R.id.cloudSubtitle);
        status = act.findViewById(R.id.cloudStatus);
        backButton = act.findViewById(R.id.cloudBackButton);
        refreshButton = act.findViewById(R.id.cloudRefreshButton);
        scroll = act.findViewById(R.id.cloudScroll);
        container = act.findViewById(R.id.cloudListContainer);
        detail = act.findViewById(R.id.cloudDetail);
        detailTitle = act.findViewById(R.id.cloudDetailTitle);
        image = act.findViewById(R.id.cloudImage);
        playButton = act.findViewById(R.id.cloudPlayButton);
        playLabel = act.findViewById(R.id.cloudPlayLabel);
        playerRow = act.findViewById(R.id.cloudPlayerRow);
        storageTotal = act.findViewById(R.id.cloudStorageTotal);
        storageBar = act.findViewById(R.id.cloudStorageBar);
        act.findViewById(R.id.cloudStorageCard)
                .setBackground(Ui.cardBg(act, pal, 16));
        ((TextView) act.findViewById(R.id.cloudStorageLabel)).setTextColor(pal.sub);
        ((TextView) act.findViewById(R.id.cloudStorageQuota)).setTextColor(pal.sub);
        storageTotal.setTextColor(pal.text);
        subtitle.setTextColor(pal.sub);
        status.setTextColor(pal.sub);
        detailTitle.setTextColor(pal.sub);
        playLabel.setTextColor(pal.text);
        if (storageBar.getProgressDrawable() != null) {
            storageBar.getProgressDrawable().setTint(pal.accent);
        }
        Ui.styleGhost(backButton, pal);
        Ui.styleGhost(refreshButton, pal);
        Ui.styleGhost(playButton, pal);
        String bucket = Prefs.s3Config(act).bucket;
        subtitle.setText(bucket.isEmpty()
                ? "Cloudflare R2"
                : act.getString(R.string.cloud_subtitle, bucket));
        clearCache();
        backButton.setOnClickListener(v -> onBack());
        refreshButton.setOnClickListener(v -> {
            if (level == LEVEL_DATES) loadDates(true);
            else if (level == LEVEL_FOLDER) loadFolder(currentDate);
        });
        playButton.setOnClickListener(v -> togglePlay());
    }

    private File cacheDir() {
        File dir = new File(act.getCacheDir(), "cloud_cache");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private void clearCache() {
        File[] files = cacheDir().listFiles();
        if (files != null) for (File f : files) {
            if (f.isDirectory()) {
                File[] inner = f.listFiles();
                if (inner != null) for (File g : inner) g.delete();
            }
            f.delete();
        }
    }

    public void onShow() {
        if (level == LEVEL_DATES
                && System.currentTimeMillis() - cacheAtMs > 60_000) {
            loadDates(false);
        }
    }

    public void onHide() {
        stopPlayer();
    }

    /** Returns true if the back press was consumed inside the browser. */
    public boolean onBack() {
        if (level == LEVEL_DETAIL) {
            stopPlayer();
            level = LEVEL_FOLDER;
            detail.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            setStatus("");
            return true;
        }
        if (level == LEVEL_FOLDER) {
            level = LEVEL_DATES;
            loadDates(false);
            return true;
        }
        return false;
    }

    private void setStatus(String s) {
        ui.post(() -> status.setText(s == null ? "" : s));
    }

    private int dp(float v) {
        return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
    }

    // ---------- dates level ----------

    private void loadDates(boolean force) {
        if (!Prefs.s3Configured(act)) {
            dateInfos.clear();
            totalBytes = -1;
            renderDates();
            renderStorage();
            setStatus(act.getString(R.string.cloud_not_configured));
            return;
        }
        if (!force && !dateInfos.isEmpty()
                && System.currentTimeMillis() - cacheAtMs < 60_000) {
            level = LEVEL_DATES;
            detail.setVisibility(View.GONE);
            scroll.setVisibility(View.VISIBLE);
            renderDates();
            renderStorage();
            setStatus("");
            return;
        }
        final int gen = ++generation;
        level = LEVEL_DATES;
        detail.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        setStatus(act.getString(R.string.cloud_loading));
        io.execute(() -> {
            try {
                S3.Config cfg = Prefs.s3Config(act);
                S3.ListResult res = S3.list(cfg, "", "/");
                Set<String> dates = new HashSet<>();
                for (String p : res.prefixes) {
                    String name = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
                    if (name.matches("\\d{4}-\\d{2}-\\d{2}")) dates.add(name);
                    else if (name.startsWith("snapshot-")) {
                        String d = name.substring("snapshot-".length());
                        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) dates.add(d);
                    }
                }
                // One full pass: bucket total + per-date counts.
                long total = -1;
                Map<String, int[]> counts = new HashMap<>();
                try {
                    S3.ListResult all = S3.list(cfg, "", "");
                    total = 0;
                    for (S3.Entry e : all.entries) {
                        total += e.size;
                        String k = e.key;
                        if (k.startsWith("snapshot-") && k.endsWith(".jpg")) {
                            String d = k.substring("snapshot-".length());
                            int slash = d.indexOf('/');
                            if (slash > 0) {
                                d = d.substring(0, slash);
                                if (d.matches("\\d{4}-\\d{2}-\\d{2}")) {
                                    counts.computeIfAbsent(d, x -> new int[2])[0]++;
                                }
                            }
                        } else if (k.length() > 11 && k.endsWith(".m4a")
                                && k.substring(0, 10).matches("\\d{4}-\\d{2}-\\d{2}")
                                && k.charAt(10) == '/') {
                            counts.computeIfAbsent(k.substring(0, 10), x -> new int[2])[1]++;
                        }
                    }
                } catch (Exception ignored) {
                    // Counts/total are decorative; dates alone still render.
                    total = -1;
                    counts.clear();
                }
                List<String> sorted = new ArrayList<>(dates);
                sorted.sort(Collections.reverseOrder());
                final long fTotal = total;
                final Map<String, int[]> fCounts = counts;
                ui.post(() -> {
                    if (gen != generation) return;
                    cacheAtMs = System.currentTimeMillis();
                    totalBytes = fTotal;
                    dateInfos.clear();
                    for (String d : sorted) {
                        DateInfo di = new DateInfo();
                        di.date = d;
                        int[] c = fCounts.get(d);
                        if (c != null) {
                            di.photos = c[0];
                            di.audio = c[1];
                            di.countsKnown = true;
                        }
                        dateInfos.add(di);
                    }
                    renderDates();
                    renderStorage();
                    setStatus(sorted.isEmpty()
                            ? act.getString(R.string.cloud_empty) : "");
                });
            } catch (Exception e) {
                if (gen != generation) return;
                setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
            }
        });
    }

    private void renderStorage() {
        if (totalBytes < 0) {
            storageTotal.setText("—");
            storageBar.setProgress(0);
            return;
        }
        storageTotal.setText(fmtSize(totalBytes));
        long quota = 10_000_000_000L; // R2 free tier: 10 GB
        storageBar.setProgress((int) Math.min(1000, totalBytes * 1000 / quota));
    }

    private static String fmtSize(long bytes) {
        if (bytes >= 1_000_000_000L) {
            return String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0);
        }
        if (bytes >= 1_000_000L) {
            return String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0);
        }
        return String.format(Locale.US, "%.0f KB", bytes / 1000.0);
    }

    private void renderDates() {
        container.removeAllViews();
        for (DateInfo di : dateInfos) {
            LinearLayout row = new LinearLayout(act);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(Ui.rounded(act, pal.card2, 16));
            row.setPadding(dp(16), dp(16), dp(16), dp(16));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = dp(10);
            row.setLayoutParams(lp);
            TextView dateTv = new TextView(act);
            dateTv.setText(di.date);
            dateTv.setTextSize(17);
            dateTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            dateTv.setTextColor(pal.text);
            row.addView(dateTv);
            if (di.countsKnown) {
                TextView countsTv = new TextView(act);
                countsTv.setText(act.getString(R.string.cloud_counts, di.photos, di.audio));
                countsTv.setTextSize(13);
                countsTv.setTextColor(pal.sub);
                LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                        0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
                countsTv.setGravity(Gravity.END);
                countsTv.setLayoutParams(clp);
                row.addView(countsTv);
            }
            row.setOnClickListener(v -> {
                currentDate = di.date;
                loadFolder(di.date);
            });
            container.addView(row);
        }
    }

    // ---------- folder (one date) level ----------

    private void loadFolder(String date) {
        final int gen = ++generation;
        level = LEVEL_FOLDER;
        detail.setVisibility(View.GONE);
        scroll.setVisibility(View.VISIBLE);
        setStatus(act.getString(R.string.cloud_loading));
        io.execute(() -> {
            try {
                S3.Config cfg = Prefs.s3Config(act);
                S3.ListResult audio = S3.list(cfg, date + "/", "");
                S3.ListResult snaps = S3.list(cfg, "snapshot-" + date + "/", "");
                List<Item> photos = new ArrayList<>();
                for (S3.Entry e : snaps.entries) if (e.key.endsWith(".jpg")) photos.add(item(e, true));
                List<Item> clips = new ArrayList<>();
                for (S3.Entry e : audio.entries) if (e.key.endsWith(".m4a")) clips.add(item(e, false));
                ui.post(() -> {
                    if (gen != generation) return;
                    renderFolder(date, photos, clips);
                    setStatus(photos.isEmpty() && clips.isEmpty()
                            ? act.getString(R.string.cloud_empty) : "");
                });
            } catch (Exception e) {
                if (gen != generation) return;
                setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
            }
        });
    }

    private Item item(S3.Entry e, boolean photo) {
        Item it = new Item();
        it.key = e.key;
        it.size = e.size;
        it.photo = photo;
        it.name = e.key.substring(e.key.lastIndexOf('/') + 1);
        return it;
    }

    private void renderFolder(String date, List<Item> photos, List<Item> clips) {
        container.removeAllViews();
        // Header card: "‹ date" + counts; tapping goes back to the dates.
        LinearLayout head = new LinearLayout(act);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setBackground(Ui.rounded(act, pal.card2, 16));
        head.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams hlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        hlp.bottomMargin = dp(6);
        head.setLayoutParams(hlp);
        TextView dateTv = new TextView(act);
        dateTv.setText("‹ " + date);
        dateTv.setTextSize(17);
        dateTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        dateTv.setTextColor(pal.text);
        head.addView(dateTv);
        TextView countsTv = new TextView(act);
        countsTv.setText(act.getString(R.string.cloud_counts, photos.size(), clips.size()));
        countsTv.setTextSize(13);
        countsTv.setTextColor(pal.sub);
        countsTv.setGravity(Gravity.END);
        countsTv.setLayoutParams(new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        head.addView(countsTv);
        head.setOnClickListener(v -> onBack());
        container.addView(head);

        if (!photos.isEmpty()) {
            container.addView(sectionTitle(R.string.cloud_photos_title));
            int screenW = act.getResources().getDisplayMetrics().widthPixels;
            int cell = (screenW - dp(40) - dp(16)) / 3;
            LinearLayout rowLayout = null;
            for (int i = 0; i < photos.size(); i++) {
                if (i % 3 == 0) {
                    rowLayout = new LinearLayout(act);
                    rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
                    rlp.bottomMargin = dp(8);
                    rowLayout.setLayoutParams(rlp);
                    container.addView(rowLayout);
                }
                Item it = photos.get(i);
                ImageView iv = new ImageView(act);
                LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams(cell, cell);
                if (i % 3 != 0) ilp.leftMargin = dp(8);
                iv.setLayoutParams(ilp);
                iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
                iv.setBackground(Ui.rounded(act, pal.card2, 12));
                iv.setTag(it.key);
                iv.setOnClickListener(v -> openItem(it));
                rowLayout.addView(iv);
                loadThumb(it, iv);
            }
        }
        if (!clips.isEmpty()) {
            container.addView(sectionTitle(R.string.cloud_audio_title));
            for (Item it : clips) container.addView(audioRow(it));
        }
    }

    private TextView sectionTitle(int res) {
        TextView tv = new TextView(act);
        tv.setText(res);
        tv.setTextSize(16);
        tv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        tv.setTextColor(pal.text);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(14);
        lp.bottomMargin = dp(10);
        tv.setLayoutParams(lp);
        return tv;
    }

    private View audioRow(Item it) {
        LinearLayout row = new LinearLayout(act);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setBackground(Ui.cardBg(act, pal, 16));
        row.setPadding(dp(12), dp(12), dp(12), dp(12));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        TextView badge = new TextView(act);
        badge.setText("▶");
        badge.setTextSize(15);
        badge.setTextColor(android.graphics.Color.WHITE);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(Ui.oval(pal.accent));
        badge.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        row.addView(badge);
        LinearLayout texts = new LinearLayout(act);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tlp.leftMargin = dp(12);
        texts.setLayoutParams(tlp);
        TextView name = new TextView(act);
        name.setText(it.name);
        name.setTextSize(15);
        name.setTextColor(pal.text);
        name.setSingleLine(true);
        name.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        TextView size = new TextView(act);
        // The listing carries no duration, so the second line is the size.
        size.setText(fmtSize(it.size));
        size.setTextSize(13);
        size.setTextColor(pal.sub);
        texts.addView(size);
        row.addView(texts);
        row.setOnClickListener(v -> openItem(it));
        return row;
    }

    // ---------- thumbnails ----------

    private void loadThumb(Item it, ImageView iv) {
        final int gen = generation;
        final String key = it.key;
        io.execute(() -> {
            try {
                File thumbDir = new File(cacheDir(), "thumbs");
                if (!thumbDir.exists()) thumbDir.mkdirs();
                File thumb = new File(thumbDir, it.name + ".thumb.jpg");
                if (!thumb.exists()) {
                    File full = new File(cacheDir(), it.name);
                    if (!full.exists() || full.length() == 0) {
                        S3.download(Prefs.s3Config(act), key, full);
                    }
                    BitmapFactory.Options bounds = new BitmapFactory.Options();
                    bounds.inJustDecodeBounds = true;
                    BitmapFactory.decodeFile(full.getAbsolutePath(), bounds);
                    int maxDim = Math.max(bounds.outWidth, bounds.outHeight);
                    BitmapFactory.Options opts = new BitmapFactory.Options();
                    opts.inSampleSize = Math.max(1, maxDim / 512);
                    Bitmap src = BitmapFactory.decodeFile(full.getAbsolutePath(), opts);
                    if (src == null) return;
                    Bitmap squared = centerCropSquare(src);
                    Bitmap rounded = roundCorners(squared, dp(12));
                    try (FileOutputStream out = new FileOutputStream(thumb)) {
                        rounded.compress(Bitmap.CompressFormat.JPEG, 82, out);
                    }
                }
                Bitmap bmp = BitmapFactory.decodeFile(thumb.getAbsolutePath());
                if (bmp == null) return;
                ui.post(() -> {
                    if (gen != generation) return;
                    if (key.equals(iv.getTag())) iv.setImageBitmap(bmp);
                });
            } catch (Exception ignored) {
                // A missing thumbnail just leaves the card-colored cell.
            }
        });
    }

    private static Bitmap centerCropSquare(Bitmap src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2;
        int y = (src.getHeight() - side) / 2;
        Bitmap cropped = Bitmap.createBitmap(src, x, y, side, side);
        Bitmap scaled = Bitmap.createScaledBitmap(cropped, 360, 360, true);
        return scaled;
    }

    private static Bitmap roundCorners(Bitmap src, float radiusPx) {
        Bitmap out = Bitmap.createBitmap(src.getWidth(), src.getHeight(),
                Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        Path path = new Path();
        path.addRoundRect(new RectF(0, 0, src.getWidth(), src.getHeight()),
                radiusPx, radiusPx, Path.Direction.CW);
        canvas.clipPath(path);
        canvas.drawBitmap(src, 0, 0, null);
        return out;
    }

    // ---------- detail level (full photo / audio player) ----------

    private void openItem(Item r) {
        final int gen = ++generation;
        level = LEVEL_DETAIL;
        stopPlayer();
        scroll.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        detailTitle.setText(r.name);
        image.setVisibility(View.GONE);
        playerRow.setVisibility(View.GONE);
        setStatus(act.getString(R.string.cloud_downloading));
        final File dest = new File(cacheDir(), r.name);
        io.execute(() -> {
            try {
                if (!dest.exists() || dest.length() == 0) {
                    S3.download(Prefs.s3Config(act), r.key, dest);
                }
                ui.post(() -> {
                    if (gen != generation) return;
                    setStatus("");
                    if (r.photo) {
                        image.setImageBitmap(BitmapFactory.decodeFile(dest.getAbsolutePath()));
                        image.setVisibility(View.VISIBLE);
                    } else {
                        currentAudio = dest;
                        playerRow.setVisibility(View.VISIBLE);
                        playLabel.setText(dest.getName());
                        playButton.setText(act.getString(R.string.player_play));
                        playing = false;
                    }
                });
            } catch (Exception e) {
                if (gen != generation) return;
                setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
            }
        });
    }

    private void togglePlay() {
        if (currentAudio == null) return;
        try {
            if (player == null) {
                player = new MediaPlayer();
                player.setDataSource(currentAudio.getAbsolutePath());
                player.setOnCompletionListener(mp -> {
                    playing = false;
                    playButton.setText(act.getString(R.string.player_play));
                });
                player.prepare();
            }
            if (playing) {
                player.pause();
                playing = false;
                playButton.setText(act.getString(R.string.player_play));
            } else {
                player.start();
                playing = true;
                playButton.setText(act.getString(R.string.player_pause));
            }
        } catch (Exception e) {
            setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
        }
    }

    private void stopPlayer() {
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) {}
            player.release();
            player = null;
        }
        playing = false;
        currentAudio = null;
    }

    private static String trimErr(Exception e) {
        String m = e.getMessage();
        if (m == null) m = e.getClass().getSimpleName();
        return m.length() > 120 ? m.substring(0, 120) : m;
    }

    static String fmtDate(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(d);
    }
}
