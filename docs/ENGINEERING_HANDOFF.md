# TalkReel AR — engineering handoff

## Current integration candidate

- Repository: https://github.com/combatcoderone/talkreel-ar
- Candidate branch: `fix/arcore-latest-workflow`
- Candidate commit: `844062ce58985915ebcabd618f4e11badc7b37a8`
- Pull request: https://github.com/combatcoderone/talkreel-ar/pull/9
- Baseline: `feature/arcore-face-studio`
- Package: `com.talkreel.ar`
- Baseline version: `0.11.0` (versionCode 16)
- ARCore: `com.google.ar:core:1.57.0`
- Build: Gradle 8.13 / Android Gradle Plugin 8.13.2 / compileSdk 36 / minSdk 26 / targetSdk 36

This branch is an integration candidate, not a release-certified build. The ARCore baseline had a successful build/lint/package-verification workflow before the small workflow-trigger change. The new candidate branch still needs its own CI run and a physical-device test pass.

## Preserve these existing behaviors

1. ARCore Augmented Faces front-camera path and face tracking lifecycle.
2. Camera/microphone permission handling and recorder state transitions.
3. Cleanup when startup fails, recording stops, or the activity backgrounds.
4. App-private media storage and public Movies publishing on Android 10+.
5. Existing clips list/playback, music download safety, teleprompter, captions, overlay controls, and social-app handoff.

Do not replace the ARCore lifecycle or recording implementation with an older branch's MainActivity wholesale.

## Other branch work to review and port selectively

- `feature/presenter-control-ui-v0.8`: inspect presenter controls and teleprompter/UI changes. Cherry-pick individual UI behavior only after reconciling MainActivity and AppState.
- `feature/snapchat-style-ar-v0.9`: inspect additional AR preview effects and assets. Keep preview-only features clearly labelled until their pixels are present in exported recordings.
- `feature/advanced-ar-pipeline-v1.0`: contains an `ArCompositor` path that routes camera frames to a MediaRecorder surface and applies filters. This is architecturally significant but changes camera session ownership and recording. Do not enable without checking surface lifecycle, camera session reconfiguration, stop/release paths, orientation, encoder compatibility, and physical-device behavior.
- `feature/media3-export-foundation`: contains a post-recording Media3 export pipeline for captions/music. It uses a separate Gradle project and older target/version metadata. Port as a separate optional export feature only after validating Media3 API compatibility, audio mixing/loop duration, caption timing, output temp-file handling, cancellation, storage permissions, and preservation of the original clip.

Branches are intentionally retained as source history. Do not bulk-merge them.

## Required build gates

1. Run the branch's Android workflow: Gradle assembleDebug and lintDebug.
2. Verify APK applicationId, versionCode/versionName, min/target SDK, signing, and artifact size.
3. Install the APK on a physical Samsung Galaxy S23 FE.
4. Exercise clean install, upgrade install, camera/mic permission allow/deny, ARCore missing/outdated, front camera tracking, camera contention, portrait/landscape, background/foreground during startup and recording, record/pause/resume/stop, playback, sharing, and long-session thermal behavior.
5. Capture logcat for every failure and fix the root cause before enabling optional compositor/export paths.

A green CI build proves compilation and packaging only; it does not prove camera, audio, AR, or media behavior on a device.

## Known product gaps from the ARCore release checklist

- AR preview is not yet composited into saved MP4 recordings.
- No production mask/glasses/makeup/hat/accessory asset library.
- Captions are not burned into MP4.
- Music is not digitally mixed into recorded audio.
- No production Google Play Billing/entitlement flow.
- Release signing, privacy policy/data safety, Play listing, accessibility audit, and device acceptance are not complete.

## Suggested integration order

1. Stabilize the ARCore branch and its camera/recording lifecycle.
2. Port presenter UI improvements in small commits.
3. Port preview-only AR treatments and clearly separate preview from recorded output.
4. Prototype compositor behind a disabled feature flag; test on device before enabling.
5. Add Media3 post-export as an optional non-destructive action that never overwrites the source clip.
6. Re-run the full acceptance checklist after every camera, encoder, or storage change.
