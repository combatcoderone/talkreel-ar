# Robust face AR pipeline — implementation contract

Status: engineering design and release gate. This document does not claim that dense landmark tracking, lighting compensation, or mesh projection have already been integrated. The current app still uses Camera2 face rectangles in its AR compositor.

## Goal

Replace rectangle-driven effects with a timestamped, pose-aware, lighting-aware face AR pipeline. The pipeline must remain stable during head rotation, translation, moderate partial occlusion, changing exposure, camera/display rotation, and recording. It must fail gracefully when the face is not observable; no tracker can recover a face that is fully outside the image or fully occluded.

## Required runtime graph

```
Camera2 repeating request
  ├─ SurfaceTexture / OES stream ─────────────────────────────┐
  │                                                          │
  └─ ImageReader YUV_420_888 -> bounded frame queue           │
        -> async Face Landmarker (LIVE_STREAM)                 │
        -> timestamp + camera-frame transform metadata        │
        -> face association / confidence / pose estimation    │
        -> temporal filter + short prediction                 │
        -> canonical face mesh + per-frame lighting estimate  │
                                                               v
                                  shared CameraTransform -> GL compositor
                                    ├─ preview EGL surface
                                    └─ encoder EGL surface
```

### Frame ownership and latency

- Keep camera capture, inference, and GL rendering off the UI thread.
- Use a bounded inference queue (capacity 1 or 2); discard stale frames rather than allowing latency to grow.
- Every frame and inference result carries the camera timestamp. Never apply a result to a different frame without measuring timestamp age.
- Close every ImageReader Image in a finally block immediately after copying/consuming its planes. Do not retain Image objects in the tracker.
- Avoid per-frame Bitmap allocation and CPU RGBA conversion. Prefer YUV input supported by the inference API or a reusable GPU path.
- Preview and encoder must consume the same compositor state and frame timestamp. Report dropped frames, inference age, and encoder swap failures.

## Face tracking engine

Use MediaPipe Face Landmarker in live-stream mode as the primary tracker. Consume its 478 3D landmarks, face transformation matrix, and blendshape outputs. Keep model initialization and teardown tied to camera lifecycle. The model asset must be packaged and versioned with the app; dependency and asset size must be included in APK/AAB validation.

### Tracking state machine

Use explicit states: SEARCHING, ACQUIRING, TRACKING, COASTING, and LOST.

- SEARCHING: run detection at the configured cadence; no face-attached effect is rendered.
- ACQUIRING: require consistent, sufficiently confident detections across multiple frames before attaching effects.
- TRACKING: update landmark geometry and pose; reject implausible jumps and low-confidence landmarks.
- COASTING: for a short bounded interval, predict translation/rotation/scale from recent velocity while reducing effect opacity. Never freeze an old mesh indefinitely.
- LOST: fade out, clear stale anchors, and return to SEARCHING. Reacquire without snapping to a different person.

For multiple faces, associate detections to tracks using a cost combining normalized landmark/box IoU, center displacement, scale change, and pose change. Gate associations by maximum timestamp gap and motion. Do not use array index as identity. If product scope is one face, explicitly select the primary face and retain its identity until LOST.

### Temporal stabilization

- Filter translation and scale with a One Euro filter or confidence-adaptive Kalman filter.
- Smooth rotation as quaternions (slerp); never interpolate Euler angles directly across wrap boundaries.
- Smooth landmark positions with per-landmark confidence weighting. Preserve high-frequency mouth/eye motion where blendshapes drive expression effects.
- Estimate velocity only from timestamped samples; clamp acceleration and prediction horizon.
- Increase smoothing when confidence drops or motion is noisy; decrease smoothing during fast intentional movement.
- Keep prediction short (typically a few frames); fade during COASTING. Do not disguise prolonged tracking loss as a stable lock.

## Camera geometry and orientation

Create one immutable CameraFrameMetadata record per frame containing camera ID, lens facing, sensor orientation, display rotation, crop region, active-array bounds, stream dimensions, preview dimensions, encoder dimensions, and front-camera mirror policy.

