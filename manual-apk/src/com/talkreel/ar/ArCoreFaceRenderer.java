package com.talkreel.ar;

import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import com.google.ar.core.AugmentedFace;
import com.google.ar.core.Camera;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.ShortBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/** ARCore camera background and face-mesh renderer. */
final class ArCoreFaceRenderer implements GLSurfaceView.Renderer {
    private static final String CV =
        "attribute vec4 aPosition; attribute vec2 aTexCoord; varying vec2 vTexCoord; void main(){gl_Position=aPosition;vTexCoord=aTexCoord;}";
    private static final String CF =
        "#extension GL_OES_EGL_image_external : require\nprecision mediump float; varying vec2 vTexCoord; uniform samplerExternalOES sTexture; void main(){gl_FragColor=texture2D(sTexture,vTexCoord);}";
    private static final String MV =
        "attribute vec4 aPosition; uniform mat4 uMvp; void main(){gl_Position=uMvp*aPosition;}";
    private static final String MF =
        "precision mediump float; uniform vec4 uColor; void main(){gl_FragColor=uColor;}";
    private final float[] projection=new float[16], view=new float[16], model=new float[16], vm=new float[16], mvp=new float[16];
    private final FloatBuffer quad=floats(new float[]{-1,-1,1,-1,-1,1,1,1});
    private final FloatBuffer inputUv=floats(new float[]{0,0,1,0,0,1,1,1});
    private final FloatBuffer displayUv=floats(new float[8]);
    private volatile Session session;
    private volatile int effectStyle=1;
    interface StatusListener { void onTrackingStatus(String status); }
    private volatile StatusListener statusListener;
    private String lastStatus = "";
    private boolean textureBound;
    private int cameraTexture, cameraProgram, meshProgram, position, uv, sampler, meshPosition, mvpHandle, colorHandle;
    private int indexCount;
    private ShortBuffer indices;

    void setSession(Session s) { session=s; textureBound=false; }
    void setEffectStyle(int style) { effectStyle=style; }
    void setStatusListener(StatusListener listener) { statusListener=listener; }
    private void publishStatus(String status) {
        if (status.equals(lastStatus)) return;
        lastStatus=status;
        StatusListener listener=statusListener;
        if (listener != null) listener.onTrackingStatus(status);
    }

    @Override public void onSurfaceCreated(GL10 gl,EGLConfig config) {
        GLES20.glClearColor(0,0,0,1);
        cameraProgram=link(CV,CF); meshProgram=link(MV,MF);
        position=GLES20.glGetAttribLocation(cameraProgram,"aPosition");
        uv=GLES20.glGetAttribLocation(cameraProgram,"aTexCoord");
        sampler=GLES20.glGetUniformLocation(cameraProgram,"sTexture");
        meshPosition=GLES20.glGetAttribLocation(meshProgram,"aPosition");
        mvpHandle=GLES20.glGetUniformLocation(meshProgram,"uMvp");
        colorHandle=GLES20.glGetUniformLocation(meshProgram,"uColor");
        int[] t=new int[1]; GLES20.glGenTextures(1,t,0); cameraTexture=t[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTexture);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
        textureBound=false;
    }

    @Override public void onSurfaceChanged(GL10 gl,int width,int height) {
        GLES20.glViewport(0,0,width,height);
    }

