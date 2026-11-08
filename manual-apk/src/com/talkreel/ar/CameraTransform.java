package com.talkreel.ar;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Point;
import android.graphics.PointF;

/**
 * Maps Camera2 face-statistics coordinates into the camera stream's normalized
 * crop space. Camera2 face rectangles are expressed in the active-array
 * coordinate system; the per-frame crop region must be applied before the
 * coordinates are sent to the compositor.
 *
 * Display/sensor rotation is intentionally not guessed here: the image stream
 * transform and the geometry transform must be configured together for a
 * device's actual stream orientation. This class handles crop and mirroring.
 */
final class CameraTransform {
    private CameraTransform() {}

    static RectF mapSensorRect(Rect face, Rect activeArray, Rect cropRegion,
                               boolean mirrorX) {
        if (face == null || activeArray == null || activeArray.isEmpty()) return null;
        Rect crop = cropRegion == null ? new Rect(activeArray) : new Rect(cropRegion);
        if (crop.isEmpty()) crop.set(activeArray);
        if (!crop.intersect(activeArray)) return null;

        float left = clamp((face.left - crop.left) / (float) crop.width(), 0f, 1f);
        float top = clamp((face.top - crop.top) / (float) crop.height(), 0f, 1f);
        float right = clamp((face.right - crop.left) / (float) crop.width(), 0f, 1f);
        float bottom = clamp((face.bottom - crop.top) / (float) crop.height(), 0f, 1f);
        if (mirrorX) {
            float oldLeft = left;
            left = 1f - right;
            right = 1f - oldLeft;
        }
        if (right <= left || bottom <= top) return null;
        return new RectF(left, top, right, bottom);
    }

    static PointF mapSensorPoint(Point point, Rect activeArray, Rect cropRegion, boolean mirrorX) {
        if (point == null || activeArray == null || activeArray.isEmpty()) return null;
        Rect crop = cropRegion == null ? new Rect(activeArray) : new Rect(cropRegion);
        if (crop.isEmpty()) crop.set(activeArray);
        if (!crop.intersect(activeArray)) return null;
        float x = clamp((point.x - crop.left) / (float) crop.width(), 0f, 1f);
        float y = clamp((point.y - crop.top) / (float) crop.height(), 0f, 1f);
        if (mirrorX) x = 1f - x;
        return new PointF(x, y);
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
