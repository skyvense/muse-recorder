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
 * ~0°; held upright in portrait it is ~90°. The watcher arms after the
 * phone has been below 65° (flat on a desk, or held low in the hand),
 * then fires once the raise crosses 80° and holds there for ~250 ms
 * (v1.19.0: 80° settles between the original near-90° trigger — holding
 * a phone bolt upright looks conspicuous — and the 60° tried briefly in
 * v1.18.0, which fired too easily). After firing it disarms until the
 * phone goes low again, and a 15 s cooldown applies, so normal handling
 * cannot produce a burst of snapshots. Vibration tells the two states
 * apart (v1.19.0): one short tick when a capture starts, a double pulse
 * when a raise lands inside the cooldown and is skipped (once per
 * raise). With the screen off it fires only
 * while the recording service holds the camera foreground-service type;
 * otherwise the system would block the camera and the attempt could only
 * fail.
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
    private long uprightSinceMs = 0;
    private boolean cooldownSignaled = false;
    private static volatile long lastFiredMs = 0;

    // Live gesture state for the record page's practice line (v1.20.0).
    public static final int STATE_OFF = 0;     // disabled or no permission
    public static final int STATE_WAITING = 1; // low / armed, waiting for a raise
    public static final int STATE_RAISING = 2; // in the 65°–80° band, on the way up
    public static final int STATE_POSE = 3;    // >= 80°, holding before the shot
    public static final int STATE_HIGH = 4;    // still high after firing; lower to re-arm
    public static volatile int gestureState = STATE_OFF;
    public static volatile int angleDeg = 0;
    public static volatile long cooldownEndsMs = 0;

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
        cooldownSignaled = false;
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

        if (angle < 65) {
            // Low again: (re)arm and forget any raise in progress.
            armed = true;
            uprightSinceMs = 0;
            cooldownSignaled = false;
            gestureState = STATE_WAITING;
            return;
        }
        if (armed && angle >= 80) {
            if (uprightSinceMs == 0) uprightSinceMs = now;
            // Firing with the screen off only works while the recording
            // service runs with the CAMERA foreground type (v1.9.3+);
            // without it Android blocks background camera access and the
            // attempt could only fail. With the screen on, always allowed.
            android.os.PowerManager pm =
                    (android.os.PowerManager) app.getSystemService(Context.POWER_SERVICE);
            boolean screenOn = pm == null || pm.isInteractive();
            boolean allowed = screenOn || RecordingService.cameraFgsActive;
            boolean coolingDown = now - lastFiredMs < 15_000;
            if (allowed && coolingDown && !cooldownSignaled
                    && !Snapshotter.isCapturing()) {
                // Distinct double pulse: "raise seen, but still cooling
                // down — no photo this time". Once per raise.
                cooldownSignaled = true;
                Snapshotter.signalCooldown(app);
            }
            if (allowed
                    && now - uprightSinceMs >= 250
                    && !coolingDown
                    && !Snapshotter.isCapturing()) {
                lastFiredMs = now;
                cooldownEndsMs = now + 15_000;
                armed = false;
                uprightSinceMs = 0;
                Log.i(TAG, "Raise gesture detected (angle " + (int) angle + "°); taking snapshot pair");
                Snapshotter.capturePair(app);
            }
        }
        // 65°–80° is a hysteresis band: keep the current state so a raise
        // passing slowly through it neither arms nor resets the timer.
        // Publish the practice-line state from the final pose each event.
        if (!armed) {
            gestureState = STATE_HIGH;
        } else if (angle >= 80) {
            gestureState = STATE_POSE;
        } else {
            gestureState = STATE_RAISING;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {}
}
