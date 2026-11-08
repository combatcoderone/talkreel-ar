package com.talkreel.ar;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;
import android.os.Handler;
import android.os.SystemClock;

/**
 * Camera-to-encoder GPU bridge. Camera2 writes to the input SurfaceTexture;
 * each frame is rendered to the app preview and, when recording, the encoder
 * Surface. This makes the rendered camera image the actual MP4 video track.
 *
 * All methods and frame callbacks must run on the supplied Handler thread.
 */
final class ArCompositor implements SurfaceTexture.OnFrameAvailableListener {
    private static final String VERTEX =
        "attribute vec4 aPosition; attribute vec2 aTexCoord; " +
        "uniform mat4 uTexMatrix; uniform mat4 uOrientationMatrix; varying vec2 vTexCoord; " +
        "void main(){ gl_Position=aPosition; vTexCoord=(uTexMatrix*uOrientationMatrix*vec4(aTexCoord,0.0,1.0)).xy; }";
    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float; varying vec2 vTexCoord; " +
        "uniform samplerExternalOES sTexture; uniform int uFilter; " +
        "void main(){ vec3 c=texture2D(sTexture,vTexCoord).rgb; " +
        "if(uFilter==1){ c.r=min(1.0,c.r*1.035+0.008); c.g=min(1.0,c.g*1.012); c.b*=0.975; } " +
        "else if(uFilter==2){ c.r*=0.975; c.g*=1.005; c.b=min(1.0,c.b*1.045+0.006); } " +
        "else if(uFilter==3){ c=(c-vec3(0.5))*1.10+vec3(0.5); c.r=min(1.0,c.r*1.025); c.b*=0.985; } " +
        "else if(uFilter==4){ c=mix(c,vec3(dot(c,vec3(0.299,0.587,0.114))),0.10); c=min(vec3(1.0),c*1.025+vec3(0.008)); } " +
        "else if(uFilter==5){ float y=dot(c,vec3(0.2126,0.7152,0.0722)); c=vec3(y); } " +
        "gl_FragColor=vec4(clamp(c,0.0,1.0),1.0); }";

    private final Handler handler;
    private EGLDisplayHolder egl;
    private SurfaceTexture cameraTexture;
    private Surface cameraInput;
    private Surface previewSurface;
    private EGL14Surface previewEgl;
    private Surface encoderSurface;
    private EGL14Surface encoderEgl;
    private int program, textureId;
    private int overlayProgram, overlayPositionLoc, overlayColorLoc;
    private java.nio.FloatBuffer overlayBuffer;
    private volatile FaceTrackingEngine.FaceFrame faceFrame;
    private volatile boolean mirrorFace;
    private volatile int effect = 0;
    private volatile int filter;
    private int positionLoc, texCoordLoc, matrixLoc, orientationLoc, filterLoc, samplerLoc;
    private java.nio.FloatBuffer vertexBuffer, texBuffer;
    private volatile float faceLeft, faceTop, faceRight, faceBottom;
    private volatile boolean hasFace, mirrorFace;
    private final float[] transform = new float[16];
    private final float[] orientationMatrix = new float[16];
    private boolean released;

    ArCompositor(Handler handler) { this.handler=handler; }

