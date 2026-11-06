package com.talkreel.ar;

import android.graphics.SurfaceTexture;
import android.opengl.EGL14;
import android.opengl.EGLExt;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.view.Surface;
import android.os.Handler;

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
        "uniform mat4 uTexMatrix; varying vec2 vTexCoord; " +
        "void main(){ gl_Position=aPosition; vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy; }";
    private static final String FRAGMENT =
        "#extension GL_OES_EGL_image_external : require\n" +
        "precision mediump float; varying vec2 vTexCoord; " +
        "uniform samplerExternalOES sTexture; uniform int uFilter; uniform vec4 uFaceRect; uniform int uHasFace; uniform int uMirror; " +
        "void main(){ vec4 c=texture2D(sTexture,vTexCoord); " +
        "if(uFilter==1){ c.rgb=mix(c.rgb,vec3(dot(c.rgb,vec3(.299,.587,.114))),.08); c.rgb=min(vec3(1.0),c.rgb*1.10+vec3(.025)); } " +
        "else if(uFilter==2){ c.rgb=mix(c.rgb,vec3(.12,.24,.34),.12); } " +
        "else if(uFilter==3){ c.rgb=mix(c.rgb,vec3(.95,.56,.68),.08); } " +
        "else if(uFilter==4){ c.rgb=min(vec3(1.0),c.rgb*1.18); } " +
        "else if(uFilter==5){ float l=dot(c.rgb,vec3(.299,.587,.114)); c.rgb=mix(c.rgb,vec3(l*.35,l*.9, min(1.0,l*1.5)),.32); } " +
        "else if(uFilter==6){ vec3 soft=(texture2D(sTexture,vTexCoord+vec2(.002,0.)).rgb+texture2D(sTexture,vTexCoord-vec2(.002,0.)).rgb+texture2D(sTexture,vTexCoord+vec2(0.,.002)).rgb+texture2D(sTexture,vTexCoord-vec2(0.,.002)).rgb)*.25; c.rgb=mix(c.rgb,soft,.24); c.rgb=min(vec3(1.0),c.rgb*1.04); } " +
        "else if(uFilter==7){ c.rgb=mix(c.rgb,vec3(1.0,.48,.62),.07); } " +
        "else if(uFilter==8){ c.rgb=mix(c.rgb,vec3(.10,.12,.16),.12); } " +
        "else if(uFilter==9){ c.rgb=mix(c.rgb,vec3(.72,.45,.22),.08); } " +
        "else if(uFilter==10){ c.rgb=mix(c.rgb,vec3(.62,.39,.22),.10); } " +
        "if(uHasFace==1 && uFilter>=2 && uFilter<=10){ vec2 q=vec2(vTexCoord.x,1.0-vTexCoord.y); if(uMirror==1) q.x=1.0-q.x; vec2 sz=uFaceRect.zw-uFaceRect.xy; vec2 ctr=(uFaceRect.xy+uFaceRect.zw)*.5; " +
        "if(uFilter==2){ vec2 e=ctr+vec2(0.,-sz.y*.10); vec2 l=(q-(e+vec2(-sz.x*.23,0.)))/vec2(sz.x*.20,sz.y*.105); vec2 r=(q-(e+vec2(sz.x*.23,0.)))/vec2(sz.x*.20,sz.y*.105); if(dot(l,l)<1.0||dot(r,r)<1.0)c.rgb=mix(c.rgb,vec3(.08,.55,.92),.68); } " +
        "if(uFilter==3){ vec2 l=(q-(ctr+vec2(-sz.x*.27,-sz.y*.64)))/vec2(sz.x*.13,sz.y*.34); vec2 r=(q-(ctr+vec2(sz.x*.27,-sz.y*.64)))/vec2(sz.x*.13,sz.y*.34); if(dot(l,l)<1.0||dot(r,r)<1.0)c.rgb=mix(c.rgb,vec3(1.0,.55,.72),.88); } " +
        "if(uFilter==4 && q.y<ctr.y-sz.y*.56 && q.y>ctr.y-sz.y*1.02 && abs(q.x-ctr.x)<sz.x*.5)c.rgb=mix(c.rgb,vec3(1.0,.72,.08),.95); " +
        "if(uFilter==5){ float edge=min(min(abs(q.x-uFaceRect.x),abs(q.x-uFaceRect.z)),min(abs(q.y-uFaceRect.y),abs(q.y-uFaceRect.w))); if(edge<.009)c.rgb=mix(c.rgb,vec3(.0,.95,1.0),.98); } " +
        "if(uFilter==7){ vec2 l=(q-(ctr+vec2(-sz.x*.23,sz.y*.18)))/vec2(sz.x*.12,sz.y*.075); vec2 r=(q-(ctr+vec2(sz.x*.23,sz.y*.18)))/vec2(sz.x*.12,sz.y*.075); vec2 lip=(q-(ctr+vec2(0.,sz.y*.32)))/vec2(sz.x*.20,sz.y*.055); if(dot(l,l)<1.0||dot(r,r)<1.0)c.rgb=mix(c.rgb,vec3(1.0,.28,.42),.52); if(dot(lip,lip)<1.0)c.rgb=mix(c.rgb,vec3(.78,.05,.22),.82); } " +
        "if(uFilter==8 && abs(q.x-ctr.x)<sz.x*.48 && q.y>ctr.y-sz.y*.48 && q.y<ctr.y+sz.y*.48){ c.rgb=mix(c.rgb,vec3(.16,.18,.22),.40); } " +
        "if(uFilter==9 && q.y<ctr.y-sz.y*.52 && q.y>ctr.y-sz.y*1.0 && abs(q.x-ctr.x)<sz.x*.5){ c.rgb=mix(c.rgb,vec3(.20,.12,.08),.92); } " +
        "if(uFilter==10 && q.y<ctr.y-sz.y*.28 && q.y>ctr.y-sz.y*.95 && abs(q.x-ctr.x)<sz.x*.62){ c.rgb=mix(c.rgb,vec3(.45,.24,.12),.82); } " +
        "} gl_FragColor=c; }";

    private final Handler handler;
    private EGLDisplayHolder egl;
    private SurfaceTexture cameraTexture;
    private Surface cameraInput;
    private Surface previewSurface;
    private EGL14Surface previewEgl;
    private Surface encoderSurface;
    private EGL14Surface encoderEgl;
    private int program, textureId;
    private volatile int filter;
    private int positionLoc, texCoordLoc, matrixLoc, filterLoc, faceRectLoc, hasFaceLoc, mirrorLoc;
    private volatile float faceLeft, faceTop, faceRight, faceBottom;
    private volatile boolean hasFace, mirrorFace;
    private final float[] transform = new float[16];
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
        positionLoc=GLES20.glGetAttribLocation(program,"aPosition");
        texCoordLoc=GLES20.glGetAttribLocation(program,"aTexCoord");
        matrixLoc=GLES20.glGetUniformLocation(program,"uTexMatrix");
        filterLoc=GLES20.glGetUniformLocation(program,"uFilter");
        faceRectLoc=GLES20.glGetUniformLocation(program,"uFaceRect");
        hasFaceLoc=GLES20.glGetUniformLocation(program,"uHasFace");
        mirrorLoc=GLES20.glGetUniformLocation(program,"uMirror");
        return cameraInput;
    }

    void setEncoderSurface(Surface surface) {
        checkThread();
        if(encoderEgl!=null){ encoderEgl.release(); encoderEgl=null; }
        encoderSurface=surface;
        if(surface!=null) encoderEgl=new EGL14Surface(egl,surface);
    }

    void setFilter(int value) { filter=value; }
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
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program,"sTexture"),0);
        GLES20.glUniform1i(filterLoc,filter);
        GLES20.glUniform1i(hasFaceLoc,hasFace?1:0);
        GLES20.glUniform1i(mirrorLoc,mirrorFace?1:0);
        GLES20.glUniform4f(faceRectLoc,faceLeft,faceTop,faceRight,faceBottom);
        GLES20.glUniformMatrix4fv(matrixLoc,1,false,transform,0);
        float[] vertices={-1,-1, 1,-1, -1,1, 1,1};
        float[] tex={0,1, 1,1, 0,0, 1,0};
        java.nio.FloatBuffer vb=java.nio.ByteBuffer.allocateDirect(vertices.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        vb.put(vertices).position(0);
        java.nio.FloatBuffer tb=java.nio.ByteBuffer.allocateDirect(tex.length*4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
        tb.put(tex).position(0);
        GLES20.glEnableVertexAttribArray(positionLoc);
        GLES20.glVertexAttribPointer(positionLoc,2,GLES20.GL_FLOAT,false,0,vb);
        GLES20.glEnableVertexAttribArray(texCoordLoc);
        GLES20.glVertexAttribPointer(texCoordLoc,2,GLES20.GL_FLOAT,false,0,tb);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(positionLoc);
        GLES20.glDisableVertexAttribArray(texCoordLoc);
        if(target==encoderEgl && timestamp>0) EGLExt.eglPresentationTimeANDROID(egl.display,target.surface,timestamp);
        EGL14.eglSwapBuffers(egl.display,target.surface);
    }

    void stop() {
        checkThread();
        released=true;
        if(egl!=null && previewEgl!=null) previewEgl.makeCurrent();
        if(program!=0){ GLES20.glDeleteProgram(program); program=0; }
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
            int[] attrs={EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_RECORDABLE_ANDROID,1,EGL14.EGL_NONE};
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
