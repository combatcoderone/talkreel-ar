package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

/** Minimal camera framing guide. All face effects are rendered by ArCompositor. */
public final class ProFrameView extends View {
    public static final int FILTER_GUIDE=0, FILTER_COUNT=30;
    private static final String[] LOOKS={
        "Natural Makeup","Soft Glam","Smoky Eye","Rose Gold","Bronze Glow",
        "Editorial Red","Nude Gloss","Berry Lip","Copper Shine","Bridal Glow",
        "Halloween Skull","Vampire","Pumpkin","Zombie","Witch","Ghost",
        "Demon","Spider Queen","Porcelain Doll","Neon Phantom",\n        "Crystal Glasses","Gold Crown","Pearl Jewelry","Cat Ears","Bunny Ears",\n        "Halo","Butterfly Wings","Cyber Visor","Star Freckles","Rainbow Face Paint"
    };
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private int preset=0,frameShape=0;
    public ProFrameView(Context c){super(c);}
    public ProFrameView(Context c,AttributeSet a){super(c,a);}
    public void setFrameShape(int shape){frameShape=Math.max(0,Math.min(3,shape));invalidate();}
    public int getFrameShape(){return frameShape;}
    public void setFilter(int value){preset=((value%FILTER_COUNT)+FILTER_COUNT)%FILTER_COUNT;invalidate();}
    public int getFilter(){return preset;}
    public static String filterName(int value){return LOOKS[(value%FILTER_COUNT+FILTER_COUNT)%FILTER_COUNT];}
    public void clearFaces(){AppState.faceFound=false;invalidate();}
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(!AppState.showFrame)return;
        float w=getWidth(),h=getHeight();if(w<=0||h<=0)return;
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.4f);
        paint.setColor(Color.argb(42,255,255,255));
        canvas.drawLine(w/3,0,w/3,h,paint);canvas.drawLine(2*w/3,0,2*w/3,h,paint);
        canvas.drawLine(0,h/3,w,h/3,paint);canvas.drawLine(0,2*h/3,w,h/3*2,paint);
        paint.setStrokeWidth(2f);paint.setColor(Color.argb(105,255,255,255));
        float cx=w/2f,cy=h*.43f,fw=w*.66f,fh=h*.66f;
        RectF r=new RectF(cx-fw/2,cy-fh/2,cx+fw/2,cy+fh/2);
        if(frameShape==1)canvas.drawOval(r,paint);
        else if(frameShape==2)canvas.drawOval(new RectF(cx-fw*.4f,cy-fh*.5f,cx+fw*.4f,cy+fh*.5f),paint);
        else canvas.drawRoundRect(r,24,24,paint);
    }
}
