# TalkReel Pro, v0.6.0, updated for v0.7.0

## v0.6.1 correction

Item 3 above said onResume reopens only if the camera was released for
Instagram or YouTube. v0.6.1 changed that: onResume reopens the camera
whenever the camera is missing and the TextureView is available, not only
after a social app release. That also covers the first launch after the
permission dialog, and a surface recreate. See docs/TEST-0.6.1.md.

## v0.7.0 change

The old Sound button is deleted. SoundEngine.java and the SOUNDS list are
gone. Music is now real songs, in a Music section of its own. See
docs/MUSIC.md. Pro features in v0.7.0 are the same as v0.6, plus the Music
section is free for all tracks. The Pro list below still says "sounds" in
the Free line, that meant the old beeps, which are now removed.

## What was wrong in v0.5, fixed in v0.6

Keeps open and close was a real bug, not a phone problem.

1. Overlay Record sent a broadcast AND set isRecording itself.
   MainActivity then set it again, so Record became Stop, and Stop did nothing.
   Fix: MainActivity is the only owner of recording state.
   The overlay only sends a request. It polls state every 500ms to update its buttons.
2. Record in the app started the foreground service with ACTION_START_OVERLAY,
   so every Record popped the overlay over the app itself.
   Fix: Record starts ACTION_START_SERVICE only, no overlay.
   Overlay appears only when the user taps Overlay.
3. Camera open had no guard. Two opens raced, one failed, the other closed it,
   and onResume reopened it again.
   Fix: cameraOpening flag, one open at a time, onResume reopens only once
   and only if the camera was released for Instagram or YouTube.

## Basic AR removed

Deleted: Beauty, Cartoon, Animal, Glasses, Glow, Circle filter, FaceOverlayView, CircleOverlayView.
Replaced with ProFrameView: a clean, transparent framing guide.
Thin white circle, rule of thirds lines, orange face box when a face is found.
No dim, no tint, no cartoon. Frame can be turned off.

## Pro features, users pay for these

Free: 720x1280, teleprompter, captions on screen, sounds, Share, Clips.
Pro, toggle Pro: On in the app for testing:
- 1080x1920 export
- SRT captions file next to the clip, with timestamps
- Silence cut list, .txt next to the clip, cuts longer than 3 seconds
- AI Script Coach, full advice: hook score, word count, estimated duration, plain fix
- Audio meter and light check in the app

Pro is a preview unlock in v0.6. Real payment is Google Play Billing,
to be added before Play release. Price from the plan: $9.99 CAD per month,
$59.99 per year. Free stays usable without Pro.

## How to test v0.6

1. Uninstall old TalkReel first, then install v0.6.0. Same release key as v0.3 to v0.5, so an update should work, but a clean install is safest.
2. Open the app. Camera should show once and stay on. No overlay should appear.
3. Tap Record. No overlay should pop up. Tap Pause, then Resume, then Stop.
   Each tap should do only one thing. Clip saves to Movies/TalkReelAR.
4. Tap Overlay. Only then the teleprompter floats. Tap Record in the overlay,
   then Pause, then Stop. Each tap should do only one thing.
5. Tap Instagram. TalkReel releases its camera, Instagram opens directly.
   Come back to TalkReel. Camera should come back once.
6. Turn Pro: On, record a short clip with Captions on. Check Movies/TalkReelAR
   for the .mp4, .srt and -cuts.txt files.

## Honest limits in v0.6

- The overlay is a teleprompter over Instagram, it cannot change Instagram's own recording or crop its video.
- SRT and cut list are files next to the clip, captions are not burned into the video yet. That needs a video encoder step, planned for v0.7.
- Pro payment is not wired yet. The Pro toggle is for testing the Pro features.
- Persian and Arabic captions depend on the phone's Google speech service.
