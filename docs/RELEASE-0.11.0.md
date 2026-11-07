# TalkReel AR 0.11.0 — development acceptance checklist

## Build configuration

- Package: `com.talkreel.ar`
- Version code/name: `16 / 0.11.0`
- Minimum Android: API 26
- Target/compile SDK: API 36
- ARCore dependency: `com.google.ar:core:1.57.0`
- Gradle: 8.13 with Android Gradle Plugin 8.13.2

The target SDK was raised to API 36. Google Play requires API 36 for new
apps and app updates submitted from 31 August 2026. This change alone does
not make the app Play-ready; the full API 36 behavior and policy review is
still required.

## Changes in this development increment

- Dedicated ARCore Augmented Faces screen, explicitly configured for the
  front-facing camera.
- Live 468-point face mesh and three selectable preview treatments.
- Live status text distinguishes camera initialization, no-face state,
  active face tracking, and tracking exceptions.
- Recording waits for microphone permission at the point of use.
- Recording state is set only after MediaRecorder successfully starts.
- Startup/configuration errors clean up the recorder and report the failure.
- Leaving the foreground cancels an in-progress startup or stops an active
  recording, rather than silently leaving the recorder active.
- Camera preview and recording requests use continuous-video autofocus,
  automatic exposure, and automatic white balance.
- New video and music files use app-specific storage; completed video is
  additionally published into the public Movies collection on Android 10+.
- Legacy public-folder media paths are retained as read fallbacks.
- Music downloads use a temporary file, basic MP3 signature validation,
  and backup/rename replacement so a failed download does not destroy a
  previously saved track.
- Clips opens a selectable list and launches the selected video.
- The Pro test toggle is explicitly labeled as a preview, not a purchase.

## Device acceptance test — required on Samsung Galaxy S23 FE

- Install over the previous version if the signing key matches; otherwise
  uninstall the old build only after backing up any app-private media.
- Verify first-launch camera permission and just-in-time microphone prompt.
- Deny microphone permission; verify the app does not enter a false REC state.
- Grant microphone permission; record, pause, resume, and stop a 30-second
  clip. Confirm the MP4 plays and audio is present.
- Interrupt camera access, background the app during startup, and background
  it during recording. Confirm cleanup and visible status.
- Verify the clip appears in the in-app Clips list, plays, shares, and appears
  in the Android Movies collection.
- Verify old music downloads still play, new downloads survive app restart,
  and a failed replacement download does not erase an existing track.
- Verify Instagram, YouTube, and TikTok buttons launch installed apps directly.
- Run AR Studio in good and low light; check face lock, mirror direction,
  overlay alignment, 30/60 fps behavior, thermal throttling, and pause/resume.
- Test on both portrait and landscape system rotations, display cutouts,
  permission denial, ARCore missing/outdated, and camera contention.

## Still not release-complete

- ARCore rendering is preview-only and is not part of the recorded MP4.
- No production textured masks, glasses, makeup, hats, or accessory assets.
- No EGL/MediaCodec compositing path for AR effects, captions, or music.
- Music is not digitally mixed into the recorded audio track.
- Captions are not burned into the MP4.
- No real Google Play Billing integration, server-side entitlement validation,
  restore flow, or subscription lifecycle handling.
- No privacy policy, data-safety declaration, Play listing, release signing
  verification, or full accessibility audit has been completed.
- No physical Galaxy S23 FE test has been performed in this branch.

A green CI build is a compile/package check only. It is not evidence that
camera, audio, AR tracking, gallery publishing, or social app handoff works
on a physical device.


## Creator workflow additions (integration branch)

- Floating selfie preview is draggable, bounded to the app viewport, and resizable from 120 to 220 dp. It is implemented by resizing/translating the existing preview container; it does not reparent the camera `TextureView`.
- System PiP supports 9:16, 1:1, and 16:9 aspect presets. PiP entry scrolls the preview into view before hiding the other controls and restores the prior scroll position on exit.
- Music catalog search, volume adjustment, persisted user-selected audio URI, and custom audio looping for the next recording are included.
- AR Studio uses the existing ARCore Augmented Faces renderer (front camera, 468-point mesh). The AR Studio view is preview-only; the mesh is not encoded into the saved MP4.
- Device acceptance must include float drag/resize while scrolling, PiP enter/exit at different scroll positions, custom audio permission persistence and playback, catalog search, and regression testing for record/pause/resume/stop.
