package personal.muse.recorder;

import android.app.Activity;
import android.graphics.BitmapFactory;
import android.graphics.drawable.ColorDrawable;
import android.media.MediaPlayer;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.TextView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The "cloud" tab: browses the recording bucket on Cloudflare R2 by date —
 * audio segments (rec_/note_) and auto snapshots (snapshot-&lt;date&gt;/).
 * Tapping a photo downloads and shows it; tapping an audio file downloads
 * and plays it. Everything is read-only; downloads go to a small cache
 * directory that is cleared when the browser is created.
 */
public final class R2Browser {
    private final Activity act;
    private final Ui.Palette pal;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final TextView title, status;
    private final Button backButton, refreshButton;
    private final ListView list;
    private final View detail;
    private final TextView detailTitle, playLabel;
    private final ImageView image;
    private final Button playButton;
    private final View playerRow;

    private static final int LEVEL_DATES = 0, LEVEL_FOLDER = 1, LEVEL_DETAIL = 2;
    private int level = LEVEL_DATES;
    private String currentDate;
    private int generation = 0;
    private long datesLoadedAtMs = 0;

    private MediaPlayer player;
    private boolean playing = false;
    private File currentAudio;

    private static final class Row {
        static final int HEADER = 0, ITEM = 1;
        int type;
        String text;
        String key;      // S3 key for ITEM rows
        long size;
        boolean photo;
    }

    private final List<Row> rows = new ArrayList<>();
    private final BaseAdapter adapter = new BaseAdapter() {
        @Override public int getCount() { return rows.size(); }
        @Override public Object getItem(int pos) { return rows.get(pos); }
        @Override public long getItemId(int pos) { return pos; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int pos) { return rows.get(pos).type; }
        @Override public boolean isEnabled(int pos) { return rows.get(pos).type == Row.ITEM; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            Row r = rows.get(pos);
            TextView tv;
            if (convert instanceof TextView) {
                tv = (TextView) convert;
            } else {
                tv = new TextView(act);
                tv.setTextSize(16);
            }
            tv.setText(r.text);
            tv.setTextColor(pal.text);
            if (r.type == Row.HEADER) {
                tv.setPadding(16, 28, 16, 8);
                tv.setTextSize(15);
                tv.setTypeface(tv.getTypeface(), android.graphics.Typeface.BOLD);
            } else {
                tv.setPadding(24, 18, 16, 18);
                tv.setTextSize(16);
                tv.setTypeface(android.graphics.Typeface.DEFAULT);
            }
            return tv;
        }
    };

