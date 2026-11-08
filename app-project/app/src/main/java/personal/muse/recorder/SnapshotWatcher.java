package personal.muse.recorder;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Build;
import android.util.Log;

/**
 * Watches the accelerometer for a "phone raised upright" gesture and fires
 * {@link Snapshotter#capturePair} when it happens.
 *
 * Geometry: the trigger angle is the angle between the gravity vector and
 * the device's z axis (out of the screen). Lying flat, screen up, it is
 * ~0°; held upright in portrait it is ~90°.
 *
 * The state machine (settled with Zhang across v1.19.0–v1.24.0):
 * - Before any shot, dipping below 65° primes the watcher; a raise that
 *   crosses 80° and holds ~250 ms fires. (80°, not 90° — holding a phone
 *   bolt upright looks conspicuous; not 60°, which fired too easily.)
 * - Once a shot is taken the watcher is LOCKED: no second shot can
 *   start until the phone has been deliberately laid flat (< 25° for
 *   ~1 s). Merely lowering it, waiting, or raising it again does
 *   nothing — and does so silently, with no vibration.
 * - Sole exception: if the phone stays at >= 80° continuously after a
 *   shot, it auto-repeats every repeat interval
 *   (Prefs.snapshotRepeatMs, default 3 min, 0 = off) for as long as it
 *   stays up; dipping below 65° breaks the chain and the lock applies.
 * Vibration is reserved for captures that actually start (short tick)
 * and photos actually saved (longer buzz). With the screen off it
 * fires only while the recording service holds the camera
 * foreground-service type; otherwise the system would block the camera
 * and the attempt could only fail.
 *
 * The feature is strictly opt-in: nothing is registered or triggered unless
 * the user enables it in Settings (Prefs.KEY_SNAPSHOT_ENABLED, default off),
 * and the camera permission must also be granted.
 *
 * Registration is reference-counted: MuseApp holds it for the whole
 * process lifetime, and the recording service and MainActivity add their
 * own holds on top, so the watcher is active whenever the app process is
 * alive at all — including right after a kill + reopen.
 */
public final class SnapshotWatcher implements SensorEventListener {
    private static final String TAG = "MuseSnapshotWatcher";
    private static SnapshotWatcher instance;
    private static int refs = 0;

    private final Context app;
    private SensorManager sensorManager;
    private boolean armed = false;
    /** True after a shot until a deliberate lay-flat unlocks (v1.24.0). */
    private boolean locked = false;
    /** True while the phone has stayed >= 80° continuously since a shot. */
    private boolean uprightChain = false;
    private long uprightSinceMs = 0;
    private long flatSinceMs = 0;
    private static volatile long lastFiredMs = 0;

    // Live gesture state for the record page's practice line (v1.20.0).
    public static final int STATE_OFF = 0;     // disabled or no permission
    public static final int STATE_WAITING = 1; // low / armed, waiting for a raise
    public static final int STATE_RAISING = 2; // in the 65°–80° band, on the way up
    public static final int STATE_POSE = 3;    // >= 80°, holding before the shot
    public static final int STATE_HIGH = 4;    // held upright after a shot, repeat pending
    public static final int STATE_LOCKED = 5;  // shot taken; lay flat to shoot again
    public static volatile int gestureState = STATE_OFF;
    public static volatile int angleDeg = 0;
    /** When the held-upright auto-repeat fires next (0 = repeat off). */
    public static volatile long nextRepeatAtMs = 0;

    private SnapshotWatcher(Context app) {
        this.app = app;
    }

    public static synchronized void start(Context ctx) {
        if (instance == null) instance = new SnapshotWatcher(ctx.getApplicationContext());
        refs++;
        if (refs == 1) instance.register();
    }

    public static synchronized void stop(Context ctx) {
        if (refs > 0) refs--;
        if (refs == 0 && instance != null) instance.unregister();
    }

    /** Re-register after a settings change (cheap; safe to call any time). */
    public static synchronized void sync(Context ctx) {
        if (refs > 0 && instance != null) {
            instance.unregister();
            instance.register();
        }
    }