    Surface start(SurfaceTexture previewTexture, int width, int height) {
        checkThread();
        stop();
        released=false;
        egl=new EGLDisplayHolder();
        egl.open();
        previewTexture.setDefaultBufferSize(width,height);
        previewSurface=new Surface(previewTexture);
        previewEgl=new EGL14Surface(egl,previewSurface);
        if(!previewEgl.makeCurrent()) throw new RuntimeException("EGL preview context activation failed");
        textureId=createExternalTexture();
        cameraTexture=new SurfaceTexture(textureId);
        cameraTexture.setDefaultBufferSize(width,height);
        cameraTexture.setOnFrameAvailableListener(this,handler);
        cameraInput=new Surface(cameraTexture);
        program=createProgram(VERTEX,FRAGMENT);
        overlayProgram=createProgram("attribute vec2 aPosition; void main(){gl_Position=vec4(aPosition,0.0,1.0);}",
                "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}");
        overlayPositionLoc=GLES20.glGetAttribLocation(overlayProgram,"aPosition");
        overlayColorLoc=GLES20.glGetUniformLocation(overlayProgram,"uColor");
        overlayBuffer=java.nio.ByteBuffer.allocateDirect(2048*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        positionLoc=GLES20.glGetAttribLocation(program,"aPosition");
        texCoordLoc=GLES20.glGetAttribLocation(program,"aTexCoord");
        matrixLoc=GLES20.glGetUniformLocation(program,"uTexMatrix");
        orientationLoc=GLES20.glGetUniformLocation(program,"uOrientationMatrix");
        filterLoc=GLES20.glGetUniformLocation(program,"uFilter");
        android.opengl.Matrix.setIdentityM(orientationMatrix,0);
        samplerLoc=GLES20.glGetUniformLocation(program,"sTexture");
        float[] vertices={-1,-1, 1,-1, -1,1, 1,1};
        float[] tex={0,1, 1,1, 0,0, 1,0};
        vertexBuffer=java.nio.ByteBuffer.allocateDirect(vertices.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        vertexBuffer.put(vertices).position(0);
        texBuffer=java.nio.ByteBuffer.allocateDirect(tex.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        texBuffer.put(tex).position(0);
        return cameraInput;
    }

    void setEncoderSurface(Surface surface) {
        checkThread();
        if(encoderEgl!=null){ encoderEgl.release(); encoderEgl=null; }
        encoderSurface=surface;
        if(surface!=null) encoderEgl=new EGL14Surface(egl,surface);
    }

    void setFilter(int value) { filter=Math.max(0,Math.min(5,value)); }
    void setEffect(int value) { effect=Math.max(0,Math.min(19,value)); }
    void setFaceFrame(FaceTrackingEngine.FaceFrame frame, boolean mirrored) { faceFrame=frame; mirrorFace=mirrored; }
    void clearFaceFrame() { faceFrame=null; }
    void setCameraOrientation(int degrees) { setCameraOrientation(degrees, false); }
    void setCameraOrientation(int degrees, boolean mirror) {
        checkThread();
        int rotation=((degrees%360)+360)%360;
        android.opengl.Matrix.setIdentityM(orientationMatrix,0);
        android.opengl.Matrix.translateM(orientationMatrix,0,0.5f,0.5f,0f);
        if (mirror) android.opengl.Matrix.scaleM(orientationMatrix,0,-1f,1f,1f);
        mirrorFace=mirror;
        android.opengl.Matrix.rotateM(orientationMatrix,0,rotation,0f,0f,1f);
        android.opengl.Matrix.translateM(orientationMatrix,0,-0.5f,-0.5f,0f);
    }
    void setFaceRect(float left,float top,float right,float bottom,boolean mirrored) {
        faceLeft=left; faceTop=top; faceRight=right; faceBottom=bottom;
        mirrorFace=mirrored; hasFace=right>left && bottom>top;
    }
    void clearFace() { hasFace=false; }

    Surface getCameraInputSurface() { return cameraInput; }

    @Override public void onFrameAvailable(SurfaceTexture ignored) {
        if(released || cameraTexture==null || egl==null) return;
        try {
            cameraTexture.updateTexImage();
            cameraTexture.getTransformMatrix(transform);
            long timestamp=cameraTexture.getTimestamp();
            draw(previewEgl,timestamp);
            if(encoderEgl!=null) draw(encoderEgl,timestamp);
        } catch (RuntimeException ignoredError) { }
    }

    private void draw(EGL14Surface target,long timestamp) {
        if(target==null || !target.makeCurrent()) return;
        GLES20.glViewport(0,0,target.width,target.height);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(program);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId);
        GLES20.glUniform1i(samplerLoc,0);
        GLES20.glUniform1i(filterLoc,filter);
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,transform,0);
        GLES20.glUniformMatrix4fv(orientationLoc,1,false,orientationMatrix,0);
        vertexBuffer.position(0);
        texBuffer.position(0);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,vertexBuffer);
        GLES20.glEnableVertexAttribArray(texCoordLoc);
        GLES20.glVertexAttribPointer(texCoordLoc,2,GLES20.GL_FLOAT,false,0,texBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(positionLoc);
        GLES20.glDisableVertexAttribArray(texCoordLoc);
        drawFaceEffects();
        if(target==encoderEgl && timestamp>0) EGLExt.eglPresentationTimeANDROID(egl.display,target.surface,timestamp);
        EGL14.eglSwapBuffers(egl.display,target.surface);
    }


    // The same GPU pass runs for both preview and encoder EGL surfaces, so the
    // tracked makeup/lens geometry is present in the actual MP4, not just UI.
    private static final int[] FACE_OVAL={10,338,297,332,284,251,389,356,454,323,361,288,397,365,379,378,400,377,152,148,176,149,150,136,172,58,132,93,234,127,162,21,54,103,67,109};
    private static final int[] LEFT_EYE={33,160,158,157,173,133,155,154,153,145,144,163,7};
    private static final int[] RIGHT_EYE={362,385,387,386,398,263,373,374,380,381,382,466};
    private static final int[] LIPS={61,185,40,39,37,0,267,269,270,409,291,375,321,405,314,17,84,181,91,146};
    private static final int[] FOREHEAD_LEFT={70,63,105,66,107};
    private static final int[] FOREHEAD_RIGHT={300,293,334,296,336};

    private void drawFaceEffects() {
        FaceTrackingEngine.FaceFrame frame=faceFrame;
        if(frame==null || SystemClock.uptimeMillis()-frame.timestampMs>450 || frame.faceCount()==0) return;
        GLES20.glUseProgram(overlayProgram);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        for(int i=0;i<frame.faceCount();i++) {
            float[] mesh=frame.landmarks[i];
            float[] expr=frame.blendshapes[i];
            if(mesh==null||mesh.length<FaceTrackingEngine.LANDMARK_COUNT*3) continue;
            drawPolygon(mesh,FACE_OVAL,skinTint(effect),0.0f);
            int eye=eyeColor(effect), lip=lipColor(effect), cheek=cheekColor(effect);
            drawPolygon(mesh,LEFT_EYE,eye,0.0f);
            drawPolygon(mesh,RIGHT_EYE,eye,0.0f);
            drawPolygon(mesh,LIPS,lip,0.0f);
            drawCircle(mesh,117,cheek,0.027f);
            drawCircle(mesh,346,cheek,0.027f);
            if(effect>=10) drawHalloween(mesh,expr);
            else if(effect==1 || effect==3 || effect==9) drawSparkleAccents(mesh);
        }
        GLES20.glDisable(GLES20.GL_BLEND);
    }

    private int skinTint(int e) {
        switch(e) {
            case 10:return 0x55D8D2C9; case 11:return 0x228E1828;
            case 12:return 0x66F08B28; case 13:return 0x665D8A42;
            case 14:return 0x665D3D87; case 15:return 0x66DDEBF2;
            case 16:return 0x665B162B; case 17:return 0x44321A45;
            case 18:return 0x77EADDD4; case 19:return 0x4400DDF5;
            case 9:return 0x22E8C6C8; case 4:return 0x228C583A;
            default:return 0x18C58A91;
        }
    }
    private int eyeColor(int e) {
        switch(e) {
            case 2:return 0xAA33243F; case 3:return 0x996F3E82;
            case 4:return 0x996E4528; case 5:return 0xAA52182C;
            case 8:return 0xAA8A4525; case 10:return 0xDD151515;
            case 11:return 0xCC25101A; case 13:return 0xDD253A20;
            case 14:return 0xCC311C55; case 15:return 0xCC71828C;
            case 16:return 0xDD130D20; case 17:return 0xCC32103B;
            case 18:return 0xBB45323A; case 19:return 0xDD00BBD2;
            default:return 0x775A344A;
        }
    }
    private int lipColor(int e) {
        switch(e) {
            case 5:return 0xE8B20C2D; case 7:return 0xD889174D;
            case 10:return 0xCC202020; case 11:return 0xEE8B1025;
            case 12:return 0xDDC64B12; case 13:return 0xCC4D392C;
            case 14:return 0xCC3A1D61; case 15:return 0xBB9BAAB2;
            case 16:return 0xEE671019; case 17:return 0xCC2A0C38;
            case 18:return 0xCC9A2638; case 19:return 0xEE00CFEA;
            default:return 0xBB9A405D;
        }
    }
    private int cheekColor(int e) {
        if(e>=10) return e==12?0xAAE66B12:(e==13?0x995B6C40:0x887D2636);
        switch(e){case 1:return 0x88D36A91;case 3:return 0x88D87CA5;case 4:return 0x888B4D2E;default:return 0x668C5360;}
    }

    private void drawHalloween(float[] mesh,float[] expr) {
        switch(effect) {
            case 10: // Skull: dark sockets, nose cavity and expression-scaled teeth.
                drawPolygon(mesh,LEFT_EYE,0xEE111111,0);
                drawPolygon(mesh,RIGHT_EYE,0xEE111111,0);
                drawTriangle(mesh,1,4,5,0xCC111111);
                drawTeeth(mesh,0xFFECE8DD,0.018f+(expr.length>0?expr[0]*0.025f:0));
                drawCrack(mesh,10,0xFF262626); drawCrack(mesh,234,0xFF262626);
                break;
            case 11: // Vampire
                drawFang(mesh,78,88,0.025f+(expr.length>0?expr[0]*0.035f:0));
                drawFang(mesh,308,318,0.025f+(expr.length>0?expr[0]*0.035f:0));
                drawCircle(mesh,1,0x99C20E2D,0.012f);
                break;
            case 12: // Pumpkin
                drawLine(mesh,10,152,0x99A63E08,2.5f);
                drawLine(mesh,109,338,0x88F4A42C,1.8f);
                drawTriangle(mesh,9,107,336,0xAA6C330A);
                break;
            case 13: // Zombie
                drawPolygon(mesh,LEFT_EYE,0xCC182019,0);
                drawPolygon(mesh,RIGHT_EYE,0xCC182019,0);
                drawCrack(mesh,10,0xFF33251E); drawCrack(mesh,234,0xFF33251E);
                drawCircle(mesh,205,0x998B1E1E,0.025f);
                break;
            case 14: // Witch
                drawTriangle(mesh,10,54,67,0xCC26104A);
                drawTriangle(mesh,10,336,296,0xCC26104A);
                drawCircle(mesh,1,0xAA21172B,0.012f);
                break;
            case 15: // Ghost
                drawPolygon(mesh,LEFT_EYE,0xBB34404A,0);
                drawPolygon(mesh,RIGHT_EYE,0xBB34404A,0);
                drawCircle(mesh,13,0xAA28313A,0.018f);
                break;
            case 16: // Demon
                drawTriangle(mesh,10,54,67,0xFF8D1228);
                drawTriangle(mesh,10,336,296,0xFF8D1228);
                drawPolygon(mesh,LEFT_EYE,0xEE160B20,0);
                drawPolygon(mesh,RIGHT_EYE,0xEE160B20,0);
                break;
            case 17: // Spider queen
                drawSpiderWeb(mesh,33); drawSpiderWeb(mesh,263);
                drawCircle(mesh,1,0xFF15101D,0.018f);
                break;
            case 18: // Cracked porcelain doll
                drawCrack(mesh,10,0xFF6B2638); drawCrack(mesh,234,0xFF6B2638);
                drawCrack(mesh,454,0xFF6B2638);
                drawCircle(mesh,1,0x66FFFFFF,0.025f);
                break;
            case 19: // Neon phantom
                drawPolygon(mesh,FACE_OVAL,0x5500DFF5,0);
                drawPolygon(mesh,LEFT_EYE,0xDD00EFFF,0);
                drawPolygon(mesh,RIGHT_EYE,0xDD00EFFF,0);
                drawLine(mesh,33,263,0xCC00EFFF,2.5f);
                break;
        }
    }

    private void drawSparkleAccents(float[] m) {
        drawCircle(m,70,0x99FFF0C4,0.008f);
        drawCircle(m,300,0x99FFF0C4,0.008f);
    }
    private void drawFang(float[] m,int top,int bottom,float length) {
        float[] xy=point(m,top); float[] lo=point(m,bottom);
        float[] p={xy[0]-0.008f,xy[1],xy[0]+0.008f,xy[1],xy[0],Math.min(0.98f,lo[1]+length)};
        drawPolygonCoords(p,0xFFF4F1E9);
    }
    private void drawTeeth(float[] m,int color,float height) {
        drawFang(m,78,88,height); drawFang(m,308,318,height);
        drawLine(m,78,308,color,1.2f);
    }
    private void drawCrack(float[] m,int start,int color) {
        float[] a=point(m,start);
        float[] p={a[0],a[1],a[0]+0.025f,a[1]+0.035f,a[0]+0.008f,a[1]+0.065f,
                   a[0]+0.034f,a[1]+0.085f};
        drawPolyline(p,color,2.0f);
    }
    private void drawSpiderWeb(float[] m,int eye) {
        float[] c=point(m,eye);
        for(int i=0;i<6;i++){
            double a=i*Math.PI/3;
            float x=c[0]+(float)Math.cos(a)*0.09f;
            float y=c[1]+(float)Math.sin(a)*0.07f;
            drawLineCoords(c[0],c[1],x,y,0xCCF2D6F5,1.1f);
        }
        drawCircle(m,eye,0xFF17111F,0.012f);
    }

    private void drawTriangle(float[] m,int a,int b,int c,int color) {
        float[] p1=point(m,a),p2=point(m,b),p3=point(m,c);
        drawPolygonCoords(new float[]{p1[0],p1[1],p2[0],p2[1],p3[0],p3[1]},color);
    }
    private void drawCircle(float[] m,int index,int color,float radius) {
        float[] c=point(m,index); float[] p=new float[24];
        for(int i=0;i<12;i++){double a=i*Math.PI/6;p[i*2]=c[0]+(float)Math.cos(a)*radius;p[i*2+1]=c[1]+(float)Math.sin(a)*radius;}
        drawPolygonCoords(p,color);
    }
    private void drawLine(float[] m,int a,int b,int color,float width) {
        float[] p=point(m,a),q=point(m,b);
        drawLineCoords(p[0],p[1],q[0],q[1],color,width);
    }
    private void drawLineCoords(float x1,float y1,float x2,float y2,int color,float width) {
        float[] xy={x1,y1,x2,y2}; setOverlayColor(color);
        overlayBuffer.clear(); putNdc(xy[0],xy[1]); putNdc(xy[2],xy[3]); overlayBuffer.flip();
        GLES20.glLineWidth(width);
        GLES20.glEnableVertexAttribArray(overlayPositionLoc);
        GLES20.glVertexAttribPointer(overlayPositionLoc,2,GLES20.GL_FLOAT,false,0,overlayBuffer);
        GLES20.glDrawArrays(GLES20.GL_LINES,0,2);
    }
    private void drawPolyline(float[] xy,int color,float width) {
        setOverlayColor(color); overlayBuffer.clear();
        for(int i=0;i<xy.length;i+=2)putNdc(xy[i],xy[i+1]);
        overlayBuffer.flip(); GLES20.glLineWidth(width);
        GLES20.glEnableVertexAttribArray(overlayPositionLoc);
        GLES20.glVertexAttribPointer(overlayPositionLoc,2,GLES20.GL_FLOAT,false,0,overlayBuffer);
        GLES20.glDrawArrays(GLES20.GL_LINE_STRIP,0,xy.length/2);
    }
    private void drawPolygon(float[] mesh,int[] indices,int color,float unused) {
        float[] xy=new float[indices.length*2];
        for(int i=0;i<indices.length;i++){float[] p=point(mesh,indices[i]);xy[i*2]=p[0];xy[i*2+1]=p[1];}
        drawPolygonCoords(xy,color);
    }
    private void drawPolygonCoords(float[] xy,int color) {
        if(xy.length<6)return;
        float cx=0,cy=0;int count=xy.length/2;
        for(int i=0;i<count;i++){cx+=xy[i*2];cy+=xy[i*2+1];}
        cx/=count;cy/=count;
        setOverlayColor(color);overlayBuffer.clear();putNdc(cx,cy);
        for(int i=0;i<count;i++)putNdc(xy[i*2],xy[i*2+1]);
        putNdc(xy[0],xy[1]);overlayBuffer.flip();
        GLES20.glEnableVertexAttribArray(overlayPositionLoc);
        GLES20.glVertexAttribPointer(overlayPositionLoc,2,GLES20.GL_FLOAT,false,0,overlayBuffer);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_FAN,0,count+2);
    }
    private float[] point(float[] mesh,int index) {
        float x=mesh[index*3],y=mesh[index*3+1];
        if(mirrorFace)x=1f-x;
        return new float[]{x,y};
    }
    private void putNdc(float x,float y){overlayBuffer.put(x*2f-1f);overlayBuffer.put(1f-y*2f);}
    private void setOverlayColor(int color) {
        GLES20.glUseProgram(overlayProgram);
        GLES20.glUniform4f(overlayColorLoc,((color>>16)&255)/255f,((color>>8)&255)/255f,
                (color&255)/255f,((color>>>24)&255)/255f);
    }