Implement a single tested CameraTransform that maps:
1. sensor active-array coordinates;
2. crop-region coordinates;
3. sensor-to-device/display rotation;
4. front-camera mirroring (exactly once, with independent preview and encoded-output policy);
5. aspect-fill/fit scaling and center crop;
6. canonical normalized coordinates;
7. preview and encoder viewport coordinates.

Do not assume the SurfaceTexture OES transform maps Camera2 face coordinates. It maps texture sampling coordinates only. Use the per-frame SurfaceTexture matrix for sampling and CameraTransform for landmark/overlay geometry. The preview and encoder transforms must differ only where their target dimensions/aspect ratios require it.

Tests must cover 0/90/180/270 degree display rotations, front and rear lenses, non-zero crop regions, asymmetric preview/encoder aspect ratios, mirrored and unmirrored output, and camera sensor orientations of 90 and 270 degrees.

## Face-attached element projection

Do not position AR elements from a face rectangle. Build a canonical face coordinate system from stable 3D landmarks and the face transformation matrix.

- Define named anchors (nose bridge, nose tip, eye corners, brow, cheek, chin, forehead, lip contour, jawline) using landmark indices from a pinned model version.
- For 3D glasses, masks, hats, ears, and other rigid assets, transform asset vertices from canonical face space using the face matrix and camera projection matrix. Calibrate asset scale and origin against the canonical mesh.
- For makeup, use mesh triangles and semantic regions (lips, eyelids, brows, cheeks). Use UV coordinates and region masks; do not stretch a rectangle over the face.
- Use depth/pose-aware occlusion where supported: render face depth/occlusion mesh before virtual assets, with depth testing enabled. Avoid rendering props through the nose, cheeks, or forehead.
- Derive projection from the actual camera intrinsics and active crop where available. If intrinsics are unavailable, use a documented approximation and reduce effect confidence at extreme poses.
- Keep all geometry in one coordinate convention (right-handed/left-handed, camera-forward sign, UV origin) and test handedness. A mirrored preview must not silently invert 3D yaw or make assets jump sides.

## Lighting and exposure robustness

Lighting is a first-class input, not a face-detection side effect.

### Capture controls

- Read exposure state/metadata where available (AE state, exposure time, sensitivity, frame duration, lens aperture, AWB state, color correction gains/transform).
- Avoid fighting Camera2 auto exposure/white balance by applying aggressive per-frame software gain on top of AE. Prefer stable capture metadata and adaptive rendering.
- Optional user controls may bias exposure compensation within device-supported ranges, but must not assume identical EV ranges across cameras.
- Handle AE/AWB convergence, sudden backlight, flicker, mixed color temperatures, and transitions between indoor/outdoor scenes.

### Image analysis and compensation

Estimate luminance and color statistics from the face region, not only the full frame. Track robust percentiles (for example P10/P50/P90), highlight clipping, shadow fraction, and local contrast. Smooth estimates temporally and ignore low-confidence/occluded regions.

- Low light: lift shadows selectively with a bounded tone curve; preserve highlights and avoid amplifying sensor noise without limit.
- Backlight: use local face-region exposure compensation while protecting the background and avoiding a bright halo around the face.
- Overexposure: roll off highlights and avoid multiplying already clipped channels.
- White balance: estimate face-region chroma and adapt makeup/skin effects in a perceptually stable color space; do not force all skin toward one fixed hue.
- Flicker: low-pass temporal exposure/color estimates and avoid visible frame-to-frame pumping.
- Keep the original camera image available; all enhancement must be reversible and adjustable.
- Never apply a global blur as a substitute for skin segmentation. Beauty smoothing must be constrained by a face/skin mask and preserve eyes, nostrils, lips, hair, and edges.

Expose a conservative auto mode plus user strength controls. Clamp gains and tone-curve parameters to avoid posterization, color clipping, and noise explosions. On low confidence, reduce enhancement rather than applying extreme corrections.

## GL compositor and encoder reliability

