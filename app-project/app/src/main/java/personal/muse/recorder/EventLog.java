package personal.muse.recorder;

import android.content.Context;

import java.io.File;
import java.io.FileWriter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Event markers the user taps while recording. Each event is one JSON line
 * in markers/events-YYYY-MM-DD.jsonl on the device:
 *   {"ts":"2026-10-24T14:02:11+08:00","epoch_ms":1793...,"type":"meeting_start"}
 * Types: conversation_start, conversation_end, meeting_start, meeting_end,
 * self_note. The uploader syncs these files to the bucket under markers/,
 * where the daily-summary pipeline reads them to structure the day.
 */
public final class EventLog {
    public static final String CONVERSATION_START = "conversation_start";
    public static final String CONVERSATION_END = "conversation_end";
    public static final String MEETING_START = "meeting_start";
    public static final String MEETING_END = "meeting_end";
    public static final String SELF_NOTE = "self_note";
    public static final String SELF_NOTE_START = "self_note_start";
    public static final String SELF_NOTE_END = "self_note_end";

    private EventLog() {}

    public static synchronized void add(Context ctx, String type) {
        try {
            Date now = new Date();
            String day = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now);
            String ts = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).format(now);
            File f = new File(Prefs.markersDir(ctx), "events-" + day + ".jsonl");
            String line = "{\"ts\":\"" + ts + "\",\"epoch_ms\":" + now.getTime()
                    + ",\"type\":\"" + type + "\"}\n";
            try (FileWriter w = new FileWriter(f, true)) {
                w.write(line);
            }
        } catch (Exception ignored) {
            // A lost marker must never disturb recording.
        }
    }
}
