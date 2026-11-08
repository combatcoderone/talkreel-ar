package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Transparent guides and selfie-frame layer. Face effects are rendered by ArCompositor
 * so preview and encoded video use the same camera frames and shader output. */
public class ProFrameView extends View {
    public static final int FILTER_GUIDE=0, FILTER_WARM=1, FILTER_COOL=2,
        FILTER_CINEMATIC=3, FILTER_SOFT=4, FILTER_MONO=5, FILTER_COUNT=6;
    private List<Rect> faces = new ArrayList<Rect>();
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
            case FILTER_WARM:return "Warm";
            case FILTER_COOL:return "Cool";
            case FILTER_CINEMATIC:return "Cinematic";
            case FILTER_SOFT:return "Soft";
            case FILTER_MONO:return "Monochrome";
            default:return "Natural";
        }
    }
    public void setFaces(List<Rect> f) {
        faces=f==null?new ArrayList<Rect>():new ArrayList<Rect>(f);
        AppState.faceFound=!faces.isEmpty(); postInvalidate();
    }
    public void clearFaces() { faces=new ArrayList<Rect>(); AppState.faceFound=false; postInvalidate(); }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        int w=getWidth(), h=getHeight(); if(w<=0||h<=0)return;
        if(AppState.showFrame) guides(c,w,h); else drawSelfieFrame(c,w,h);
        // Face detection is used for framing feedback only; no cartoon face stickers.
    }
    private void guides(Canvas c,int w,int h) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.argb(65,255,255,255));
        c.drawLine(w/3f,0,w/3f,h,p); c.drawLine(2*w/3f,0,2*w/3f,h,p);
        c.drawLine(0,h/3f,w,h/3f,p); c.drawLine(0,2*h/3f,w,2*h/3f,p);
        p.setColor(Color.argb(145,255,255,255)); p.setStrokeWidth(3);
        drawSelfieFrame(c,w,h);
    }
    private void drawSelfieFrame(Canvas c,int w,int h) {
        p.setStyle(Paint.Style.STROKE);
        p.setStrokeWidth(2.5f);
        p.setColor(Color.argb(125,255,255,255));
        float cx=w/2f, cy=h*.43f, fw=w*.66f, fh=h*.66f;
        RectF frame=new RectF(cx-fw/2f,cy-fh/2f,cx+fw/2f,cy+fh/2f);
        if(frameShape==1) c.drawOval(frame,p);
        else if(frameShape==2) c.drawOval(new RectF(cx-fw*.40f,cy-fh*.50f,cx+fw*.40f,cy+fh*.50f),p);
        else if(frameShape==3) c.drawRoundRect(new RectF(cx-fw*.40f,cy-fh*.50f,cx+fw*.40f,cy+fh*.50f),24,24,p);
        else c.drawRoundRect(frame,24,24,p);
    }
}
