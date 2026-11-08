package com.talkreel.ar;

import org.junit.Test;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

public class FaceTrackingMathTest {
    private static float[] face(float x,float y) {
        float[] mesh=new float[FaceTrackingEngine.LANDMARK_COUNT*3];
        int[] anchors={1,152,234,454};
        for(int index:anchors){mesh[index*3]=x;mesh[index*3+1]=y;}
        return mesh;
    }

    @Test public void detectionsCanReorderWithoutSwappingTrackHistory() {
        float[][] current={face(0.74f,0.50f),face(0.26f,0.49f)};
        float[][] oldCenters={{0.25f,0.50f},{0.75f,0.50f},{0f,0f}};
        boolean[] valid={true,true,false};
        assertArrayEquals(new int[]{1,0},FaceTrackingMath.associate(current,oldCenters,valid,0.20f));
    }

    @Test public void newFaceDoesNotStealDistantTrack() {
        float[][] current={face(0.95f,0.90f)};
        float[][] oldCenters={{0.15f,0.10f}};
        assertArrayEquals(new int[]{-1},FaceTrackingMath.associate(current,oldCenters,new boolean[]{true},0.20f));
    }

    @Test public void centerUsesFourStableFacialAnchors() {
        float[] mesh=face(0.42f,0.61f);
        assertEquals(0.42f,FaceTrackingMath.centerX(mesh),0.00001f);
        assertEquals(0.61f,FaceTrackingMath.centerY(mesh),0.00001f);
    }
}
