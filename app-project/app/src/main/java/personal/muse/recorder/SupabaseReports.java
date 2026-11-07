package personal.muse.recorder;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.ImageView;
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
private final Handler ui = new Handler(Looper.getMainLooper());
private final ExecutorService io = Executors.newSingleThreadExecutor();

private final TextView title, status, detailText;
private final Button backButton, refreshButton, sheetButton;
private final ImageView sheetImage;
private final ListView list;
private final View detail;

private boolean detailOpen = false;
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
}

private final List<DayRow> days = new ArrayList<>();
private final BaseAdapter adapter = new BaseAdapter() {
@Override public int getCount() { return days.size();}
@Override public Object getItem(int pos) { return days.get(pos);}
@Override public long getItemId(int pos) { return pos;}

@Override
public View getView(int pos, View convert, ViewGroup parent) {
TextView tv;
if (convert instanceof TextView) tv = (TextView) convert;
else {
tv = new TextView(act);
tv.setTextSize(16);
}
DayRow d = days.get(pos);
tv.setPadding(24, 18, 16, 18);
tv.setText("📅 " + d.day + " · "
+ act.getString(R.string.reports_segments, d.segments));
return tv;
}
};

public SupabaseReports(Activity act) {
this.act = act;
title = act.findViewById(R.id.reportsTitle);
status = act.findViewById(R.id.reportsStatus);
detailText = act.findViewById(R.id.reportsDetailText);
backButton = act.findViewById(R.id.reportsBackButton);
refreshButton = act.findViewById(R.id.reportsRefreshButton);
sheetButton = act.findViewById(R.id.reportsSheetButton);
sheetImage = act.findViewById(R.id.reportsSheetImage);
list = act.findViewById(R.id.reportsList);
detail = act.findViewById(R.id.reportsDetail);
list.setAdapter(adapter);
backButton.setOnClickListener(v -> onBack());
refreshButton.setOnClickListener(v -> loadDays());
sheetButton.setOnClickListener(v -> onSheetButton());
list.setOnItemClickListener((parent, view, pos, id) -> openDay(days.get(pos)));
}

public void onShow() {
if (!detailOpen && System.currentTimeMillis() - loadedAtMs > 60_000) loadDays();
}

/** Returns true if the back press was consumed. */
public boolean onBack() {
if (!detailOpen) return false;
detailOpen = false;
resetSheet();
detail.setVisibility(View.GONE);
list.setVisibility(View.VISIBLE);
backButton.setVisibility(View.GONE);
title.setText(act.getString(R.string.reports_title));
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

private void openDay(DayRow d) {
final int gen = ++generation;
detailOpen = true;
resetSheet();
sheetDay = d.day;
list.setVisibility(View.GONE);
detail.setVisibility(View.VISIBLE);
backButton.setVisibility(View.VISIBLE);
title.setText(d.day);
detailText.setText(d.summary);
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
sb.append("\n\n—— ").append(act.getString(R.string.reports_analyses_title))
.append(" ——");
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
detailText.setText(text);
status.setText("");
if (fPhotos >= 0) {
sheetButtonLabel = act.getString(R.string.reports_sheet_button,
fPhotos, fClusters);
sheetButton.setText(sheetButtonLabel);
sheetButton.setVisibility(View.VISIBLE);
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
