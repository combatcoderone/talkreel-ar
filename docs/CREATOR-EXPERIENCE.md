# Creator experience: face tracking, selfie PiP, music and UI

## Face tracking and effects

### Native camera fallback
- Query the selected camera's `STATISTICS_INFO_AVAILABLE_FACE_DETECT_MODES` and request FULL when available, otherwise SIMPLE. Do not assume every camera supports FULL.
- Smooth face rectangles between capture results and tolerate a short tracking gap to reduce visible jitter/flicker.
- Camera2 hardware face detection is still only a rectangular face detector. It is not a facial landmark mesh and cannot guarantee correct eye/nose/mouth anchors.
- The advanced effect path is the Unity AR Foundation + ARCore face subsystem. Attach effect prefabs to the tracked face transform and smooth their world transform. Create effect-specific anchor calibration for glasses, crowns, ears, masks and makeup.

### Required real-device validation
Test at minimum on several Android devices, front camera, good/low light, glasses, different skin tones, head yaw/pitch, face partly occluded, camera distance changes and two people entering/leaving frame. Record a 30-second sample and inspect both preview and exported MP4. Track FPS, time-to-first-face, tracking-loss duration and overlay landmark error. Do not claim face tracking is reliable until this matrix passes.

## Selfie picture-in-picture

The native app now exposes Android system PiP with three aspect-ratio presets:
- Portrait 9:16
- Square 1:1
- Landscape 16:9

The user taps **Use PiP** after the front-camera preview is ready. In PiP mode, the app hides editing controls and keeps the camera preview visible. Android owns the PiP window's screen position and resize gestures; the app sets its aspect ratio, not an arbitrary pixel size or screen coordinate. Users can move/resize the system PiP window using the device's standard PiP gestures where supported.

This is not the same as an in-app floating mini-window over TalkReel's own scrollable controls. That requires a separate overlay container/window design and explicit testing of touch routing and camera lifecycle. It is not claimed as implemented here.

## Music improvements

- Search the existing catalog by title, artist, mood or intended use.
- Adjust preview/recording playback volume from the Music section.
- Play, stop, download and select-for-recording controls remain separate.
- Current catalog remains the nine previously documented tracks. Do not add new download URLs until the MP3 is fetched and checked, the source page and licence are reviewed, and any attribution/Content ID caveats are recorded.
- Important recording limitation remains: current MediaRecorder captures the microphone and camera; music playback through the phone speaker can leak into the microphone. Clean soundtrack mixing requires an encoder/audio-muxing pipeline and is not implemented by this UI work.

## UI review checklist

- Keep the selfie preview near the top, with a clear face/tracking status.
- Keep Record, Pause/Resume and Stop grouped together with clear labels.
- Group AR, camera and PiP controls together; avoid placing destructive actions beside Record.
- Put script editing and teleprompter controls in their own section.
- Keep Music search, playback, volume and selection controls together.
- Keep social launch, clips and share actions separate from capture controls.
- Test small phones, large font/display scaling, keyboard open, long scripts, RTL captions, landscape PiP and scrolling to the bottom of Music.
- Check that touch targets remain comfortably tappable and that controls do not become clipped or overlap.

## Release gates

1. Unity Editor compiles the AR project and the ARCore face subsystem initializes on a supported physical device.
2. Face effects remain attached through head movement and recover after temporary tracking loss.
3. Android PiP enters/exits without losing the camera or leaving the app in a stale UI state.
4. PiP aspect presets persist across launches.
5. Music search filters every catalog row; volume affects the active MediaPlayer.
6. Existing recording, pause/resume, captions, music selection, clip saving and sharing regression tests pass.
7. Exported MP4 is inspected separately; preview overlays are not considered recorded until a compositing pipeline is implemented.
