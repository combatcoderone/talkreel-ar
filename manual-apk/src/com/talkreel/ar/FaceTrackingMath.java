package com.talkreel.ar;

/** Small, deterministic face-association helper shared by the tracker and JVM tests. */
final class FaceTrackingMath {
    private static final int[] CENTER_LANDMARKS={1,152,234,454};

    private FaceTrackingMath() {}

    static float centerX(float[] mesh) {
        float sum=0f;
        for(int index:CENTER_LANDMARKS)sum+=mesh[index*3];
        return sum*0.25f;
    }

    static float centerY(float[] mesh) {
        float sum=0f;
        for(int index:CENTER_LANDMARKS)sum+=mesh[index*3+1];
        return sum*0.25f;
    }

    /**
     * Greedy nearest-center matching. Each previous track can be consumed once;
     * detections with no prior center inside maxDistance start a fresh track.
     */
    static int[] associate(float[][] detections,float[][] previousCenters,
                           boolean[] previousValid,float maxDistance) {
        int[] matches=new int[detections.length];
        boolean[] used=new boolean[previousCenters.length];
        java.util.Arrays.fill(matches,-1);
        for(int i=0;i<detections.length;i++) {
            float x=centerX(detections[i]),y=centerY(detections[i]);
            int best=-1;
            float bestDistance=maxDistance;
            for(int j=0;j<previousCenters.length;j++) {
                if(j>=previousValid.length||!previousValid[j]||used[j])continue;
                float dx=x-previousCenters[j][0],dy=y-previousCenters[j][1];
                float distance=(float)Math.sqrt(dx*dx+dy*dy);
                if(distance<bestDistance){bestDistance=distance;best=j;}
            }
            if(best>=0){matches[i]=best;used[best]=true;}
        }
        return matches;
    }
}
