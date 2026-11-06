package personal.muse.recorder;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.WindowManager;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Takes one still photo with the back camera and one with the front camera,
 * headlessly (no preview UI): a dummy SurfaceTexture feeds a short preview
 * run so auto-exposure can settle, then a still capture lands on an
 * ImageReader and is saved as a JPEG in {@link Prefs#snapshotsDir}.
 *
 * Files are named snap_<yyyy-MM-dd_HH-mm-ss>_back.jpg / _front.jpg and are
 * picked up by {@link Uploader}, which sends them to the bucket under
 * snapshot-<yyyy-MM-dd>/. A failed or missing camera never blocks the other
 * one, and the whole pair runs on background threads.
 */
public final class Snapshotter {
    private static final String TAG = "MuseSnapshotter";
    private static final AtomicBoolean CAPTURING = new AtomicBoolean(false);

    /** When the last pair finished (0 = never); shown in the UI if needed. */
    public static volatile long lastSnapshotAtMs = 0;

    private Snapshotter() {}

    public static boolean isCapturing() {
        return CAPTURING.get();
    }

    public static void capturePair(Context ctx) {
        if (!CAPTURING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        // NOTE: no vibration and no event marker here. Up to v1.9.1 both
        // fired at trigger time, so attempts the system then blocked
        // (camera unavailable while the app is in the background) still
        // buzzed — the user felt far more buzzes than photos. From v1.9.2
        // the buzz and the marker mean "a photo was actually saved".
        Thread t = new Thread(() -> {
            HandlerThread ht = new HandlerThread("muse-snapshot");
            ht.start();
            Handler h = new Handler(ht.getLooper());
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    .format(new Date());
            int mode = Prefs.snapshotCameraMode(app); // 0=both, 1=back, 2=front
            int saved = 0;
            try {
                if (mode != 2) saved += attempt(app, h, CameraMetadata.LENS_FACING_BACK, stamp, "back") ? 1 : 0;
                if (mode != 1) {
                    // Give the HAL a moment to fully release the first
                    // camera; opening the next one too early was losing
                    // the front shot on some attempts.
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    saved += attempt(app, h, CameraMetadata.LENS_FACING_FRONT, stamp, "front") ? 1 : 0;
                }
            } finally {
                lastSnapshotAtMs = System.currentTimeMillis();
                if (saved > 0) EventLog.add(app, EventLog.SNAPSHOT);
                CAPTURING.set(false);
                ht.quitSafely();
                Uploader.kick(app);
            }
        }, "muse-snapshot-pair");
        t.start();
    }

    /**
     * One camera attempt; on a fast failure (camera busy / blocked, the
     * attempt returned in under 5 s without an image) retries once after a
     * short pause. A slow failure (timeout) is not retried, to bound the
     * total time a pair can take.
     */
    private static boolean attempt(Context app, Handler h, int facing, String stamp, String label) {
        long t0 = System.currentTimeMillis();
        boolean ok = captureOne(app, h, facing, stamp, label);
        if (!ok && System.currentTimeMillis() - t0 < 5_000) {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            ok = captureOne(app, h, facing, stamp, label);
        }
        if (ok) vibrateTick(app);
        return ok;
    }

    private static void vibrateTick(Context ctx) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    v.vibrate(VibrationEffect.createOneShot(40, VibrationEffect.DEFAULT_AMPLITUDE));
                } else {
                    //noinspection deprecation
                    v.vibrate(40);
                }
            }
        } catch (Exception ignored) {}
    }

    private static String findCamera(CameraManager cm, int facing) {
        try {
            for (String id : cm.getCameraIdList()) {
                Integer f = cm.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (f != null && f == facing) return id;
            }
        } catch (Exception e) {
            Log.w(TAG, "Camera list failed", e);
        }
        return null;
    }

    private static Size pickJpegSize(CameraCharacteristics chars) {
        StreamConfigurationMap map = chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) return new Size(1920, 1080);
        Size[] sizes = map.getOutputSizes(ImageFormat.JPEG);
        if (sizes == null || sizes.length == 0) return new Size(1920, 1080);
        Size best = sizes[0];
        for (Size s : sizes) {
            // Cap at ~12 MP: plenty for a snapshot, keeps upload sizes sane.
            if (s.getWidth() > 4200) continue;
            if ((long) s.getWidth() * s.getHeight() > (long) best.getWidth() * best.getHeight()) {
                best = s;
            }
        }
        return best;
    }

    private static int jpegOrientation(Context ctx, CameraCharacteristics chars, int facing) {
        Integer sensor = chars.get(CameraCharacteristics.SENSOR_ORIENTATION);
        int sensorDeg = sensor == null ? 0 : sensor;
        int deviceDeg = 0;
        try {
            WindowManager wm = (WindowManager) ctx.getSystemService(Context.WINDOW_SERVICE);
            if (wm != null) {
                switch (wm.getDefaultDisplay().getRotation()) {
                    case Surface.ROTATION_90: deviceDeg = 90; break;
                    case Surface.ROTATION_180: deviceDeg = 180; break;
                    case Surface.ROTATION_270: deviceDeg = 270; break;
                    default: deviceDeg = 0;
                }
            }
        } catch (Exception ignored) {}
        if (facing == CameraMetadata.LENS_FACING_FRONT) {
            int r = (sensorDeg + deviceDeg) % 360;
            return (360 - r) % 360;
        }
        return (sensorDeg - deviceDeg + 360) % 360;
    }

    /** Mutable per-capture state, closed over by the async callbacks. */
    private static final class Shot {
        CameraDevice camera;
        CameraCaptureSession session;
        ImageReader reader;
        SurfaceTexture texture;
        volatile boolean saved = false;

        void closeAll() {
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (camera != null) camera.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (texture != null) texture.release(); } catch (Exception ignored) {}
            session = null; camera = null; reader = null; texture = null;
        }
    }

    private static boolean captureOne(Context app, Handler h, int facing, String stamp, String label) {
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return false;
        String id = findCamera(cm, facing);
        if (id == null) {
            Log.i(TAG, "No camera with facing " + facing + "; skipping " + label);
            return false;
        }
        final Shot shot = new Shot();
        final CountDownLatch done = new CountDownLatch(1);
        try {
            CameraCharacteristics chars = cm.getCameraCharacteristics(id);
            Size size = pickJpegSize(chars);
            final int orientation = jpegOrientation(app, chars, facing);
            final File out = new File(Prefs.snapshotsDir(app), "snap_" + stamp + "_" + label + ".jpg");

            shot.texture = new SurfaceTexture(11);
            shot.texture.setDefaultBufferSize(640, 480);
            final Surface previewSurface = new Surface(shot.texture);

            shot.reader = ImageReader.newInstance(size.getWidth(), size.getHeight(),
                    ImageFormat.JPEG, 2);
            shot.reader.setOnImageAvailableListener(reader -> {
                try (Image image = reader.acquireLatestImage()) {
                    if (image == null || shot.saved) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(bytes);
                    }
                    shot.saved = true;
                    Log.i(TAG, "Saved snapshot " + out.getName() + " (" + bytes.length + " bytes)");
                } catch (Exception e) {
                    Log.w(TAG, "Snapshot save failed for " + label, e);
                } finally {
                    done.countDown();
                }
            }, h);

            cm.openCamera(id, new CameraDevice.StateCallback() {
                @Override
                public void onOpened(CameraDevice camera) {
                    shot.camera = camera;
                    try {
                        camera.createCaptureSession(
                                Arrays.asList(previewSurface, shot.reader.getSurface()),
                                new CameraCaptureSession.StateCallback() {
                                    @Override
                                    public void onConfigured(CameraCaptureSession session) {
                                        shot.session = session;
                                        try {
                                            // ~600 ms of preview so AE/AF can settle,
                                            // then one still capture to the reader.
                                            CaptureRequest.Builder preview =
                                                    camera.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                                            preview.addTarget(previewSurface);
                                            preview.set(CaptureRequest.CONTROL_AF_MODE,
                                                    CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                            session.setRepeatingRequest(preview.build(), null, h);
                                            h.postDelayed(() -> {
                                                try {
                                                    CaptureRequest.Builder still =
                                                            camera.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
                                                    still.addTarget(shot.reader.getSurface());
                                                    still.set(CaptureRequest.CONTROL_AF_MODE,
                                                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);
                                                    still.set(CaptureRequest.JPEG_ORIENTATION, orientation);
                                                    session.capture(still.build(),
                                                            new CameraCaptureSession.CaptureCallback() {
                                                                @Override
                                                                public void onCaptureFailed(
                                                                        CameraCaptureSession s,
                                                                        CaptureRequest req,
                                                                        android.hardware.camera2.CaptureFailure failure) {
                                                                    Log.w(TAG, "Capture failed for " + label);
                                                                    done.countDown();
                                                                }
                                                            }, h);
                                                } catch (Exception e) {
                                                    Log.w(TAG, "Still capture failed for " + label, e);
                                                    done.countDown();
                                                }
                                            }, 600);
                                        } catch (Exception e) {
                                            Log.w(TAG, "Preview start failed for " + label, e);
                                            done.countDown();
                                        }
                                    }

                                    @Override
                                    public void onConfigureFailed(CameraCaptureSession session) {
                                        Log.w(TAG, "Session configure failed for " + label);
                                        done.countDown();
                                    }
                                }, h);
                    } catch (Exception e) {
                        Log.w(TAG, "Session create failed for " + label, e);
                        done.countDown();
                    }
                }

                @Override
                public void onDisconnected(CameraDevice camera) {
                    camera.close();
                    done.countDown();
                }

                @Override
                public void onError(CameraDevice camera, int error) {
                    Log.w(TAG, "Camera error " + error + " for " + label);
                    camera.close();
                    done.countDown();
                }
            }, h);
        } catch (CameraAccessException | SecurityException e) {
            Log.w(TAG, "openCamera failed for " + label, e);
            shot.closeAll();
            return false;
        } catch (Exception e) {
            Log.w(TAG, "Snapshot setup failed for " + label, e);
            shot.closeAll();
            return false;
        }
        try {
            // Hard cap per camera so a wedged HAL can never stall the pair.
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        shot.closeAll();
        if (!shot.saved) {
            Log.w(TAG, "No image captured for " + label);
        }
        return shot.saved;
    }
}
