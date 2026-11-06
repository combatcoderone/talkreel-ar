package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import java.util.ArrayList;
import java.util.List;

/** Snapchat-inspired face effects drawn on the transparent live-preview layer.
 * The MediaRecorder/camera pipeline is intentionally left unchanged. */
public class ProFrameView extends View {
    public static final int FILTER_GUIDE=0, FILTER_GLOW=1, FILTER_GLASSES=2,
        FILTER_BUNNY=3, FILTER_CROWN=4, FILTER_NEON=5, FILTER_COUNT=6;
    private List<Rect> faces = new ArrayList<Rect>();
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int filter = FILTER_GUIDE;

    public ProFrameView(Context c) { super(c); }
    public ProFrameView(Context c, AttributeSet a) { super(c,a); }
    public void setFilter(int v) { filter=((v%FILTER_COUNT)+FILTER_COUNT)%FILTER_COUNT; invalidate(); }
    public int getFilter() { return filter; }
    public static String filterName(int v) {
        switch(v) {
            case FILTER_GLOW:return "Glow"; case FILTER_GLASSES:return "Glasses";
            case FILTER_BUNNY:return "Bunny"; case FILTER_CROWN:return "Crown";
            case FILTER_NEON:return "Neon"; default:return "Guide";
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
        if(AppState.showFrame) guides(c,w,h);
        if(filter==FILTER_GUIDE||faces.isEmpty()) {
            if(AppState.showFrame&&!faces.isEmpty()) box(c,faces.get(0));
            return;
        }
        for(Rect face:faces) effect(c,face);
    }
    private void guides(Canvas c,int w,int h) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(2); p.setColor(Color.argb(65,255,255,255));
        c.drawLine(w/3f,0,w/3f,h,p); c.drawLine(2*w/3f,0,2*w/3f,h,p);
        c.drawLine(0,h/3f,w,h/3f,p); c.drawLine(0,2*h/3f,w,2*h/3f,p);
        p.setColor(Color.argb(145,255,255,255)); p.setStrokeWidth(3);
        c.drawCircle(w/2f,h*.42f,Math.min(w,h)*.32f,p);
    }
    private void box(Canvas c,Rect f) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(3); p.setColor(Color.argb(190,255,158,43));
        c.drawRoundRect(new RectF(f),18,18,p);
    }
    private void effect(Canvas c,Rect f) {
        float x=f.centerX(), y=f.centerY(), fw=Math.max(40,f.width()), fh=Math.max(50,f.height());
        switch(filter) {
            case FILTER_GLOW:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(5,fw*.045f));
                p.setColor(Color.argb(100,255,80,180)); c.drawCircle(x,y,fw*.62f,p);
                p.setStrokeWidth(2); p.setColor(Color.argb(220,255,225,245)); c.drawCircle(x,y,fw*.55f,p);
                sparkle(c,x-fw*.55f,y-fh*.3f,fw*.08f); sparkle(c,x+fw*.55f,y-fh*.1f,fw*.07f); break;
            case FILTER_GLASSES:
                float ey=y-fh*.1f, dx=fw*.22f;
                p.setStyle(Paint.Style.FILL); p.setColor(Color.argb(65,70,190,255));
                c.drawRoundRect(x-dx-fw*.16f,ey-fh*.07f,x-fw*.03f,ey+fh*.13f,16,16,p);
                c.drawRoundRect(x+fw*.03f,ey-fh*.07f,x+dx+fw*.16f,ey+fh*.13f,16,16,p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(4,fw*.035f)); p.setColor(Color.rgb(30,30,40));
                c.drawRoundRect(x-dx-fw*.16f,ey-fh*.07f,x-fw*.03f,ey+fh*.13f,16,16,p);
                c.drawRoundRect(x+fw*.03f,ey-fh*.07f,x+dx+fw*.16f,ey+fh*.13f,16,16,p);
                c.drawLine(x-fw*.03f,ey,x+fw*.03f,ey,p); break;
            case FILTER_BUNNY:
                p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(255,185,215));
                c.save(); c.rotate(-12,x-fw*.28f,y-fh*.55f); c.drawOval(x-fw*.48f,y-fh*1.05f,x-fw*.12f,y-fh*.3f,p); c.restore();
                c.save(); c.rotate(12,x+fw*.28f,y-fh*.55f); c.drawOval(x+fw*.12f,y-fh*1.05f,x+fw*.48f,y-fh*.3f,p); c.restore();
                p.setColor(Color.rgb(255,235,245));
                c.drawOval(x-fw*.41f,y-fh*.98f,x-fw*.19f,y-fh*.36f,p); c.drawOval(x+fw*.19f,y-fh*.98f,x+fw*.41f,y-fh*.36f,p);
                p.setColor(Color.argb(180,255,120,175)); c.drawCircle(x,y+fh*.22f,fw*.07f,p); break;
            case FILTER_CROWN:
                p.setStyle(Paint.Style.FILL); p.setColor(Color.rgb(255,205,45));
                Path crown=new Path(); crown.moveTo(x-fw*.48f,y-fh*.53f); crown.lineTo(x-fw*.38f,y-fh*.88f);
                crown.lineTo(x-fw*.12f,y-fh*.63f); crown.lineTo(x,y-fh*1.02f); crown.lineTo(x+fw*.14f,y-fh*.63f);
                crown.lineTo(x+fw*.40f,y-fh*.88f); crown.lineTo(x+fw*.49f,y-fh*.53f); crown.close(); c.drawPath(crown,p);
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(4); p.setColor(Color.rgb(255,245,170));
                c.drawLine(x-fw*.45f,y-fh*.52f,x+fw*.46f,y-fh*.52f,p); break;
            case FILTER_NEON:
                p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(6,fw*.055f)); p.setColor(Color.argb(110,0,235,255));
                RectF r=new RectF(f.left-fw*.12f,f.top-fh*.12f,f.right+fw*.12f,f.bottom+fh*.12f);
                c.drawRoundRect(r,fw*.35f,fw*.35f,p); p.setStrokeWidth(2); p.setColor(Color.rgb(190,255,255));
                c.drawRoundRect(r,fw*.35f,fw*.35f,p); sparkle(c,x-fw*.58f,y,fw*.07f); sparkle(c,x+fw*.58f,y-fh*.2f,fw*.07f); break;
        }
    }
    private void sparkle(Canvas c,float x,float y,float r) {
        p.setStyle(Paint.Style.STROKE); p.setStrokeWidth(Math.max(2,r*.22f)); p.setColor(Color.argb(230,255,255,255));
        c.drawLine(x-r,y,x+r,y,p); c.drawLine(x,y-r,x,y+r,p);
        c.drawLine(x-r*.65f,y-r*.65f,x+r*.65f,y+r*.65f,p);
    }
}
