package personal.muse.recorder;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.ColorDrawable;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
* The "reports" tab: reads the daily summaries (and, per day, the window
* analyses) from Zhang's Supabase project via PostgREST, using the
* publishable/anon key the user pastes into Settings. Read-only; the
* tables need a SELECT policy for the anon role (see the delivery note /
* Settings hint). The service_role key is never used by the app.
*/
public final class SupabaseReports {
private final Activity act;
private final Ui.Palette pal;
private final Handler ui = new Handler(Looper.getMainLooper());
private final ExecutorService io = Executors.newSingleThreadExecutor();

private final TextView title, status, detailText, dayTitle;
private final Button backButton, refreshButton, sheetButton;
private final ImageView sheetImage;
private final ListView list;
private final View detail, header, detailBar;

private boolean detailOpen = false;
private boolean pendingAutoSheet = false;
private int generation = 0;
private long loadedAtMs = 0;

// Daily activity sheet (daily_sheets table): metadata first, the ~1 MB
// base64 image only when the user taps the button.
private String sheetDay = null;
private String sheetButtonLabel = "";
private Bitmap sheetBitmap = null;
private boolean sheetBusy = false;

private static final class DayRow {
String day;
String summary;
int segments;
int sheetPhotos = -1;
int sheetClusters = -1;
}

private final List<DayRow> days = new ArrayList<>();
private final BaseAdapter adapter = new BaseAdapter() {
@Override public int getCount() { return days.size();}
@Override public Object getItem(int pos) { return days.get(pos);}
@Override public long getItemId(int pos) { return pos;}

@Override
public View getView(int pos, View convert, ViewGroup parent) {
// Mockup day card: bold date + segment count right, one-line summary
// preview, and (for days with an activity sheet) a primary button that
// jumps straight into the detail with the sheet shown.
DayRow d = days.get(pos);
LinearLayout outer = new LinearLayout(act);
outer.setOrientation(LinearLayout.VERTICAL);
outer.setPadding(dp(20), dp(5), dp(20), dp(5));
LinearLayout card = new LinearLayout(act);
card.setOrientation(LinearLayout.VERTICAL);
card.setBackground(Ui.cardBg(act, pal, 16));
card.setPadding(dp(16), dp(14), dp(16), dp(14));
outer.addView(card);
LinearLayout top = new LinearLayout(act);
top.setOrientation(LinearLayout.HORIZONTAL);
top.setGravity(Gravity.CENTER_VERTICAL);
TextView dateTv = new TextView(act);
dateTv.setText(d.day);
dateTv.setTextSize(17);
dateTv.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
dateTv.setTextColor(pal.text);
top.addView(dateTv);
TextView segTv = new TextView(act);
segTv.setText(act.getString(R.string.reports_segments, d.segments));
segTv.setTextSize(13);
segTv.setTextColor(pal.sub);
segTv.setGravity(Gravity.END);
segTv.setLayoutParams(new LinearLayout.LayoutParams(
0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
top.addView(segTv);
card.addView(top);
String preview = firstLine(d.summary);
if (!preview.isEmpty()) {
TextView pv = new TextView(act);
pv.setText(preview);
pv.setTextSize(14);
pv.setTextColor(pal.sub);
pv.setSingleLine(true);
pv.setEllipsize(android.text.TextUtils.TruncateAt.END);
LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT);
plp.topMargin = dp(6);
pv.setLayoutParams(plp);
card.addView(pv);
}
if (d.sheetPhotos >= 0) {
Button b = new Button(act);
b.setText(act.getString(R.string.reports_sheet_button,
d.sheetPhotos, d.sheetClusters));
Ui.stylePrimary(b, pal);
LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
LinearLayout.LayoutParams.MATCH_PARENT,
LinearLayout.LayoutParams.WRAP_CONTENT);
blp.topMargin = dp(12);
b.setLayoutParams(blp);
b.setOnClickListener(v -> openDay(d, true));
card.addView(b);
}
return outer;
}
};

private int dp(float v) {
return (int) (v * act.getResources().getDisplayMetrics().density + 0.5f);
}

private static String firstLine(String s) {
// Clean one-line preview: strip all markdown markers, not just ##.
return Markdown.stripToPlain(s);
}

private CharSequence renderMd(String text) {
return Markdown.render(act, text, pal, detailText.getTextSize());
}

public SupabaseReports(Activity act) {
this.act = act;
this.pal = Ui.of(act);
title = act.findViewById(R.id.reportsTitle);
status = act.findViewById(R.id.reportsStatus);
detailText = act.findViewById(R.id.reportsDetailText);
dayTitle = act.findViewById(R.id.reportsDayTitle);
backButton = act.findViewById(R.id.reportsBackButton);
refreshButton = act.findViewById(R.id.reportsRefreshButton);
sheetButton = act.findViewById(R.id.reportsSheetButton);
sheetImage = act.findViewById(R.id.reportsSheetImage);
list = act.findViewById(R.id.reportsList);
detail = act.findViewById(R.id.reportsDetail);
header = act.findViewById(R.id.reportsHeader);
detailBar = act.findViewById(R.id.reportsDetailBar);
((TextView) act.findViewById(R.id.reportsAppName)).setTextColor(pal.sub);
((TextView) act.findViewById(R.id.reportsSubtitle)).setTextColor(pal.sub);
title.setTextColor(pal.text);
dayTitle.setTextColor(pal.text);
status.setTextColor(pal.sub);
detailText.setTextColor(pal.text);
Ui.styleGhost(backButton, pal);
Ui.styleGhost(refreshButton, pal);
Ui.stylePrimary(sheetButton, pal);
list.setDivider(new ColorDrawable(android.graphics.Color.TRANSPARENT));
list.setDividerHeight(0);
list.setAdapter(adapter);
backButton.setOnClickListener(v -> onBack());
refreshButton.setOnClickListener(v -> loadDays());
sheetButton.setOnClickListener(v -> onSheetButton());
list.setOnItemClickListener((parent, view, pos, id) -> openDay(days.get(pos), false));
}

public void onShow() {
if (!detailOpen && System.currentTimeMillis() - loadedAtMs > 60_000) loadDays();
}

/** Returns true if the back press was consumed. */
public boolean onBack() {
if (!detailOpen) return false;
detailOpen = false;
pendingAutoSheet = false;
resetSheet();
detail.setVisibility(View.GONE);
list.setVisibility(View.VISIBLE);
detailBar.setVisibility(View.GONE);
header.setVisibility(View.VISIBLE);
status.setText("");
return true;
}

private boolean configured() {
return!Prefs.supabaseKey(act).trim().isEmpty();
}

private void loadDays() {
if (!configured()) {
days.clear();
adapter.notifyDataSetChanged();
status.setText(act.getString(R.string.reports_not_configured));
return;
}
final int gen = ++generation;
status.setText(act.getString(R.string.reports_loading));
io.execute(() -> {
try {
String json = get("/rest/v1/daily_summaries"
+ "?select=day,summary_text,segment_count&order=day.desc&limit=90");
JSONArray arr = new JSONArray(json);
List<DayRow> out = new ArrayList<>();
for (int i = 0; i < arr.length(); i++) {
JSONObject o = arr.getJSONObject(i);
DayRow d = new DayRow();
d.day = o.optString("day");
d.summary = o.optString("summary_text");
d.segments = o.optInt("segment_count");
out.add(d);
}
// Activity-sheet metadata for every day (one query, no images) so the
// list cards can show the sheet button; the table may not exist yet.
try {
JSONArray sarr = new JSONArray(get("/rest/v1/daily_sheets"
+ "?select=day,photo_count,cluster_count"));
java.util.Map<String, int[]> sheets = new java.util.HashMap<>();
for (int i = 0; i < sarr.length(); i++) {
JSONObject o = sarr.getJSONObject(i);
sheets.put(o.optString("day"), new int[]{
o.optInt("photo_count"), o.optInt("cluster_count")});
}
for (DayRow d : out) {
int[] s = sheets.get(d.day);
if (s != null) {
d.sheetPhotos = s[0];
d.sheetClusters = s[1];
}
}
} catch (Exception ignored) {
// No sheets (or no table): cards simply have no button.
}
ui.post(() -> {
if (gen!= generation) return;
loadedAtMs = System.currentTimeMillis();
days.clear();
days.addAll(out);
adapter.notifyDataSetChanged();
status.setText(out.isEmpty()
? act.getString(R.string.reports_empty): "");
});
} catch (Exception e) {
if (gen!= generation) return;
ui.post(() -> status.setText(
act.getString(R.string.reports_failed, trimErr(e))));
}
});
}

private void resetSheet() {
sheetDay = null;
sheetBitmap = null;
sheetBusy = false;
sheetButton.setVisibility(View.GONE);
sheetImage.setVisibility(View.GONE);
sheetImage.setImageBitmap(null);
}

private void onSheetButton() {
if (sheetDay == null || sheetBusy) return;
if (sheetBitmap!= null) {
boolean show = sheetImage.getVisibility()!= View.VISIBLE;
sheetImage.setVisibility(show? View.VISIBLE: View.GONE);
sheetButton.setText(show
? act.getString(R.string.reports_sheet_hide): sheetButtonLabel);
return;
}
sheetBusy = true;
sheetButton.setText(act.getString(R.string.reports_sheet_loading));
final String day = sheetDay;
final int gen = generation;
io.execute(() -> {
try {
JSONArray arr = new JSONArray(get("/rest/v1/daily_sheets"
+ "?select=image_base64&day=eq." + enc(day)));
if (arr.length() == 0) throw new java.io.IOException("empty");
byte[] bytes = Base64.decode(arr.getJSONObject(0).optString("image_base64"),
Base64.DEFAULT);
Bitmap bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
if (bmp == null) throw new java.io.IOException("decode failed");
ui.post(() -> {
sheetBusy = false;
if (gen!= generation ||!detailOpen) return;
sheetBitmap = bmp;
sheetImage.setImageBitmap(bmp);
sheetImage.setVisibility(View.VISIBLE);
sheetButton.setText(act.getString(R.string.reports_sheet_hide));
});
} catch (Exception e) {
ui.post(() -> {
sheetBusy = false;
if (gen!= generation ||!detailOpen) return;
sheetButton.setText(sheetButtonLabel);
status.setText(act.getString(R.string.reports_sheet_failed, trimErr(e)));
});
}
});
}

private void openDay(DayRow d, boolean autoSheet) {
final int gen = ++generation;
detailOpen = true;
pendingAutoSheet = autoSheet;
resetSheet();
sheetDay = d.day;
list.setVisibility(View.GONE);
detail.setVisibility(View.VISIBLE);
header.setVisibility(View.GONE);
detailBar.setVisibility(View.VISIBLE);
dayTitle.setText(d.day);
detailText.setText(renderMd(d.summary));
status.setText(act.getString(R.string.reports_loading));
io.execute(() -> {
StringBuilder sb = new StringBuilder(d.summary);
try {
String next = nextDay(d.day);
String path = "/rest/v1/analyses?select=window_start,window_end,analysis_text"
+ "&order=window_start.asc"
+ "&window_start=gte." + enc(d.day + " 00:00")
+ "&window_start=lt." + enc(next + " 00:00");
JSONArray arr = new JSONArray(get(path));
if (arr.length() > 0) {
// A real markdown heading, so the renderer styles it as one.
sb.append("\n\n## ").append(act.getString(R.string.reports_analyses_title));
for (int i = 0; i < arr.length(); i++) {
JSONObject o = arr.getJSONObject(i);
sb.append("\n\n\n")
.append(o.optString("analysis_text"));
}
}
} catch (Exception e) {
sb.append("\n\n(").append(act.getString(R.string.reports_analyses_failed))
.append(")");
}
// Activity-sheet metadata (no image yet); table may not exist yet.
int sheetPhotos = -1, sheetClusters = -1;
try {
JSONArray sarr = new JSONArray(get("/rest/v1/daily_sheets"
+ "?select=photo_count,cluster_count&day=eq." + enc(d.day)));
if (sarr.length() > 0) {
sheetPhotos = sarr.getJSONObject(0).optInt("photo_count");
sheetClusters = sarr.getJSONObject(0).optInt("cluster_count");
}
} catch (Exception ignored) {
// No sheet for this day (or table not created yet): no button.
}
final String text = sb.toString();
final int fPhotos = sheetPhotos, fClusters = sheetClusters;
ui.post(() -> {
if (gen!= generation ||!detailOpen) return;
detailText.setText(renderMd(text));
status.setText("");
if (fPhotos >= 0) {
sheetButtonLabel = act.getString(R.string.reports_sheet_button,
fPhotos, fClusters);
sheetButton.setText(sheetButtonLabel);
sheetButton.setVisibility(View.VISIBLE);
// Opened from a list card's sheet button: load and show the image
// right away instead of waiting for a second tap.
if (pendingAutoSheet) {
pendingAutoSheet = false;
onSheetButton();
}
}
});
});
}

private static String enc(String s) throws Exception {
return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
}

private static String nextDay(String day) {
try {
SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
Date d = f.parse(day);
return f.format(new Date(d.getTime() + 86_400_000L));
} catch (Exception e) {
return day;
}
}

private String get(String path) throws Exception {
String base = Prefs.supabaseUrl(act).trim();
if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
String key = Prefs.supabaseKey(act).trim();
HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
conn.setConnectTimeout(20_000);
conn.setReadTimeout(60_000);
conn.setRequestProperty("apikey", key);
conn.setRequestProperty("Authorization", "Bearer " + key);
conn.setRequestProperty("Accept", "application/json");
int code = conn.getResponseCode();
InputStream in = code >= 200 && code < 300? conn.getInputStream(): conn.getErrorStream();
String body = "";
if (in!= null) {
java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
byte[] b = new byte[8192];
int n;
while ((n = in.read(b)) > 0) bos.write(b, 0, n);
body = bos.toString("UTF-8");
}
conn.disconnect();
if (code < 200 || code >= 300) {
throw new java.io.IOException("HTTP " + code + " " + body);
}
return body;
}

private static String trimErr(Exception e) {
String m = e.getMessage();
if (m == null) m = e.getClass().getSimpleName();
return m.length() > 120? m.substring(0, 120): m;
}
}
