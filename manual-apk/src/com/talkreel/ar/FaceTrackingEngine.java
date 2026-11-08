package com.talkreel.ar;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.Image;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import com.google.mediapipe.framework.image.BitmapImageBuilder;
import com.google.mediapipe.framework.image.MPImage;
import com.google.mediapipe.tasks.core.BaseOptions;
import com.google.mediapipe.tasks.components.containers.Category;
import com.google.mediapipe.tasks.components.containers.NormalizedLandmark;
import com.google.mediapipe.tasks.vision.core.RunningMode;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker;
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarkerResult;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/** Asynchronous on-device 3D face mesh and expression tracker. */
public final class FaceTrackingEngine implements AutoCloseable {
    public static final int LANDMARK_COUNT=478;
    public static final int BLENDSHAPE_COUNT=52;
    public static final class FaceFrame {
        public final float[][] landmarks;
        public final float[][] blendshapes;
        public final float[][] poseMatrices;
        public final long timestampMs;
        FaceFrame(float[][] p,float[][] b,float[][] matrices,long t){landmarks=p;blendshapes=b;poseMatrices=matrices;timestampMs=t;}
        public int faceCount(){return landmarks.length;}
    }
    public interface Listener {
        void onFaces(FaceFrame frame,int uprightWidth,int uprightHeight);
        void onError(String message);
    }
    private final Listener listener;
    private final HandlerThread thread;
    private final Handler worker;
    private final AtomicBoolean busy=new AtomicBoolean(false);
    private volatile boolean closed;
    private FaceLandmarker landmarker;
    private long lastTimestamp;
    private final float[][] previous=new float[3][];

    public FaceTrackingEngine(Context context,Listener listener) {
        this.listener=listener;
        thread=new HandlerThread("TalkReelFaceLandmarker");
        thread.start();
        worker=new Handler(thread.getLooper());
        Context app=context.getApplicationContext();
        worker.post(()->{
            try {
                BaseOptions base=BaseOptions.builder().setModelAssetPath("face_landmarker.task").build();
                FaceLandmarker.FaceLandmarkerOptions options=FaceLandmarker.FaceLandmarkerOptions.builder()
                    .setBaseOptions(base).setRunningMode(RunningMode.VIDEO).setNumFaces(3)
                    .setMinFaceDetectionConfidence(0.55f).setMinFacePresenceConfidence(0.55f)
                    .setMinTrackingConfidence(0.55f).setOutputFaceBlendshapes(true)
                    .setOutputFacialTransformationMatrixes(true).build();
                landmarker=FaceLandmarker.createFromOptions(app,options);
            } catch(Exception e) {
                if(!closed&&listener!=null)listener.onError("Face model initialization failed: "+e.getMessage());
            }
        });
    }

    public void process(Image image,int rotationDegrees) {
        if(image==null)return;
        if(closed||!busy.compareAndSet(false,true)){try{image.close();}catch(Exception ignored){}return;}
        if(!worker.post(()->processFrame(image,rotationDegrees))){
            try{image.close();}catch(Exception ignored){}
            busy.set(false);
        }
    }

    private void processFrame(Image image,int rotationDegrees) {
        Bitmap raw=null,rotated=null;
        MPImage input=null;
        try {
            if(closed||landmarker==null)return;
            raw=toBitmap(image);
            int rotation=((rotationDegrees%360)+360)%360;
            if(rotation==0){rotated=raw;raw=null;}
            else {Matrix matrix=new Matrix();matrix.postRotate(rotation);
                rotated=Bitmap.createBitmap(raw,0,0,raw.getWidth(),raw.getHeight(),matrix,true);}
            input=new BitmapImageBuilder(rotated).build();
            long timestamp=SystemClock.uptimeMillis();
            if(timestamp<=lastTimestamp)timestamp=lastTimestamp+1;
            lastTimestamp=timestamp;
            FaceLandmarkerResult result=landmarker.detectForVideo(input,timestamp);
            List<List<NormalizedLandmark>> all=result.faceLandmarks();
            int count=Math.min(3,all.size());
            float[][] meshes=new float[count][LANDMARK_COUNT*3];
            float[][] expressions=new float[count][BLENDSHAPE_COUNT];
            float[][] poses=new float[count][16];
            Optional<List<List<Category>>> blendResult=result.faceBlendshapes();
            Optional<List<float[]>> matrixResult=result.facialTransformationMatrixes();
            for(int f=0;f<count;f++){
                List<NormalizedLandmark> points=all.get(f);
                float[] mesh=meshes[f];
                for(int i=0;i<Math.min(LANDMARK_COUNT,points.size());i++){
                    NormalizedLandmark p=points.get(i);
                    mesh[i*3]=p.x();mesh[i*3+1]=p.y();mesh[i*3+2]=p.z();
                }
                if(blendResult.isPresent() && f<blendResult.get().size()){
                    for(Category category:blendResult.get().get(f)){
                        int index=blendIndex(category.categoryName());
                        if(index>=0)expressions[f][index]=category.score();
                    }
                }
                if(matrixResult.isPresent() && f<matrixResult.get().size()) {
                    float[] matrix=matrixResult.get().get(f);
                    if(matrix!=null)System.arraycopy(matrix,0,poses[f],0,Math.min(16,matrix.length));
                }
                if(previous[f]!=null&&previous[f].length==mesh.length)
                    for(int i=0;i<mesh.length;i++)mesh[i]=previous[f][i]*0.35f+mesh[i]*0.65f;
                previous[f]=mesh.clone();
            }
            for(int i=count;i<previous.length;i++)previous[i]=null;
            if(!closed&&listener!=null)listener.onFaces(new FaceFrame(meshes,expressions,poses,timestamp),
                rotated.getWidth(),rotated.getHeight());
        } catch(Exception e) {
            if(!closed&&listener!=null)listener.onError("Face tracking frame failed: "+e.getMessage());
        } finally {
            if(input!=null)try{input.close();}catch(Exception ignored){}
            if(rotated!=null&&!rotated.isRecycled())rotated.recycle();
            if(raw!=null&&!raw.isRecycled())raw.recycle();
            try{image.close();}catch(Exception ignored){}
            busy.set(false);
        }
    }

