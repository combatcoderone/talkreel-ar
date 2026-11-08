package com.talkreel.ar;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceContour;
import java.util.ArrayList;
import java.util.List;

/**
 * Camera framing and face-contour makeup renderer.
 * Effects in this class are 2D face-contour overlays on the live preview.
 * They are not 3D lenses and are not yet baked into the encoder stream.
 */
public class ProFrameView extends View {
    public static final int FILTER_GUIDE=0, FILTER_COUNT=20;
    private static final String[] LOOKS = {
        "Natural Makeup","Soft Glam","Smoky Eye","Rose Gold","Bronze Glow",
        "Editorial Red","Nude Gloss","Berry Lip","Copper Shine","Bridal Glow",
        "Halloween Skull","Vampire","Pumpkin","Zombie","Witch",
        "Ghost","Demon","Spider Queen","Porcelain Doll","Neon Phantom"
    };
    private List<Face> trackedFaces = new ArrayList<Face>();
    private List<Rect> legacyFaces = new ArrayList<Rect>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private int preset = 0;
    private int frameShape = 0;
    private int sourceWidth = 640, sourceHeight = 480, rotation = 0;
    private boolean mirrored = false;

    public ProFrameView(Context c) { super(c); }
    public ProFrameView(Context c, AttributeSet a) { super(c,a); }

    public void setFrameShape(int shape) { frameShape=Math.max(0,Math.min(3,shape)); invalidate(); }
    public int getFrameShape() { return frameShape; }
    public void setFilter(int value) { preset=((value%FILTER_COUNT)+FILTER_COUNT)%FILTER_COUNT; invalidate(); }
    public int getFilter() { return preset; }
    public static String filterName(int value) { return LOOKS[(value%FILTER_COUNT+FILTER_COUNT)%FILTER_COUNT]; }

