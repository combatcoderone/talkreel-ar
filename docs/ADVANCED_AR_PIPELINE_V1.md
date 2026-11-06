# Advanced AR recording pipeline — v1.0 engineering scope

This document is the implementation contract for the advanced AR upgrade. It is intentionally additive: existing TalkReel workflows remain available while the new camera/render path is developed and verified.

## Product requirement

AR effects must be part of the actual MP4 pixels and audio/video timeline, not just a transparent preview overlay. A clip recorded with an AR effect must show that effect when played outside TalkReel (Gallery, Instagram, YouTube, TikTok, desktop players).

## Rendering architecture

1. Camera2 produces frames into a dedicated external OES texture.
2. An EGL/OpenGL ES compositor owns the preview and encoder output surfaces. The same timestamped frame graph feeds both outputs.
3. A face-analysis stage supplies tracked facial landmarks/contours, confidence, pose, and tracking identity. Bounding-box-only Camera2 statistics are not an acceptable final landmark source.
4. The compositor renders the camera image, face-attached effect layers, optional beauty adjustments, captions/branding overlays, and color treatment.
5. MediaCodec encodes the composited video. MediaMuxer combines encoded video with microphone audio and optional licensed music. Audio timestamps and pause/resume behavior must remain synchronized.
6. Export finalization verifies a playable MP4, non-zero video/audio tracks, duration, dimensions, and monotonic timestamps before exposing Share.

## Effect families

- Beauty Studio: adjustable skin smoothing, tone/brightness, and subtle eye/face enhancement. Defaults must be conservative and every adjustment must be disableable.
- Animal Lenses: tracked ears, nose/muzzle, whiskers, and animated accents anchored to facial landmarks.
- Creative Masks: face-contour masks, glasses, crowns, neon accents, and themed overlays that remain aligned through head movement.
- Makeup Studio: landmark-anchored lipstick, blush, eyeliner, and eyeshadow with opacity/intensity controls.
- Effect controls: effect selection, intensity, front/rear camera behavior, mirror preview, and reset.

Effects must handle face loss/reacquisition, multiple faces, changing scale, rotation, partial occlusion, and low light without leaving stale graphics on screen. Tracking should be smoothed and prediction bounded to avoid jitter.

## Preserve existing TalkReel features

Do not remove or silently regress:
- script editor, adjustable editor height, teleprompter scrolling/speed, pause/resume/reset controls;
- Camera face on/off selection and existing recording controls;
- captions, language selection, RTL handling, caption color, SRT export;
- music catalog, download/play controls, selected-track behavior, and music toggle;
- clips listing, file provider, sharing, social-app launch shortcuts;
- Overlay Companion and its honest limitation that it cannot control another app's native recording session;
- saved preferences, timer, coach, silence log, and existing export modes.

The recording engine may be replaced. UI behavior and user data must be migrated rather than discarded.

## Platform and build policy

- Feature completeness and stable real-time rendering take priority over legacy device support.
- Keep the current API 26 minimum only if the selected face-analysis and encoder/compositor implementation can be tested reliably there. Otherwise set the documented minimum to the lowest API level that the complete pipeline actually supports; do not guess based on compile-time availability.
- Declare and check required GLES capabilities and camera hardware at runtime. Provide a clear unsupported-device message instead of crashing.
- Use a reproducible Gradle build with pinned dependency versions and CI artifact verification. Never commit signing keys or passwords. Release signing must use a stable owner-controlled keystore; CI's ephemeral key is for test artifacts only.
- The shipped source remains the authoritative app. Legacy prototype source must not be used to produce the APK.

## Release acceptance gates

A release is not complete until all gates pass:

1. CI builds, aligns, signs, and verifies the APK.
2. Instrumented/unit tests cover frame transforms, front-camera mirroring, landmark-to-output coordinate mapping, effect state, and timestamp handling.
3. Device tests cover at least one supported lower-tier device and one recent device; front/rear camera, 720p/1080p, 30 fps, pause/resume, 60-second recording, low-light tracking, face loss/reacquisition, and thermal/load behavior.
4. Export tests inspect MP4 tracks, dimensions, duration, audio/video sync, and confirm visible AR pixels in the exported file.
5. Regression checklist verifies every preserved TalkReel feature above.
6. Artifact is downloaded from the successful workflow run and independently checked. Do not call a build "released" solely because a workflow started or a PR merged.

## Rollout

Implement behind an AR engine boundary, then switch the recording path only after the compositor passes export and regression tests. Keep the legacy camera recording path as a temporary fallback during development, not as the final user-facing AR behavior. Do not advertise a filter as export-enabled until the encoded MP4 verification passes.
