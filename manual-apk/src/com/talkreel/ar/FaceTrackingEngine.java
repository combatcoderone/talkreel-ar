package com.talkreel.ar;

import android.media.Image;
import android.content.Context;
import com.google.android.gms.tasks.Task;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.Face;
import com.google.mlkit.vision.face.FaceDetection;
import com.google.mlkit.vision.face.FaceDetector;
import com.google.mlkit.vision.face.FaceDetectorOptions;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** Throttled ML Kit contour detector for live camera frames. */
public final class FaceTrackingEngine implements AutoCloseable {
    public interface Listener { void onFaces(List<Face> faces, int width, int height); }
    private final FaceDetector detector;
    private final Listener listener;
    private final AtomicBoolean busy = new AtomicBoolean(false);
    private volatile boolean closed = false;

    public FaceTrackingEngine(Context context, Listener listener) {
        this.listener = listener;
        FaceDetectorOptions options = new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
                .setContourMode(FaceDetectorOptions.CONTOUR_MODE_ALL)
                .setMinFaceSize(0.08f)
                .build();
        detector = FaceDetection.getClient(options);
    }

    public void process(final Image image, int rotationDegrees) {
        if (image == null) return;
        if (closed || !busy.compareAndSet(false, true)) { image.close(); return; }
        final int width = image.getWidth();
        final int height = image.getHeight();
        try {
            InputImage input = InputImage.fromMediaImage(image, rotationDegrees);
            Task<List<Face>> task = detector.process(input);
            task.addOnSuccessListener(faces -> {
                if (!closed && listener != null) listener.onFaces(faces, width, height);
            });
            task.addOnFailureListener(error -> {
                // A failed frame is dropped; the next camera frame is still processed.
            });
            task.addOnCompleteListener(done -> {
                try { image.close(); } catch (Exception ignored) {}
                busy.set(false);
            });
        } catch (Exception error) {
            try { image.close(); } catch (Exception ignored) {}
            busy.set(false);
        }
    }

    @Override public void close() {
        closed = true;
        detector.close();
    }
}
