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

    /** Short single tick: "a capture is starting" (v1.19.0). */
    public static void signalCaptureStart(Context ctx) {
        vibratePattern(ctx, new long[]{0, 40});
    }

    /** Double pulse: "raise seen, but still in cooldown" (v1.19.0). */
    public static void signalCooldown(Context ctx) {
        vibratePattern(ctx, new long[]{0, 40, 90, 40});
    }

    private static void vibratePattern(Context ctx, long[] timings) {
        try {
            Vibrator v = (Vibrator) ctx.getSystemService(Context.VIBRATOR_SERVICE);
            if (v == null) return;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                v.vibrate(VibrationEffect.createWaveform(timings, -1));
            } else if (timings.length == 2) {
                //noinspection deprecation
                v.vibrate(timings[1]);
            }
        } catch (Exception ignored) {}
    }

    public static void capturePair(Context ctx) {
        if (!CAPTURING.compareAndSet(false, true)) return;
        final Context app = ctx.getApplicationContext();
        // Vibration semantics: up to v1.9.1 the buzz fired at trigger time,
        // so attempts the system then blocked still buzzed — the user felt
        // far more buzzes than photos. From v1.9.2 the per-save tick in
        // attempt() means "a photo was actually saved", and the event
        // marker follows the same rule. v1.19.0 adds one distinct signal
        // on top, at the user's request: signalCaptureStart above says
        // only "starting" — a start tick with no save tick after it means
        // the attempt failed or the frame was filtered as blank.
        signalCaptureStart(app);
        Thread t = new Thread(() -> {
            HandlerThread ht = new HandlerThread("muse-snapshot");
            ht.start();
            Handler h = new Handler(ht.getLooper());
            String stamp = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US)
                    .format(new Date());
            int mode = Prefs.snapshotCameraMode(app); // 0=both, 1=back, 2=front
            int saved = 0;
            int filtered = 0;
            try {
                if (mode != 2) {
                    int r = attempt(app, h, CameraMetadata.LENS_FACING_BACK, stamp, "back");
                    if (r == RESULT_SAVED) saved++; else if (r == RESULT_FILTERED) filtered++;
                }
                if (mode != 1) {
                    // Give the HAL a moment to fully release the first
                    // camera; opening the next one too early was losing
                    // the front shot on some attempts.
                    try { Thread.sleep(300); } catch (InterruptedException ignored) {}
                    int r = attempt(app, h, CameraMetadata.LENS_FACING_FRONT, stamp, "front");
                    if (r == RESULT_SAVED) saved++; else if (r == RESULT_FILTERED) filtered++;
                }
            } finally {
                lastSnapshotAtMs = System.currentTimeMillis();
                if (saved > 0) EventLog.add(app, EventLog.SNAPSHOT);
                // Filtered frames get their own marker (one per frame) so
                // the daily pipeline can still report how many snapshots
                // were taken and dropped on-device as blank.
                for (int i = 0; i < filtered; i++) EventLog.add(app, EventLog.SNAPSHOT_FILTERED);
                CAPTURING.set(false);
                ht.quitSafely();
                Uploader.kick(app);
            }
        }, "muse-snapshot-pair");
        t.start();
    }

    // captureOne() outcomes.
    private static final int RESULT_FAILED = 0;
    private static final int RESULT_SAVED = 1;
    private static final int RESULT_FILTERED = 2; // captured fine, dropped as blank

    /**
     * One camera attempt; on a fast failure (camera busy / blocked, the
     * attempt returned in under 5 s without an image) retries once after a
     * short pause. A slow failure (timeout) is not retried, to bound the
     * total time a pair can take. A filtered (blank) frame is a successful
     * capture, so it is never retried and never buzzes.
     */
    private static int attempt(Context app, Handler h, int facing, String stamp, String label) {
        long t0 = System.currentTimeMillis();
        int r = captureOne(app, h, facing, stamp, label);
        if (r == RESULT_FAILED && System.currentTimeMillis() - t0 < 5_000) {
            try { Thread.sleep(400); } catch (InterruptedException ignored) {}
            r = captureOne(app, h, facing, stamp, label);
        }
        if (r == RESULT_SAVED) vibrateTick(app);
        return r;
    }

    /**
     * Scores a captured JPEG on-device and reports whether it is blank:
     * near-black, or detail-free (lens covered / in a pocket / badly
     * blurred). Mirrors the receiver-side score in snapshot_sample.py with
     * the same thresholds calibrated on real frames (real scenes: gradient
     * >= ~6, Laplacian variance in the hundreds; covered/blurry frames:
     * gradient < 2, Laplacian variance < 60). Runs on a copy downscaled to
     * ~256 px, so it costs a few ms — far less than the JPEG encode and
     * upload it can save. Any scoring error means "keep the photo".
     */
    private static boolean looksBlank(byte[] jpeg) {
        android.graphics.Bitmap bmp = null;
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 256
                    || bounds.outHeight / (sample * 2) >= 256) sample *= 2;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return false;
            int w = bmp.getWidth(), ht = bmp.getHeight();
            if (w < 3 || ht < 3) return false;
            int[] px = new int[w * ht];
            bmp.getPixels(px, 0, w, 0, 0, w, ht);
            double[] g = new double[w * ht];
            double sum = 0;
            for (int i = 0; i < px.length; i++) {
                int c = px[i];
                double lum = 0.299 * ((c >> 16) & 0xFF)
                        + 0.587 * ((c >> 8) & 0xFF)
                        + 0.114 * (c & 0xFF);
                g[i] = lum;
                sum += lum;
            }
            double brightness = sum / g.length;
            double gradSum = 0;
            long gradN = 0;
            for (int y = 0; y < ht; y++) {
                for (int x = 0; x < w - 1; x++) {
                    gradSum += Math.abs(g[y * w + x + 1] - g[y * w + x]);
                    gradN++;
                }
            }
            for (int y = 0; y < ht - 1; y++) {
                for (int x = 0; x < w; x++) {
                    gradSum += Math.abs(g[(y + 1) * w + x] - g[y * w + x]);
                    gradN++;
                }
            }
            double grad = gradN == 0 ? 0 : gradSum / gradN;
            double lapMean = 0;
            long lapN = 0;
            for (int y = 1; y < ht - 1; y++) {
                for (int x = 1; x < w - 1; x++) {
                    lapMean += -4 * g[y * w + x] + g[(y - 1) * w + x] + g[(y + 1) * w + x]
                            + g[y * w + x - 1] + g[y * w + x + 1];
                    lapN++;
                }
            }
            double lapVar = 0;
            if (lapN > 0) {
                lapMean /= lapN;
                for (int y = 1; y < ht - 1; y++) {
                    for (int x = 1; x < w - 1; x++) {
                        double lap = -4 * g[y * w + x] + g[(y - 1) * w + x]
                                + g[(y + 1) * w + x] + g[y * w + x - 1] + g[y * w + x + 1];
                        lapVar += (lap - lapMean) * (lap - lapMean);
                    }
                }
                lapVar /= lapN;
            }
            boolean blank = brightness < 12 || grad < 2.0 || lapVar < 60;
            Log.i(TAG, "Snapshot score: brightness=" + String.format(Locale.US, "%.1f", brightness)
                    + " grad=" + String.format(Locale.US, "%.2f", grad)
                    + " lapVar=" + String.format(Locale.US, "%.0f", lapVar)
                    + " blank=" + blank);
            return blank;
        } catch (Exception e) {
            Log.w(TAG, "Blank scoring failed; keeping photo", e);
            return false;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /**
     * Shrinks a captured JPEG for upload (v1.17.0, Zhang's request): the
     * photos only feed rough scene recognition, so ~1024 px wide and
     * <= ~500 KB is plenty, versus several MB for the original.
     *
     * Re-encoding through a Bitmap drops the EXIF orientation tag, so the
     * pixels are physically rotated by the capture's JPEG orientation
     * first — the result displays upright everywhere with no EXIF at all
     * (this also makes the shrunk file immune to the front-camera EXIF
     * history). Quality steps down (then width, once) until the size fits.
     * Any failure returns the original bytes unchanged.
     */
    private static byte[] shrinkForUpload(byte[] jpeg, int orientationDeg) {
        android.graphics.Bitmap bmp = null;
        try {
            android.graphics.BitmapFactory.Options bounds =
                    new android.graphics.BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return jpeg;
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= 2048) sample *= 2;
            android.graphics.BitmapFactory.Options opts =
                    new android.graphics.BitmapFactory.Options();
            opts.inSampleSize = sample;
            bmp = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, opts);
            if (bmp == null) return jpeg;

            if (orientationDeg % 360 != 0) {
                android.graphics.Matrix m = new android.graphics.Matrix();
                m.postRotate(orientationDeg);
                android.graphics.Bitmap rotated = android.graphics.Bitmap
                        .createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
                if (rotated != bmp) {
                    bmp.recycle();
                    bmp = rotated;
                }
            }

            int[][] plans = { {1024, 85}, {1024, 70}, {1024, 55}, {800, 60}, {800, 45} };
            byte[] best = null;
            for (int[] plan : plans) {
                android.graphics.Bitmap scaled = bmp;
                if (bmp.getWidth() > plan[0]) {
                    int w = plan[0];
                    int h = Math.max(1, Math.round((float) bmp.getHeight() * w / bmp.getWidth()));
                    scaled = android.graphics.Bitmap.createScaledBitmap(bmp, w, h, true);
                }
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, plan[1], bos);
                if (scaled != bmp) scaled.recycle();
                best = bos.toByteArray();
                if (best.length <= 500 * 1024) break;
            }
            return best != null ? best : jpeg;
        } catch (Exception e) {
            Log.w(TAG, "Snapshot shrink failed; keeping original", e);
            return jpeg;
        } finally {
            if (bmp != null) bmp.recycle();
        }
    }

    /**
     * Per-save buzz: "a photo was actually saved" (since v1.9.2). One
     * longer buzz (120 ms) so it reads differently from the short 40 ms
     * capture-start tick and from the cooldown double pulse.
     */
    private static void vibrateTick(Context ctx) {
        vibratePattern(ctx, new long[]{0, 120});
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
            // Front: sensor + device (Android docs: display rotation acts
            // clockwise for front cameras). The old code returned
            // (360 - (sensor + device)) % 360 instead, which on this phone
            // (front sensor 270, device 0) wrote 90 (EXIF 6, same as the
            // back camera) instead of 270 (EXIF 8) — front photos came out
            // 180 degrees off. Verified against the 2026-11-06 19:19 pair.
            return (sensorDeg + deviceDeg) % 360;
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
        volatile boolean filtered = false;

        void closeAll() {
            try { if (session != null) session.close(); } catch (Exception ignored) {}
            try { if (camera != null) camera.close(); } catch (Exception ignored) {}
            try { if (reader != null) reader.close(); } catch (Exception ignored) {}
            try { if (texture != null) texture.release(); } catch (Exception ignored) {}
            session = null; camera = null; reader = null; texture = null;
        }
    }

    private static int captureOne(Context app, Handler h, int facing, String stamp, String label) {
        CameraManager cm = (CameraManager) app.getSystemService(Context.CAMERA_SERVICE);
        if (cm == null) return RESULT_FAILED;
        String id = findCamera(cm, facing);
        if (id == null) {
            Log.i(TAG, "No camera with facing " + facing + "; skipping " + label);
            return RESULT_FAILED;
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
                    if (image == null || shot.saved || shot.filtered) return;
                    ByteBuffer buf = image.getPlanes()[0].getBuffer();
                    byte[] bytes = new byte[buf.remaining()];
                    buf.get(bytes);
                    // On-device blank filter (v1.17.0): score the JPEG in
                    // memory; a blank frame is never written, so it is
                    // never uploaded either.
                    if (Prefs.snapshotFilterEnabled(app) && looksBlank(bytes)) {
                        shot.filtered = true;
                        Log.i(TAG, "Snapshot filtered as blank: " + label
                                + " (" + bytes.length + " bytes not saved)");
                        return;
                    }
                    // Snapshots only feed rough scene recognition, so the
                    // multi-MB original is wasteful: shrink to ~1024 px
                    // wide / <= ~500 KB before saving (upload sees only
                    // the shrunk file).
                    byte[] uploadBytes = shrinkForUpload(bytes, orientation);
                    try (FileOutputStream fos = new FileOutputStream(out)) {
                        fos.write(uploadBytes);
                    }
                    shot.saved = true;
                    Log.i(TAG, "Saved snapshot " + out.getName() + " (" + uploadBytes.length
                            + " bytes, original " + bytes.length + ")");
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
            return RESULT_FAILED;
        } catch (Exception e) {
            Log.w(TAG, "Snapshot setup failed for " + label, e);
            shot.closeAll();
            return RESULT_FAILED;
        }
        try {
            // Hard cap per camera so a wedged HAL can never stall the pair.
            done.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {}
        shot.closeAll();
        if (shot.filtered) return RESULT_FILTERED;
        if (!shot.saved) {
            Log.w(TAG, "No image captured for " + label);
            return RESULT_FAILED;
        }
        return RESULT_SAVED;
    }
}