    @Override public void onDrawFrame(GL10 gl) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
        Session active=session;
        if(active==null){publishStatus("Waiting for ARCore session");return;}
        try {
            // A recreated EGL context requires ARCore to receive the new external texture.
            if(!textureBound){active.setCameraTextureName(cameraTexture);textureBound=true;}
            Frame frame=active.update();
            frame.transformDisplayUvCoords(inputUv,displayUv);
            drawCamera();
            Camera camera=frame.getCamera();
            if(camera.getTrackingState()!=TrackingState.TRACKING) {
                publishStatus("Move the phone slowly to initialize tracking");
                return;
            }
            camera.getProjectionMatrix(projection,0,0.1f,100f);
            camera.getViewMatrix(view,0);
            int trackedFaces=0;
            for(AugmentedFace face:active.getAllTrackables(AugmentedFace.class)) {
                if(face.getTrackingState()==TrackingState.TRACKING) {
                    trackedFaces++;
                    drawFace(face);
                }
            }
            publishStatus(trackedFaces>0 ? "Face tracked · 468-point mesh active" : "Center your face in the camera view");
        } catch(Exception error) {
            publishStatus("AR tracking paused · " + error.getClass().getSimpleName());
        }
    }

    private void drawCamera() {
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glUseProgram(cameraProgram);
        quad.position(0); displayUv.position(0);
        GLES20.glEnableVertexAttribArray(position);
        GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,quad);
        GLES20.glEnableVertexAttribArray(uv);
        GLES20.glVertexAttribPointer(uv,2,GLES20.GL_FLOAT,false,0,displayUv);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,cameraTexture);
        GLES20.glUniform1i(sampler,0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4);
        GLES20.glDisableVertexAttribArray(position);
        GLES20.glDisableVertexAttribArray(uv);
    }

    private void drawFace(AugmentedFace face) {
        FloatBuffer vertices=face.getMeshVertices();
        ShortBuffer source=face.getMeshTriangleIndices();
        if(vertices==null||source==null||vertices.remaining()<3||source.remaining()<3)return;
        if(indices==null) {
            indexCount=source.remaining();
            indices=ByteBuffer.allocateDirect(indexCount*2).order(ByteOrder.nativeOrder()).asShortBuffer();
            ShortBuffer copy=source.duplicate();
            copy.position(0);
            indices.put(copy);
            indices.position(0);
        }
        // ARCore mesh vertices are face-local; center pose transforms them into world space.
        face.getCenterPose().toMatrix(model,0);
        Matrix.multiplyMM(vm,0,view,0,model,0);
        Matrix.multiplyMM(mvp,0,projection,0,vm,0);
        GLES20.glEnable(GLES20.GL_DEPTH_TEST);
        GLES20.glEnable(GLES20.GL_BLEND);
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
        GLES20.glUseProgram(meshProgram);
        GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0);
        applyStyle();
        vertices.position(0);
        GLES20.glEnableVertexAttribArray(meshPosition);
        GLES20.glVertexAttribPointer(meshPosition,3,GLES20.GL_FLOAT,false,0,vertices);
        indices.position(0);
        GLES20.glDrawElements(GLES20.GL_TRIANGLES,indexCount,GLES20.GL_UNSIGNED_SHORT,indices);
        GLES20.glDisableVertexAttribArray(meshPosition);
        GLES20.glDisable(GLES20.GL_BLEND);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
    }

    private void applyStyle() {
        switch(effectStyle) {
            case 0: GLES20.glUniform4f(colorHandle,0.18f,0.82f,0.72f,0.13f); break; // soft contour
            case 2: GLES20.glUniform4f(colorHandle,0.98f,0.66f,0.32f,0.24f); break; // warm studio
            default: GLES20.glUniform4f(colorHandle,0.12f,0.70f,1.0f,0.30f); break; // neon
        }
    }

    private static FloatBuffer floats(float[] a) {
        FloatBuffer b=ByteBuffer.allocateDirect(a.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        b.put(a); b.position(0); return b;
    }
    private static int link(String vertex,String fragment) {
        int vs=compile(GLES20.GL_VERTEX_SHADER,vertex), fs=compile(GLES20.GL_FRAGMENT_SHADER,fragment);
        int p=GLES20.glCreateProgram();
        GLES20.glAttachShader(p,vs); GLES20.glAttachShader(p,fs); GLES20.glLinkProgram(p);
        int[] ok=new int[1]; GLES20.glGetProgramiv(p,GLES20.GL_LINK_STATUS,ok,0);
        if(ok[0]==0){String log=GLES20.glGetProgramInfoLog(p);GLES20.glDeleteProgram(p);throw new IllegalStateException(log);}
        GLES20.glDeleteShader(vs); GLES20.glDeleteShader(fs);
        return p;
    }
    private static int compile(int type,String source) {
        int s=GLES20.glCreateShader(type);
        GLES20.glShaderSource(s,source); GLES20.glCompileShader(s);
        int[] ok=new int[1]; GLES20.glGetShaderiv(s,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String log=GLES20.glGetShaderInfoLog(s);GLES20.glDeleteShader(s);throw new IllegalStateException(log);}
        return s;
    }
}