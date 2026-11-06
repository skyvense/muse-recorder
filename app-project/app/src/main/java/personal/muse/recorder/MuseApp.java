package personal.muse.recorder;

import android.app.Application;

/**
 * Process-wide entry point. The raise-to-snapshot watcher is registered
 * here, at process level, so it is active whenever the app process is
 * alive — in particular right after the user re-opens the app, without
 * first having to start a recording. (v1.9.0 registered it only from the
 * recording service and the foreground activity; after the app was killed
 * and merely re-opened, the watcher could be missing and the gesture did
 * nothing.) The watcher itself still checks the opt-in preference and the
 * camera permission on every sensor event, so this costs nothing while
 * the feature is off.
 */
public class MuseApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        SnapshotWatcher.start(this);
    }
}