    void stop() {
        checkThread();
        released=true;
        if(egl!=null && previewEgl!=null) previewEgl.makeCurrent();
        if(program!=0){ GLES20.glDeleteProgram(program); program=0; }
        if(overlayProgram!=0){ GLES20.glDeleteProgram(overlayProgram); overlayProgram=0; }
        faceFrame=null;
        if(textureId!=0){ GLES20.glDeleteTextures(1,new int[]{textureId},0); textureId=0; }
        if(cameraTexture!=null){ cameraTexture.setOnFrameAvailableListener(null); cameraTexture.release(); cameraTexture=null; }
        if(cameraInput!=null){ cameraInput.release(); cameraInput=null; }
        if(encoderEgl!=null){ encoderEgl.release(); encoderEgl=null; }
        encoderSurface=null;
        if(previewEgl!=null){ previewEgl.release(); previewEgl=null; }
        if(previewSurface!=null){ previewSurface.release(); previewSurface=null; }
        if(egl!=null){ egl.close(); egl=null; }
    }

    private void checkThread() {
        if(handler.getLooper().getThread()!=Thread.currentThread()) throw new IllegalStateException("ArCompositor must be used on camera thread");
    }

    private static int createExternalTexture() {
        int[] ids=new int[1]; GLES20.glGenTextures(1,ids,0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,ids[0]);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        return ids[0];
    }