- Check every EGL operation, including eglMakeCurrent, eglPresentationTimeANDROID, and eglSwapBuffers. Capture eglGetError and GL error state in structured logs.
- Replace empty RuntimeException catches with rate-limited diagnostics and a recoverable state transition. Do not crash the recording silently.
- Handle EGL_CONTEXT_LOST by recreating context, programs, textures, and surfaces from retained configuration; if recovery fails, stop AR effects cleanly while preserving camera recording where possible.
- Ensure GL object creation/deletion occurs on the owning GL thread with the correct context current.
- Negotiate EGL configs robustly: try supported recordable ES2 configs and log selected channel/depth/stencil attributes. Do not assume a single exact RGBA8 config exists on every device.
- Validate output dimensions, aspect ratio, color format, encoder input surface lifecycle, and presentation timestamps.
- Keep camera frame timestamps monotonic. Verify pause/resume behavior and audio/video sync in exported MP4.
- Tie SurfaceTexture and EGL teardown to a synchronous owner-thread barrier before returning from surface destruction; avoid releasing a TextureView SurfaceTexture while queued GL cleanup still references it.

## Performance and thermal policy

- Target a stable preview frame rate; run inference at a bounded cadence and reuse the latest valid pose between inference results only while its timestamp age remains within the coast window.
- Adapt inference cadence/model complexity to device performance and thermal state. Never block the camera callback on inference.
- Track p50/p95 inference latency, render time, frame drops, stale-result count, thermal state, and memory churn.
- Preallocate buffers and reuse mesh/landmark arrays. Avoid per-frame object churn.
- When overloaded, degrade in this order: reduce inference cadence, disable expensive beauty passes, reduce particle/secondary effects; preserve camera preview, recording, and basic face anchors.

## Failure and privacy behavior

- If the model fails to load, show a clear status and retain non-face-dependent filters; do not pretend tracking is active.
- If face confidence is low, fade effects rather than pinning them to an incorrect location.
- Do not upload camera frames or landmarks. Face inference should remain on-device unless a separately disclosed feature explicitly requires network processing.
- Clear frame buffers and tracker state on camera close, permission loss, app backgrounding, and camera switch.

## Required automated and device tests

### Unit tests
- CameraTransform matrix composition, crop, mirroring, all rotations, aspect-fill/fit, preview-vs-encoder mapping.
- Quaternion interpolation across ±180°; timestamp gaps; confidence-weighted smoothing; prediction horizon clamp.
- Multi-face association, identity retention, occlusion/coasting, reacquisition, and crossing tracks.
- Lighting estimator percentiles, clipped highlights, dark-frame noise guard, backlight compensation bounds, temporal flicker rejection.
- Mesh anchor mapping, handedness, projection, and depth ordering.

### Instrumentation/device matrix
- Front and rear cameras; portrait, landscape, reverse landscape; all display rotations.
- Yaw ±75° and pitch/roll ranges representative of normal use; near/far face; rapid movement; face at each image edge.
- Glasses, masks, hand-over-face, hair occlusion, partial profile, multiple faces entering/leaving/crossing.
- Dim indoor light, bright window behind face, direct sunlight, mixed color temperatures, rapid indoor/outdoor transition, LED flicker.
- Camera switch, permission revoke/regrant, social-app camera contention, background/foreground, surface recreation, EGL context loss.
- Pause/resume, long recording, thermal throttling, low storage, encoder failure, audio/video sync, MP4 orientation and mirroring.
- At minimum: one Pixel-class device and one Samsung device on the oldest and newest supported Android versions.

## Definition of done

This work is complete only when the MediaPipe model and dependency are actually wired into both supported build paths (or the manual build is explicitly documented as rectangle-only), CameraTransform is used by preview and encoder, mesh-based effects render from tracked landmarks, lighting compensation is implemented and tuned, automated tests pass, CI builds, and the device matrix is signed off. A document or successful compile alone is not evidence of robust tracking.

## Current repository gap

The current `MainActivity` consumes `CaptureResult.STATISTICS_FACES` and selects `faces[0]`. `ArCompositor` uses a face rectangle in its shader and currently ignores EGL swap results and suppresses runtime exceptions. This is a useful camera-to-EGL foundation, but it is not a robust landmark/mesh pipeline. The next implementation increment must replace the rectangle source, centralize transforms, and make GL failures observable before adding complex effects.
