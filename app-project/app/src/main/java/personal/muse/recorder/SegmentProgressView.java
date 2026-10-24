package personal.muse.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * Full-width segment progress bar for the recording status line.
 * Rounded track, red fill proportional to elapsed / segment length, and the
 * "elapsed/total" seconds text drawn over it: dark on the empty track, white
 * on the filled part (clipped to the fill, so the text stays readable on
 * both sides of the boundary).
 */
public class SegmentProgressView extends View {
    private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textDarkPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textLightPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF bgRect = new RectF();
    private final Path bgPath = new Path();
    private long elapsedSec = 0;
    private long totalSec = 0;
    private String unit = "s";

    public SegmentProgressView(Context ctx) {
        super(ctx);
        init();
    }

    public SegmentProgressView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        bgPaint.setStyle(Paint.Style.FILL);
        bgPaint.setColor(0xFFE0E0E6);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFFFF3B30);
        float density = getResources().getDisplayMetrics().density;
        textDarkPaint.setColor(0xFF1C1C1E);
        textDarkPaint.setTextSize(13f * density);
        textDarkPaint.setFakeBoldText(true);
        textDarkPaint.setTextAlign(Paint.Align.CENTER);
        textLightPaint.setColor(0xFFFFFFFF);
        textLightPaint.setTextSize(13f * density);
        textLightPaint.setFakeBoldText(true);
        textLightPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setProgress(long elapsedSec, long totalSec, String unit) {
        if (this.elapsedSec != elapsedSec || this.totalSec != totalSec
                || !unit.equals(this.unit)) {
            this.elapsedSec = elapsedSec;
            this.totalSec = totalSec;
            this.unit = unit;
            invalidate();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        float r = h / 2f;
        bgRect.set(0, 0, w, h);
        canvas.drawRoundRect(bgRect, r, r, bgPaint);

        float frac = totalSec > 0 ? Math.min(1f, elapsedSec / (float) totalSec) : 0f;
        float fillW = w * frac;
        String text = elapsedSec + "/" + totalSec + unit;
        float x = w / 2f;
        float y = h / 2f - (textDarkPaint.descent() + textDarkPaint.ascent()) / 2f;
        canvas.drawText(text, x, y, textDarkPaint);
        if (fillW > 0) {
            canvas.save();
            bgPath.reset();
            bgPath.addRoundRect(bgRect, r, r, Path.Direction.CW);
            canvas.clipPath(bgPath);
            canvas.clipRect(0, 0, fillW, h);
            canvas.drawRect(0, 0, fillW, h, fillPaint);
            canvas.drawText(text, x, y, textLightPaint);
            canvas.restore();
        }
    }
}
