package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Transparent guides and selfie-frame layer. Face effects are rendered by ArCompositor
 * so preview and encoded video use the same camera frames and shader output. */
public class ProFrameView extends View {
    public static final int FILTER_GUIDE=0, FILTER_GLOW=1, FILTER_GLASSES=2,
        FILTER_BUNNY=3, FILTER_CROWN=4, FILTER_NEON=5, FILTER_BEAUTY=6,
        FILTER_MAKEUP=7, FILTER_MASK=8, FILTER_CAT=9, FILTER_DOG=10, FILTER_COUNT=11;
    private boolean faceFound = false;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int filter = FILTER_GUIDE;
    private int frameShape = 0;
    public void setFrameShape(int shape) { frameShape = Math.max(0, Math.min(3, shape)); invalidate(); }
    public int getFrameShape() { return frameShape; }

    public ProFrameView(Context c) { super(c); }
    public ProFrameView(Context c, AttributeSet a) { super(c,a); }
    public void setFilter(int v) { filter=((v%FILTER_COUNT)+FILTER_COUNT)%FILTER_COUNT; invalidate(); }
    public int getFilter() { return filter; }
    public static String filterName(int v) {
        switch(v) {
            case FILTER_GLOW:return "Glow"; case FILTER_GLASSES:return "Glasses";
            case FILTER_BUNNY:return "Bunny"; case FILTER_CROWN:return "Crown";
            case FILTER_NEON:return "Neon"; case FILTER_BEAUTY:return "Beauty";
            case FILTER_MAKEUP:return "Makeup"; case FILTER_MASK:return "Mask";
            case FILTER_CAT:return "Cat"; case FILTER_DOG:return "Dog"; default:return "Guide";
        }
    }
    /** Tracks the face-detection state for status text; AR itself is drawn by the compositor. */
    public void setFaceFound(boolean found) {
        if (faceFound == found) return;
        faceFound = found;
        AppState.faceFound = found;
        postInvalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w=getWidth(), h=getHeight(); if(w<=0||h<=0)return;
        if(AppState.showFrame) guides(c,w,h); else drawSelfieFrame(c,w,h);
    }
    private void guides(Canvas c,int w,int h) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.argb(65,255,255,255));
        c.drawLine(w/3f,0,w/3f,h,p); c.drawLine(2*w/3f,0,2*w/3f,h,p);
        c.drawLine(0,h/3f,w,h/3f,p); c.drawLine(0,2*h/3f,w,2*h/3f,p);
        p.setColor(Color.argb(145,255,255,255)); p.setStrokeWidth(3);
        drawSelfieFrame(c,w,h);
    }
    private void drawSelfieFrame(Canvas c,int w,int h) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.argb(145,255,255,255));
            float cx=w/2f, cy=h*.43f, fw=w*.66f, fh=h*.66f;
            RectF frame=new RectF(cx-fw/2f,cy-fh/2f,cx+fw/2f,cy+fh/2f);
            if(frameShape==1) c.drawOval(frame,p);
            else if(frameShape==2) {
                Path heart=new Path();
                heart.moveTo(cx,cy+fh*.43f);
                heart.cubicTo(cx-fw*.12f,cy+fh*.23f,cx-fw*.53f,cy-fh*.02f,cx-fw*.43f,cy-fh*.29f);
                heart.cubicTo(cx-fw*.34f,cy-fh*.52f,cx-fw*.08f,cy-fh*.47f,cx,cy-fh*.24f);
                heart.cubicTo(cx+fw*.08f,cy-fh*.47f,cx+fw*.34f,cy-fh*.52f,cx+fw*.43f,cy-fh*.29f);
                heart.cubicTo(cx+fw*.53f,cy-fh*.02f,cx+fw*.12f,cy+fh*.23f,cx,cy+fh*.43f);
                heart.close(); c.drawPath(heart,p);
            } else if(frameShape==3) c.drawOval(new RectF(cx-fw*.43f,cy-fh*.48f,cx+fw*.43f,cy+fh*.48f),p);
            else c.drawRoundRect(frame,18,18,p);
    }
}