    private static int createProgram(String vertex,String fragment) {
        int vs=compile(GLES20.GL_VERTEX_SHADER,vertex), fs=compile(GLES20.GL_FRAGMENT_SHADER,fragment);
        int p=GLES20.glCreateProgram(); GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p);
        int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
        if(ok[0]==0){ String log=GLES20.glGetProgramInfoLog(p); GLES20.glDeleteProgram(p); throw new RuntimeException("AR shader link failed: "+log); }
        return p;
    }
    private static int compile(int type,String source) {
        int s=GLES20.glCreateShader(type); GLES20.glShaderSource(s,source); GLES20.glCompileShader(s);
        int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){ String log=GLES20.glGetShaderInfoLog(s); GLES20.glDeleteShader(s); throw new RuntimeException("AR shader compile failed: "+log); }
        return s;
    }

    private static final class EGLDisplayHolder {
        android.opengl.EGLDisplay display;
        android.opengl.EGLContext context;
        android.opengl.EGLConfig config;
        void open() {
            display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] v=new int[2]; if(display==EGL14.EGL_NO_DISPLAY || !EGL14.eglInitialize(display,v,0,v,1)) throw new RuntimeException("EGL init failed");
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGLExt.EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
            android.opengl.EGLConfig[] configs=new android.opengl.EGLConfig[1]; int[] count=new int[1];
            if(!EGL14.eglChooseConfig(display,attrs,0,configs,0,1,count,0) || count[0]==0) throw new RuntimeException("No EGL config");
            config=configs[0];
            context=EGL14.eglCreateContext(display,config,EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            if(context==EGL14.EGL_NO_CONTEXT) throw new RuntimeException("EGL context failed");
        }
        void close() {
            if(display!=null && display!=EGL14.EGL_NO_DISPLAY){
                EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
                if(context!=null) EGL14.eglDestroyContext(display,context);
                EGL14.eglTerminate(display);
            }
            display=null; context=null;
        }
    }
    private static final class EGL14Surface {
        final EGLDisplayHolder owner; final android.opengl.EGLSurface surface; final int width,height;
        EGL14Surface(EGLDisplayHolder owner,Surface window) {
            this.owner=owner;
            surface=EGL14.eglCreateWindowSurface(owner.display,owner.config,window,new int[]{EGL14.EGL_NONE},0);
            if(surface==EGL14.EGL_NO_SURFACE) throw new RuntimeException("EGL window surface failed");
            int[] w=new int[1],h=new int[1]; EGL14.eglQuerySurface(owner.display,surface,EGL14.EGL_WIDTH,w,0); EGL14.eglQuerySurface(owner.display,surface,EGL14.EGL_HEIGHT,h,0);
            width=w[0]; height=h[0];
        }
        boolean makeCurrent(){ return EGL14.eglMakeCurrent(owner.display,surface,surface,owner.context); }
        void release(){ EGL14.eglDestroySurface(owner.display,surface); }
    }
}