    private void register() {
        sensorManager = (SensorManager) app.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager == null) return;
        Sensor acc = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
        if (acc == null) {
            Log.i(TAG, "No accelerometer; snapshot watcher inactive");
            return;
        }
        sensorManager.registerListener(this, acc, SensorManager.SENSOR_DELAY_NORMAL);
    }

    private void unregister() {
        if (sensorManager != null) sensorManager.unregisterListener(this);
        sensorManager = null;
        armed = false;
        uprightSinceMs = 0;
        flatSinceMs = 0;
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (!Prefs.snapshotEnabled(app)) {
            armed = false;
            uprightSinceMs = 0;
            gestureState = STATE_OFF;
            return;
        }
        if (Build.VERSION.SDK_INT >= 23
                && app.checkSelfPermission(Manifest.permission.CAMERA)
                != PackageManager.PERMISSION_GRANTED) {
            gestureState = STATE_OFF;
            return;
        }
        float x = event.values[0], y = event.values[1], z = event.values[2];
        double g = Math.sqrt(x * x + y * y + z * z);
        if (g < 1.0) return;
        double cos = Math.max(-1.0, Math.min(1.0, z / g));
        double angle = Math.toDegrees(Math.acos(cos));
        long now = System.currentTimeMillis();
        angleDeg = (int) Math.round(angle);

        // A deliberate lay-flat (< 25° for ~1 s) is the ONLY way to
        // unlock after a shot (v1.24.0): it also clears every timer, so
        // the next raise can fire immediately.
        if (angle < 25) {
            if (flatSinceMs == 0) flatSinceMs = now;
            if (locked && now - flatSinceMs >= 1_000) {
                locked = false;
                lastFiredMs = 0;
                nextRepeatAtMs = 0;
            }
        } else {
            flatSinceMs = 0;
        }
        if (angle < 65) {
            // Going low breaks any held-upright chain. It primes the
            // watcher only when not locked — after a shot, merely
            // lowering the phone does NOT re-arm it.
            uprightChain = false;
            uprightSinceMs = 0;
            if (!locked) armed = true;
            gestureState = locked ? STATE_LOCKED : STATE_WAITING;
            return;
        }
        if (angle >= 80) {
            // Firing with the screen off only works while the recording
            // service runs with the CAMERA foreground type (v1.9.3+);
            // without it Android blocks background camera access and the
            // attempt could only fail. With the screen on, always allowed.
            android.os.PowerManager pm =
                    (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            boolean allowed = screenOn || RecordingService.cameraFgsActive;
            boolean capturing = Snapshotter.isCapturing();
            if (armed && !locked) {
                if (uprightSinceMs == 0) uprightSinceMs = now;
                if (allowed && now - uprightSinceMs >= 250 && !capturing) {
                    fire(now, "Raise gesture detected");
                }
            } else if (locked && uprightChain && allowed && !capturing) {
                // The one exception to the lay-flat rule: the phone has
                // stayed >= 80° continuously since the shot, so it
                // auto-repeats every interval (default 3 min).
                long intervalMs = Prefs.snapshotRepeatMs(app);
                if (intervalMs > 0 && lastFiredMs > 0
                        && now - lastFiredMs >= intervalMs) {
                    fire(now, "Held upright; auto-repeat snapshot");
                }
            }
            // Locked without a chain: silently nothing — no photo, no
            // vibration, no reaction at all.
        }
        // Publish the practice-line state from the final pose each event.
        if (locked) {
            gestureState = (angle >= 80 && uprightChain && nextRepeatAtMs > 0)
                    ? STATE_HIGH : STATE_LOCKED;
        } else if (angle >= 80) {
            gestureState = STATE_POSE;
        } else {
            gestureState = STATE_RAISING;
        }
    }

    /** Shared fire path for the raise gesture and the held-upright repeat. */
    private void fire(long now, String why) {
        lastFiredMs = now;
        long intervalMs = Prefs.snapshotRepeatMs(app);
        nextRepeatAtMs = intervalMs > 0 ? now + intervalMs : 0;
        armed = false;
        locked = true;
        uprightChain = true;
        uprightSinceMs = 0;
        Log.i(TAG, why + " (angle " + angleDeg + "°); taking snapshot pair");
        Snapshotter.capturePair(app);
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
