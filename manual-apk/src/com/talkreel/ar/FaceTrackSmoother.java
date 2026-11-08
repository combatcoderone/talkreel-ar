package com.talkreel.ar;

import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.PointF;
import android.hardware.camera2.params.Face;

/**
 * Single-primary-face temporal tracker for Camera2 hardware face detection.
 * Uses vendor face IDs when available, then geometric association, confidence
 * gating, adaptive exponential smoothing, and a short confidence-fading coast.
 *
 * This is a robust fallback tracker, not a substitute for dense 3D landmarks.
 */
final class FaceTrackSmoother {
    static final class Snapshot {
        final RectF bounds;
        final float confidence;
        final boolean coasting;
        final long timestampNs;
        final PointF leftEye, rightEye, mouth;
        Snapshot(RectF bounds, float confidence, boolean coasting, long timestampNs,
                 PointF leftEye, PointF rightEye, PointF mouth) {
            this.bounds = new RectF(bounds);
            this.confidence = confidence;
            this.coasting = coasting;
            this.timestampNs = timestampNs;
            this.leftEye = leftEye == null ? null : new PointF(leftEye.x, leftEye.y);
            this.rightEye = rightEye == null ? null : new PointF(rightEye.x, rightEye.y);
            this.mouth = mouth == null ? null : new PointF(mouth.x, mouth.y);
        }
    }

    private static final long COAST_NS = 180_000_000L;
    private static final float MIN_SCORE = 35f;
    private RectF smoothed;
    private int faceId = -1;
    private long lastSeenNs;
    private long lastUpdateNs;
    private float vx, vy, vw, vh;
    private PointF smoothLeftEye, smoothRightEye, smoothMouth;

    synchronized Snapshot update(Face[] faces, Rect activeArray, Rect crop,
                                 boolean mirror, long timestampNs) {
        Face best = choose(faces, activeArray, crop, mirror);
        if (best == null) return coast(timestampNs);

        RectF measured = CameraTransform.mapSensorRect(best.getBounds(), activeArray, crop, mirror);
        if (measured == null) return coast(timestampNs);

        float score = best.getScore();
        PointF leftEye = CameraTransform.mapSensorPoint(best.getLeftEyePosition(), activeArray, crop, mirror);
        PointF rightEye = CameraTransform.mapSensorPoint(best.getRightEyePosition(), activeArray, crop, mirror);
        PointF mouth = CameraTransform.mapSensorPoint(best.getMouthPosition(), activeArray, crop, mirror);
        if (score < MIN_SCORE) return coast(timestampNs);
        float dt = lastUpdateNs == 0 ? 1f / 30f :
                Math.max(1f / 120f, Math.min(0.15f, (timestampNs - lastUpdateNs) / 1_000_000_000f));

        if (smoothed == null || timestampNs - lastSeenNs > COAST_NS * 2) {
            smoothed = new RectF(measured);
            vx = vy = vw = vh = 0f;
            smoothLeftEye = leftEye; smoothRightEye = rightEye; smoothMouth = mouth;
        } else {
            float cx = measured.centerX(), cy = measured.centerY();
            float sx = smoothed.centerX(), sy = smoothed.centerY();
            float width = measured.width(), height = measured.height();
            float alpha = CameraTransform.clamp(0.28f + Math.abs(cx - sx) * 0.8f +
                    Math.abs(cy - sy) * 0.8f, 0.28f, 0.78f);
            float oldCx = sx, oldCy = sy, oldW = smoothed.width(), oldH = smoothed.height();
            float newCx = lerp(sx, cx, alpha);
            float newCy = lerp(sy, cy, alpha);
            float newW = lerp(oldW, width, alpha);
            float newH = lerp(oldH, height, alpha);
            vx = lerp(vx, (newCx - oldCx) / dt, 0.35f);
            vy = lerp(vy, (newCy - oldCy) / dt, 0.35f);
            vw = lerp(vw, (newW - oldW) / dt, 0.30f);
            vh = lerp(vh, (newH - oldH) / dt, 0.30f);
            smoothed.set(newCx - newW * 0.5f, newCy - newH * 0.5f,
                    newCx + newW * 0.5f, newCy + newH * 0.5f);
            smoothLeftEye = smoothPoint(smoothLeftEye, leftEye, alpha);
            smoothRightEye = smoothPoint(smoothRightEye, rightEye, alpha);
            smoothMouth = smoothPoint(smoothMouth, mouth, alpha);
        }
        faceId = best.getId();
        lastSeenNs = timestampNs;
        lastUpdateNs = timestampNs;
        return new Snapshot(smoothed, score / 100f, false, timestampNs, smoothLeftEye, smoothRightEye, smoothMouth);
    }

    private Face choose(Face[] faces, Rect activeArray, Rect crop, boolean mirror) {
        if (faces == null || faces.length == 0 || activeArray == null) return null;
        Face best = null;
        float bestCost = Float.MAX_VALUE;
        for (Face face : faces) {
            if (face == null || face.getScore() < MIN_SCORE) continue;
            RectF candidate = CameraTransform.mapSensorRect(face.getBounds(), activeArray, crop, mirror);
            if (candidate == null || candidate.width() < 0.015f || candidate.height() < 0.015f) continue;
            float area = candidate.width() * candidate.height();
            float cost;
            if (smoothed == null) {
                cost = -area * 4f - face.getScore() / 100f;
            } else {
                float dx = candidate.centerX() - smoothed.centerX();
                float dy = candidate.centerY() - smoothed.centerY();
                float scale = Math.abs((float)Math.log(Math.max(0.01f,
                        candidate.width() / Math.max(0.01f, smoothed.width()))));
                float idPenalty = (faceId >= 0 && face.getId() >= 0 && faceId != face.getId()) ? 2.0f : 0f;
                cost = (float)Math.sqrt(dx * dx + dy * dy) + scale * 0.18f + idPenalty - area * 0.08f;
            }
            if (cost < bestCost) { bestCost = cost; best = face; }
        }
        if (best != null && smoothed != null && bestCost > 1.1f) return null;
        return best;
    }

    private Snapshot coast(long timestampNs) {
        if (smoothed == null || lastSeenNs == 0) return null;
        long age = timestampNs - lastSeenNs;
        if (age < 0 || age > COAST_NS) {
            reset();
            return null;
        }
        float t = age / (float)COAST_NS;
        float seconds = age / 1_000_000_000f;
        float dx = CameraTransform.clamp(vx * seconds, -0.06f, 0.06f);
        float dy = CameraTransform.clamp(vy * seconds, -0.06f, 0.06f);
        RectF predicted = new RectF(smoothed);
        predicted.offset(dx, dy);
        return new Snapshot(predicted, 1f - t, true, timestampNs, smoothLeftEye, smoothRightEye, smoothMouth);
    }

    synchronized void reset() {
        smoothed = null;
        faceId = -1;
        lastSeenNs = lastUpdateNs = 0;
        vx = vy = vw = vh = 0f;
        smoothLeftEye = smoothRightEye = smoothMouth = null;
    }

    private static PointF smoothPoint(PointF oldPoint, PointF next, float alpha) {
        if (next == null) return null;
        if (oldPoint == null) return new PointF(next.x, next.y);
        return new PointF(lerp(oldPoint.x, next.x, alpha), lerp(oldPoint.y, next.y, alpha));
    }

    private static float lerp(float a, float b, float t) { return a + (b - a) * t; }
}