    private static Bitmap toBitmap(Image image) {
        int w=image.getWidth(),h=image.getHeight();
        Image.Plane[] p=image.getPlanes();
        ByteBuffer y=p[0].getBuffer(),u=p[1].getBuffer(),v=p[2].getBuffer();
        int yr=p[0].getRowStride(),yp=p[0].getPixelStride();
        int ur=p[1].getRowStride(),up=p[1].getPixelStride();
        int vr=p[2].getRowStride(),vp=p[2].getPixelStride();
        int[] pixels=new int[w*h];
        for(int row=0;row<h;row++)for(int col=0;col<w;col++){
            int yy=(y.get(row*yr+col*yp)&255)-16;
            int uu=(u.get((row>>1)*ur+(col>>1)*up)&255)-128;
            int vv=(v.get((row>>1)*vr+(col>>1)*vp)&255)-128;
            int c=Math.max(0,yy);
            int r=clamp((298*c+409*vv+128)>>8);
            int g=clamp((298*c-100*uu-208*vv+128)>>8);
            int b=clamp((298*c+516*uu+128)>>8);
            pixels[row*w+col]=0xff000000|(r<<16)|(g<<8)|b;
        }
        Bitmap bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
        bitmap.setPixels(pixels,0,w,0,0,w,h);
        return bitmap;
    }
    private static int clamp(int v){return Math.max(0,Math.min(255,v));}

    // Full ARKit 52-channel ordering; scores remain available to every lens.
    private static final String[] BLEND_NAMES={
        "browDownLeft","browDownRight","browInnerUp","browOuterUpLeft","browOuterUpRight",
        "cheekPuff","cheekSquintLeft","cheekSquintRight","eyeBlinkLeft","eyeBlinkRight",
        "eyeLookDownLeft","eyeLookDownRight","eyeLookInLeft","eyeLookInRight","eyeLookOutLeft",
        "eyeLookOutRight","eyeLookUpLeft","eyeLookUpRight","eyeSquintLeft","eyeSquintRight",
        "eyeWideLeft","eyeWideRight","jawForward","jawLeft","jawOpen","jawRight","mouthClose",
        "mouthDimpleLeft","mouthDimpleRight","mouthFrownLeft","mouthFrownRight","mouthFunnel",
        "mouthLeft","mouthLowerDownLeft","mouthLowerDownRight","mouthPressLeft","mouthPressRight",
        "mouthPucker","mouthRight","mouthRollLower","mouthRollUpper","mouthShrugLower",
        "mouthShrugUpper","mouthSmileLeft","mouthSmileRight","mouthStretchLeft","mouthStretchRight",
        "mouthUpperUpLeft","mouthUpperUpRight","noseSneerLeft","noseSneerRight","tongueOut"
    };
    private static int blendIndex(String name){
        for(int i=0;i<BLEND_NAMES.length;i++)if(BLEND_NAMES[i].equals(name))return i;
        return -1;
    }

    @Override public void close(){
        closed=true;
        worker.post(()->{if(landmarker!=null){try{landmarker.close();}catch(Exception ignored){}landmarker=null;}thread.quitSafely();});
    }
}
