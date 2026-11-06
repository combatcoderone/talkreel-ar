package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

// Pro framing guide. This replaces all basic AR.
// No cartoon ears, glasses, glow, beauty tint, or black circle.
// Just a thin white guide circle, rule of thirds lines, and a
// small face box when a face is found. Fully transparent,
// so the camera is always visible.
public class ProFrameView extends View {
    private List<Rect> faces = new ArrayList<Rect>();
    private Paint linePaint = new Paint();
    private Paint circlePaint = new Paint();
    private Paint facePaint = new Paint();

    public ProFrameView(Context context) { super(context); init(); }
    public ProFrameView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        linePaint.setColor(Color.argb(90, 255, 255, 255));
        linePaint.setStrokeWidth(2);
        linePaint.setAntiAlias(true);
        circlePaint.setColor(Color.argb(160, 255, 255, 255));
        circlePaint.setStyle(Paint.Style.STROKE);
        circlePaint.setStrokeWidth(3);
        circlePaint.setAntiAlias(true);
        facePaint.setColor(Color.argb(200, 255, 158, 43));
        facePaint.setStyle(Paint.Style.STROKE);
        facePaint.setStrokeWidth(4);
        facePaint.setAntiAlias(true);
    }

    public void setFaces(List<Rect> newFaces) {
        faces = newFaces;
        AppState.faceFound = !newFaces.isEmpty();
        postInvalidate();
    }

    public void clearFaces() {
        faces = new ArrayList<Rect>();
        AppState.faceFound = false;
        postInvalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!AppState.showFrame) return;
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;
        // Rule of thirds.
        canvas.drawLine(w / 3f, 0, w / 3f, h, linePaint);
        canvas.drawLine(2 * w / 3f, 0, 2 * w / 3f, h, linePaint);
        canvas.drawLine(0, h / 3f, w, h / 3f, linePaint);
        canvas.drawLine(0, 2 * h / 3f, w, 2 * h / 3f, linePaint);
        // Clean guide circle, centred on the face area, no dim, no fill.
        float cx = w / 2f;
        float cy = h * 0.42f;
        float r = Math.min(w, h) * 0.36f;
        if (!faces.isEmpty()) {
            Rect f = faces.get(0);
            cx = f.centerX();
            cy = f.centerY();
        }
        canvas.drawCircle(cx, cy, r, circlePaint);
        if (!faces.isEmpty()) {
            Rect f = faces.get(0);
            canvas.drawRect(f.left, f.top, f.right, f.bottom, facePaint);
        }
    }
}
