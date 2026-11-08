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

    void setFilter(int value) { filter=value; }
    void setCameraOrientation(int degrees) {
        checkThread();
        int rotation=((degrees%360)+360)%360;
        android.opengl.Matrix.setIdentityM(orientationMatrix,0);
        android.opengl.Matrix.translateM(orientationMatrix,0,0.5f,0.5f,0f);
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
