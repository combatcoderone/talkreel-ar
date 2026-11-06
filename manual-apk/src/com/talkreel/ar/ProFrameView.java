package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/**
 * Lightweight live framing assistant. It deliberately uses Camera2 face metadata
 * rather than pretending to apply beauty/segmentation effects that this camera
 * pipeline does not support. All drawing is preview-only and does not affect
 * encoded video or add load to the recording encoder.
 */
public class ProFrameView extends View {
    private final List<Rect> faces = new ArrayList<Rect>();
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint accent = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint facePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint chip = new Paint(Paint.ANTI_ALIAS_FLAG);
    private RectF smoothedFace;
    private long lastUpdate;

    public ProFrameView(Context context) { super(context); init(); }
    public ProFrameView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        setWillNotDraw(false);
        guide.setColor(0xA6FFFFFF);
        guide.setStrokeWidth(dp(1));
        guide.setStyle(Paint.Style.STROKE);
        accent.setColor(0xFFFFB74D);
        accent.setStrokeWidth(dp(2));
        accent.setStyle(Paint.Style.STROKE);
        facePaint.setColor(0xFFFFB74D);
        facePaint.setStrokeWidth(dp(3));
        facePaint.setStyle(Paint.Style.STROKE);
        text.setColor(Color.WHITE);
        text.setTextSize(dp(11));
        text.setTypeface(Typeface.create("sans-serif-medium", Typeface.BOLD));
        chip.setColor(0xCC101820);
        chip.setStyle(Paint.Style.FILL);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    public synchronized void setFaces(List<Rect> detected) {
        faces.clear();
        if (detected != null) {
            for (Rect r : detected) if (r != null && r.width() > 0 && r.height() > 0) faces.add(new Rect(r));
        }
        AppState.faceFound = !faces.isEmpty();
        postInvalidateOnAnimation();
    }

    public synchronized void clearFaces() {
        faces.clear();
        AppState.faceFound = false;
        postInvalidateOnAnimation();
    }

    @Override
    protected synchronized void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!AppState.showFrame) return;
        float w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;

        float inset = dp(18);
        RectF safe = new RectF(inset, inset, w - inset, h - inset);
        guide.setAlpha(125);
        canvas.drawRoundRect(safe, dp(14), dp(14), guide);

        // Thirds grid, deliberately subtle.
        guide.setAlpha(65);
        canvas.drawLine(w / 3f, inset, w / 3f, h - inset, guide);
        canvas.drawLine(2f * w / 3f, inset, 2f * w / 3f, h - inset, guide);
        guide.setAlpha(85);
        canvas.drawLine(inset, h / 3f, w - inset, h / 3f, guide);
        canvas.drawLine(inset, 2f * h / 3f, w - inset, 2f * h / 3f, guide);

        // Eye-line and headroom references for portrait video.
        accent.setAlpha(145);
        canvas.drawLine(w * 0.18f, h * 0.34f, w * 0.82f, h * 0.34f, accent);
        canvas.drawLine(w * 0.18f, h * 0.76f, w * 0.82f, h * 0.76f, accent);

        Rect current = faces.isEmpty() ? null : faces.get(0);
        if (current != null) {
            RectF target = new RectF(current);
            if (smoothedFace == null) smoothedFace = new RectF(target);
            else {
                // Exponential smoothing reduces jitter without lagging far behind motion.
                smoothedFace.left += (target.left - smoothedFace.left) * 0.42f;
                smoothedFace.top += (target.top - smoothedFace.top) * 0.42f;
                smoothedFace.right += (target.right - smoothedFace.right) * 0.42f;
                smoothedFace.bottom += (target.bottom - smoothedFace.bottom) * 0.42f;
            }
            lastUpdate = System.currentTimeMillis();
            drawFaceBrackets(canvas, smoothedFace);

            float centerX = smoothedFace.centerX();
            float centerY = smoothedFace.centerY();
            float idealX = w * 0.5f;
            float idealY = h * 0.39f;
            boolean centered = Math.abs(centerX - idealX) < w * 0.13f
                    && centerY > h * 0.20f && centerY < h * 0.58f;
            drawStatus(canvas, centered ? "GOOD FRAMING" : "ADJUST POSITION", centered);
            // Small eye-level marker, not a fake facial filter.
            accent.setAlpha(230);
            canvas.drawCircle(centerX, smoothedFace.top + smoothedFace.height() * 0.38f, dp(3), accent);
        } else {
            if (smoothedFace != null && System.currentTimeMillis() - lastUpdate > 450) smoothedFace = null;
            drawStatus(canvas, "PLACE FACE IN FRAME", false);
            drawTarget(canvas, w * 0.5f, h * 0.40f);
        }
    }

    private void drawFaceBrackets(Canvas canvas, RectF r) {
        float pad = dp(8);
        RectF b = new RectF(r.left - pad, r.top - pad, r.right + pad, r.bottom + pad);
        float corner = Math.min(dp(18), Math.min(b.width(), b.height()) * 0.22f);
        facePaint.setAlpha(245);
        Path p = new Path();
        // Eight short corner strokes create a tracking-box treatment.
        float len = corner;
        p.moveTo(b.left, b.top + len); p.lineTo(b.left, b.top); p.lineTo(b.left + len, b.top);
        p.moveTo(b.right - len, b.top); p.lineTo(b.right, b.top); p.lineTo(b.right, b.top + len);
        p.moveTo(b.right, b.bottom - len); p.lineTo(b.right, b.bottom); p.lineTo(b.right - len, b.bottom);
        p.moveTo(b.left + len, b.bottom); p.lineTo(b.left, b.bottom); p.lineTo(b.left, b.bottom - len);
        canvas.drawPath(p, facePaint);
    }

    private void drawTarget(Canvas canvas, float x, float y) {
        accent.setAlpha(190);
        float r = dp(24);
        canvas.drawCircle(x, y, r, accent);
        canvas.drawLine(x - dp(34), y, x - dp(12), y, accent);
        canvas.drawLine(x + dp(12), y, x + dp(34), y, accent);
        canvas.drawLine(x, y - dp(34), x, y - dp(12), accent);
        canvas.drawLine(x, y + dp(12), x, y + dp(34), accent);
    }

    private void drawStatus(Canvas canvas, String label, boolean good) {
        float left = dp(14), top = dp(14);
        float width = text.measureText(label) + dp(26);
        RectF r = new RectF(left, top, left + width, top + dp(30));
        chip.setColor(good ? 0xD91B5E20 : 0xD92A3038);
        canvas.drawRoundRect(r, dp(15), dp(15), chip);
        text.setColor(good ? 0xFFB9F6CA : Color.WHITE);
        canvas.drawText(label, left + dp(13), top + dp(20), text);
    }
}
