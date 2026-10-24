package personal.muse.recorder;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/**
 * iOS-Voice-Memos-style record button: a red circle inside a ring when idle,
 * morphing to a red rounded square while recording.
 */
public class RecordButtonView extends View {
    private final Paint ringPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF innerRect = new RectF();
    private boolean recording = false;

    public RecordButtonView(Context ctx) {
        super(ctx);
        init();
    }

    public RecordButtonView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        init();
    }

    private void init() {
        ringPaint.setStyle(Paint.Style.STROKE);
        ringPaint.setColor(0xFFC7C7CC);
        fillPaint.setStyle(Paint.Style.FILL);
        fillPaint.setColor(0xFFFF3B30);
        setContentDescription(getContext().getString(R.string.record_button_desc));
    }

    public void setRecording(boolean recording) {
        if (this.recording != recording) {
            this.recording = recording;
            invalidate();
        }
    }

    public boolean isRecordingState() {
        return recording;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float w = getWidth(), h = getHeight();
        float cx = w / 2f, cy = h / 2f;
        float density = getResources().getDisplayMetrics().density;
        ringPaint.setStrokeWidth(5f * density);
        float ringR = Math.min(w, h) / 2f - 4f * density;
        canvas.drawCircle(cx, cy, ringR, ringPaint);

        if (!recording) {
            canvas.drawCircle(cx, cy, ringR * 0.66f, fillPaint);
        } else {
            float half = ringR * 0.42f;
            float corner = ringR * 0.16f;
            innerRect.set(cx - half, cy - half, cx + half, cy + half);
            canvas.drawRoundRect(innerRect, corner, corner, fillPaint);
        }
    }
}