    public void setFaceResults(List<Face> faces,int width,int height,int rotationDegrees,boolean mirror) {
        trackedFaces=faces==null?new ArrayList<Face>():new ArrayList<Face>(faces);
        sourceWidth=Math.max(1,width); sourceHeight=Math.max(1,height);
        rotation=((rotationDegrees%360)+360)%360; mirrored=mirror;
        AppState.faceFound=!trackedFaces.isEmpty(); postInvalidate();
    }
    public void setFaces(List<Rect> faces) {
        legacyFaces=faces==null?new ArrayList<Rect>():new ArrayList<Rect>(faces);
        AppState.faceFound=!legacyFaces.isEmpty(); postInvalidate();
    }
    public void clearFaces() {
        trackedFaces.clear(); legacyFaces.clear(); AppState.faceFound=false; postInvalidate();
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w=getWidth(),h=getHeight();
        if(w<=0||h<=0)return;
        if(AppState.showFrame) drawGuides(canvas,w,h);
        else drawSelfieFrame(canvas,w,h);
        if(!trackedFaces.isEmpty()) {
            for(Face face:trackedFaces) drawTrackedLook(canvas,face,w,h);
        } else if(!legacyFaces.isEmpty()) {
            // Legacy Camera2 rectangle detection is retained only as a fallback indicator.
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f);
            paint.setColor(Color.argb(100,255,255,255));
            for(Rect r:legacyFaces) canvas.drawRoundRect(new RectF(r),18,18,paint);
        }
    }

    private void drawGuides(Canvas c,int w,int h) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(1.5f);
        paint.setColor(Color.argb(45,255,255,255));
        c.drawLine(w/3f,0,w/3f,h,paint); c.drawLine(2*w/3f,0,2*w/3f,h,paint);
        c.drawLine(0,h/3f,w,h/3f,paint); c.drawLine(0,2*h/3f,w,2*h/3f,paint);
        drawSelfieFrame(c,w,h);
    }

    private void drawSelfieFrame(Canvas c,int w,int h) {
        paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2f);
        paint.setColor(Color.argb(100,255,255,255));
        float cx=w/2f,cy=h*.43f,fw=w*.66f,fh=h*.66f;
        RectF r=new RectF(cx-fw/2,cy-fh/2,cx+fw/2,cy+fh/2);
        if(frameShape==1)c.drawOval(r,paint);
        else if(frameShape==2)c.drawOval(new RectF(cx-fw*.4f,cy-fh*.5f,cx+fw*.4f,cy+fh*.5f),paint);
        else c.drawRoundRect(r,24,24,paint);
    }

    private int[] palette(int index) {
        switch(index) {
            case 1:return new int[]{0x66B05A83,0x99A52E52,0x44E9A0B8};
            case 2:return new int[]{0xAA25202F,0xCC6D1739,0x557A263F};
            case 3:return new int[]{0x77A84C83,0xAA9B2455,0x55E89AB7};
            case 4:return new int[]{0x778B4A2A,0xAA8D3E32,0x557D4A2E};
            case 5:return new int[]{0xAA6C1831,0xFF9E102B,0x557B1C2B};
            case 6:return new int[]{0x557D5C55,0xAA9B655D,0x44C88F86};
            case 7:return new int[]{0x886A244C,0xCC7D174B,0x557A2747};
            case 8:return new int[]{0x99814A27,0xCC9C3F2D,0x557C492B};
            case 9:return new int[]{0x66B47C91,0xCCB43E64,0x66E8C5D0};
            case 10:return new int[]{0xDD101010,0xFF202020,0x66505050};
            case 11:return new int[]{0xAA5C0B18,0xFF7E0D20,0x66520B18};
            case 12:return new int[]{0xAAE56B08,0xFFB84208,0x66E87C14};
            case 13:return new int[]{0xAA59664A,0xFF5D362B,0x665D7C4B};
            case 14:return new int[]{0xAA45306F,0xFF572B72,0x66513A78};
            case 15:return new int[]{0x88C8D5D9,0xFF879CA8,0x66D6E4E8};
            case 16:return new int[]{0xAA7B1111,0xFFB31919,0x667A1010};
            case 17:return new int[]{0xAA24142F,0xFF6B1C75,0x665D1B68};
            case 18:return new int[]{0xAAB5A3A0,0xFF9C243A,0x66E8D5D2};
            case 19:return new int[]{0xAA1A5B68,0xFF00A9C7,0x6600D9E8};
            default:return new int[]{0x447B5B4A,0xAA9B3B46,0x44D59A91};
        }
    }

    private void drawTrackedLook(Canvas c,Face face,int w,int h) {
        int[] colors=palette(preset);
        // Subtle shadow on both eye contours.
        drawContour(c,face,FaceContour.LEFT_EYE,w,h,colors[0],true);
        drawContour(c,face,FaceContour.RIGHT_EYE,w,h,colors[0],true);
        // Lip color follows upper and lower lip contours, not a fixed face rectangle.
        Path lips=new Path();
        appendContour(lips,face,FaceContour.UPPER_LIP_TOP,w,h,false);
        appendContour(lips,face,FaceContour.LOWER_LIP_BOTTOM,w,h,true);
        paint.setStyle(Paint.Style.FILL); paint.setColor(colors[1]); paint.setAlpha(155);
        if(!lips.isEmpty()) c.drawPath(lips,paint);
        // Blush is anchored to detected cheek landmarks.
        drawCheek(c,face,FaceLandmark.LEFT_CHEEK,w,h,colors[2]);
        drawCheek(c,face,FaceLandmark.RIGHT_CHEEK,w,h,colors[2]);
        // A restrained contour outline helps the Halloween collection read as makeup.
        if(preset>=10) {
            drawContour(c,face,FaceContour.FACE,w,h,colors[2],false);
            if(preset==11) drawFangs(c,face,w,h);
            if(preset==17) drawSpiderAccent(c,face,w,h,colors[1]);
            if(preset==10||preset==13||preset==16) drawCracks(c,face,w,h,colors[1]);
        }
    }

    private void drawCheek(Canvas c,Face face,int type,int w,int h,int color) {
        com.google.mlkit.vision.face.FaceContour cheek=face.getContour(type);
        if(cheek==null||cheek.getPoints().isEmpty())return;
        PointF p=map(cheek.getPoints().get(0),w,h);
        paint.setStyle(Paint.Style.FILL); paint.setColor(color); paint.setAlpha(85);
        c.drawCircle(p.x,p.y,Math.max(9,getWidth()*.035f),paint);
    }

    private void drawContour(Canvas c,Face face,int type,int w,int h,int color,boolean fill) {
        Path path=new Path();
        if(!appendContour(path,face,type,w,h,true))return;
        paint.setColor(color); paint.setAlpha(fill?105:185);
        paint.setStyle(fill?Paint.Style.FILL:Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(2,getWidth()*.003f));
        c.drawPath(path,paint);
    }

    private boolean appendContour(Path path,Face face,int type,int w,int h,boolean close) {
        com.google.mlkit.vision.face.FaceContour contour=face.getContour(type);
        if(contour==null||contour.getPoints().isEmpty())return false;
        boolean first=true;
        for(PointF point:contour.getPoints()) {
            PointF p=map(point,w,h);
            if(first){path.moveTo(p.x,p.y);first=false;} else path.lineTo(p.x,p.y);
        }
        if(close)path.close();
        return !first;
    }

    private PointF map(PointF point,int viewW,int viewH) {
        float x=point.x,y=point.y;
        float rw=(rotation==90||rotation==270)?sourceHeight:sourceWidth;
        float rh=(rotation==90||rotation==270)?sourceWidth:sourceHeight;
        float rx=x,ry=y;
        if(rotation==90){rx=sourceHeight-y;ry=x;}
        else if(rotation==180){rx=sourceWidth-x;ry=sourceHeight-y;}
        else if(rotation==270){rx=y;ry=sourceWidth-x;}
        if(mirrored)rx=rw-rx;
        float scale=Math.max(viewW/rw,viewH/rh);
        float ox=(viewW-rw*scale)/2f,oy=(viewH-rh*scale)/2f;
        return new PointF(ox+rx*scale,oy+ry*scale);
    }

    private void drawFangs(Canvas c,Face face,int w,int h) {
        Path path=new Path();
        com.google.mlkit.vision.face.FaceContour mouth=face.getContour(FaceContour.UPPER_LIP_BOTTOM);
        if(mouth==null||mouth.getPoints().size()<4)return;
        PointF a=map(mouth.getPoints().get(1),w,h),b=map(mouth.getPoints().get(2),w,h);
        PointF d=map(mouth.getPoints().get(mouth.getPoints().size()-2),w,h);
        path.moveTo(a.x,a.y);path.lineTo(a.x+3,a.y+getHeight()*.035f);path.lineTo(a.x+8,a.y);path.close();
        path.moveTo(d.x,d.y);path.lineTo(d.x-3,d.y+getHeight()*.035f);path.lineTo(d.x-8,d.y);path.close();
        paint.setStyle(Paint.Style.FILL);paint.setColor(Color.WHITE);paint.setAlpha(220);c.drawPath(path,paint);
    }

    private void drawSpiderAccent(Canvas c,Face face,int w,int h,int color) {
        com.google.mlkit.vision.face.FaceContour eye=face.getContour(FaceContour.LEFT_EYE);
        if(eye==null||eye.getPoints().isEmpty())return;
        PointF center=map(eye.getPoints().get(0),w,h);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(1.5f);paint.setColor(color);paint.setAlpha(180);
        float radius=getWidth()*.055f;
        for(int i=0;i<5;i++){float x=center.x+radius*(float)Math.cos(i*Math.PI/3);float y=center.y+radius*(float)Math.sin(i*Math.PI/3);c.drawLine(center.x,center.y,x,y,paint);}
        c.drawCircle(center.x,center.y,radius,paint);
    }

    private void drawCracks(Canvas c,Face face,int w,int h,int color) {
        com.google.mlkit.vision.face.FaceContour brow=face.getContour(FaceContour.FACE);
        if(brow==null||brow.getPoints().size()<8)return;
        PointF p=map(brow.getPoints().get(5),w,h);
        paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(2.2f);paint.setColor(color);paint.setAlpha(190);
        Path crack=new Path();crack.moveTo(p.x,p.y);
        crack.lineTo(p.x+getWidth()*.018f,p.y+getHeight()*.025f);
        crack.lineTo(p.x-getWidth()*.004f,p.y+getHeight()*.043f);
        crack.lineTo(p.x+getWidth()*.021f,p.y+getHeight()*.065f);
        c.drawPath(crack,paint);
    }
}