    public R2Browser(Activity act) {
        this.act = act;
        this.pal = Ui.of(act);
        title = act.findViewById(R.id.cloudTitle);
        status = act.findViewById(R.id.cloudStatus);
        backButton = act.findViewById(R.id.cloudBackButton);
        refreshButton = act.findViewById(R.id.cloudRefreshButton);
        list = act.findViewById(R.id.cloudList);
        detail = act.findViewById(R.id.cloudDetail);
        detailTitle = act.findViewById(R.id.cloudDetailTitle);
        image = act.findViewById(R.id.cloudImage);
        playButton = act.findViewById(R.id.cloudPlayButton);
        playLabel = act.findViewById(R.id.cloudPlayLabel);
        playerRow = act.findViewById(R.id.cloudPlayerRow);
        title.setTextColor(pal.text);
        status.setTextColor(pal.sub);
        detailTitle.setTextColor(pal.sub);
        playLabel.setTextColor(pal.text);
        Ui.styleGhost(backButton, pal);
        Ui.styleGhost(refreshButton, pal);
        Ui.styleGhost(playButton, pal);
        list.setDivider(new ColorDrawable(pal.divider));
        list.setDividerHeight(1);
        list.setAdapter(adapter);
        clearCache();
        backButton.setOnClickListener(v -> onBack());
        refreshButton.setOnClickListener(v -> {
            if (level == LEVEL_DATES) loadDates(true);
            else if (level == LEVEL_FOLDER) loadFolder(currentDate);
        });
        list.setOnItemClickListener((parent, view, pos, id) -> {
            Row r = rows.get(pos);
            if (r.type != Row.ITEM) return;
            if (level == LEVEL_DATES) {
                currentDate = r.key;
                loadFolder(r.key);
            } else if (level == LEVEL_FOLDER) {
                openItem(r);
            }
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
        if (files != null) for (File f : files) f.delete();
    }

    public void onShow() {
        if (level == LEVEL_DATES && System.currentTimeMillis() - datesLoadedAtMs > 60_000) {
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
            list.setVisibility(View.VISIBLE);
            title.setText(currentDate);
            status.setText("");
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

    private void loadDates(boolean force) {
        if (!Prefs.s3Configured(act)) {
            rows.clear();
            adapter.notifyDataSetChanged();
            backButton.setVisibility(View.GONE);
            title.setText(act.getString(R.string.cloud_title));
            setStatus(act.getString(R.string.cloud_not_configured));
            return;
        }
        final int gen = ++generation;
        level = LEVEL_DATES;
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.GONE);
        title.setText(act.getString(R.string.cloud_title));
        setStatus(act.getString(R.string.cloud_loading));
        io.execute(() -> {
            try {
                S3.ListResult res = S3.list(Prefs.s3Config(act), "", "/");
                Set<String> dates = new HashSet<>();
                for (String p : res.prefixes) {
                    String name = p.endsWith("/") ? p.substring(0, p.length() - 1) : p;
                    if (name.matches("\\d{4}-\\d{2}-\\d{2}")) dates.add(name);
                    else if (name.startsWith("snapshot-")) {
                        String d = name.substring("snapshot-".length());
                        if (d.matches("\\d{4}-\\d{2}-\\d{2}")) dates.add(d);
                    }
                }
                List<String> sorted = new ArrayList<>(dates);
                sorted.sort(Collections.reverseOrder());
                ui.post(() -> {
                    if (gen != generation) return;
                    datesLoadedAtMs = System.currentTimeMillis();
                    rows.clear();
                    for (String d : sorted) {
                        Row r = new Row();
                        r.type = Row.ITEM;
                        r.key = d;
                        r.text = "📅 " + d;
                        rows.add(r);
                    }
                    adapter.notifyDataSetChanged();
                    setStatus(sorted.isEmpty()
                            ? act.getString(R.string.cloud_empty) : "");
                });
            } catch (Exception e) {
                if (gen != generation) return;
                setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
            }
        });
    }

    private void loadFolder(String date) {
        final int gen = ++generation;
        level = LEVEL_FOLDER;
        detail.setVisibility(View.GONE);
        list.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        title.setText(date);
        setStatus(act.getString(R.string.cloud_loading));
        io.execute(() -> {
            try {
                S3.Config cfg = Prefs.s3Config(act);
                S3.ListResult audio = S3.list(cfg, date + "/", "");
                S3.ListResult snaps = S3.list(cfg, "snapshot-" + date + "/", "");
                List<Row> out = new ArrayList<>();
                List<S3.Entry> photos = new ArrayList<>();
                for (S3.Entry e : snaps.entries) if (e.key.endsWith(".jpg")) photos.add(e);
                if (!photos.isEmpty()) {
                    out.add(header(act.getString(R.string.cloud_photo_section)));
                    for (S3.Entry e : photos) out.add(item(e, true));
                }
                List<S3.Entry> clips = new ArrayList<>();
                for (S3.Entry e : audio.entries) if (e.key.endsWith(".m4a")) clips.add(e);
                if (!clips.isEmpty()) {
                    out.add(header(act.getString(R.string.cloud_audio_section)));
                    for (S3.Entry e : clips) out.add(item(e, false));
                }
                ui.post(() -> {
                    if (gen != generation) return;
                    rows.clear();
                    rows.addAll(out);
                    adapter.notifyDataSetChanged();
                    setStatus(out.isEmpty() ? act.getString(R.string.cloud_empty) : "");
                });
            } catch (Exception e) {
                if (gen != generation) return;
                setStatus(act.getString(R.string.cloud_failed, trimErr(e)));
            }
        });
    }

    private Row header(String text) {
        Row r = new Row();
        r.type = Row.HEADER;
        r.text = text;
        return r;
    }

    private Row item(S3.Entry e, boolean photo) {
        Row r = new Row();
        r.type = Row.ITEM;
        r.key = e.key;
        r.size = e.size;
        r.photo = photo;
        String name = e.key.substring(e.key.lastIndexOf('/') + 1);
        String time = "";
        // <prefix>_<yyyy-MM-dd>_<HH-mm-ss>... : time starts after the 2nd '_'
        int u1 = name.indexOf('_');
        int u2 = u1 >= 0 ? name.indexOf('_', u1 + 1) : -1;
        if (u2 >= 0 && name.length() >= u2 + 9) {
            time = name.substring(u2 + 1, u2 + 9).replace('-', ':');
        }
        String mb = String.format(Locale.US, "%.1f MB", e.size / 1_000_000.0);
        if (photo) {
            String cam = name.contains("_front") ? "🤳 " : "📷 ";
            r.text = cam + time + " · " + mb;
        } else {
            r.text = (name.startsWith("note_") ? "🎙 " : "🎵 ") + time + " · " + mb;
        }
        return r;
    }

    private void openItem(Row r) {
        final int gen = ++generation;
        level = LEVEL_DETAIL;
        stopPlayer();
        list.setVisibility(View.GONE);
        detail.setVisibility(View.VISIBLE);
        backButton.setVisibility(View.VISIBLE);
        title.setText(currentDate);
        detailTitle.setText(r.key.substring(r.key.lastIndexOf('/') + 1));
        image.setVisibility(View.GONE);
        playerRow.setVisibility(View.GONE);
        setStatus(act.getString(R.string.cloud_downloading));
        final File dest = new File(cacheDir(),
                r.key.substring(r.key.lastIndexOf('/') + 1));
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
