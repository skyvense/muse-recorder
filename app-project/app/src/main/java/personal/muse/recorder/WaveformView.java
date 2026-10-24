package personal.muse.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Handler;
import android.os.Looper;
import android.util.AttributeSet;
import android.view.View;

/**
 * Live waveform fed by the recorder's real microphone amplitude.
 * Bars scroll right-to-left; amplitude is smoothed so speech looks alive
 * without jittering on every sample.
 */
public class WaveformView extends View {
    private static final int BARS = 42;
    private final float[] bars = new float[BARS];
    private int head = 0;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean animating = false;
    private float smoothed = 0f;

    /** Optional alternate amplitude source (e.g. a press-and-hold note recorder). */
    public static volatile java.util.function.IntSupplier amplitudeOverride = null;

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            pushSample();
            invalidate();
            if (animating) handler.postDelayed(this, 90);
        }
    };

    public WaveformView(Context ctx) {
        super(ctx);
        init();
    }

    public WaveformView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(0xFFFF3B30);
        paint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void startAnimating() {
        if (!animating) {
            animating = true;
            handler.post(tick);
        }
    }

    public void stopAnimating() {
        animating = false;
        handler.removeCallbacks(tick);
    }

    private void pushSample() {
        java.util.function.IntSupplier override = amplitudeOverride;
        int amp = override != null ? override.getAsInt() : RecordingService.pollMaxAmplitude();
        // Map onto a decibel scale: raw peak amplitude makes speech look tiny
        // next to the 32767 maximum, but on a dB scale normal speech fills
        // most of the range, which is what makes the bars jump.
        float ratio = Math.min(1f, amp / 32767f);
        float db = ratio > 0.0001f ? (float) (20 * Math.log10(ratio)) : -60f;
        float target = (db + 44f) / 44f;              // -44 dB floor → 0..1
        target = Math.max(0f, Math.min(1f, target));
        target = (float) Math.pow(target, 0.65f);     // lift quiet sounds
        // instant attack, quick release: lively, not floaty
        smoothed = target > smoothed ? target : smoothed * 0.55f + target * 0.45f;
        bars[head] = 0.06f + smoothed * 0.94f;
        head = (head + 1) % BARS;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float gap = w * 0.012f;
        float barW = (w - gap * (BARS - 1)) / BARS;
        paint.setStrokeWidth(barW);
        float midY = h / 2f;
        float maxHalf = h * 0.46f;
        for (int i = 0; i < BARS; i++) {
            float v = bars[(head + i) % BARS];
            float half = Math.max(barW * 0.35f, v * maxHalf);
            float x = i * (barW + gap) + barW / 2f;
            canvas.drawLine(x, midY - half, x, midY + half, paint);
        }
    }
}
