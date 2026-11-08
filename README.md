# TalkReel AR

Talk, record, and post. AR teleprompter app for Reels, Shorts, and TikTok.

Version: 0.10.0 (camera direction and face AR rebuild; see docs/PRODUCT-READINESS.md)
Package: com.talkreel.ar
Repo: combatcoderone/talkreel-ar
Min SDK: 26 (Android 8.0), Target SDK: 36 (Android 16)
App size: about 80 KB, no music or models bundled

## What it is

TalkReel AR is for a person who reviews a product, talks to camera, and
posts to Instagram, YouTube, or TikTok. Open the app, paste a script, press
Record, talk, press Stop, share the clip. The teleprompter scrolls while you
talk, so you do not have to memorise the script. A floating teleprompter can
also stay on top while Instagram or YouTube records.

## What is shipped

The production source is `manual-apk/`, plain Java. The standalone `manual-apk/build.sh` remains a dependency-free fallback. The Media3-enabled finished-video export requires the Gradle build (`manual-apk/build.gradle`) and its AndroidX Media3 dependencies. See docs/PRODUCT-READINESS.md before treating this integration candidate as a release.

`app/` is an older v0.1 Kotlin Compose prototype. It is kept for history
only, it is NOT the shipped app, and it does not have the v0.2 to v0.7 fixes.
See `app/LEGACY.md`. Do not build `app/` and expect v0.7.0.

## What v0.8.0 changes

- Larger script editor (260 dp default) with Editor − / Editor + sizing,
  persistent height, and a visible scrollbar.
- Teleprompter and music catalog have independent visible scrollbars.
- Face mode selects the front camera (Face: On) or rear camera (Face: Off)
  for recordings made inside TalkReel. The setting is saved.
- Overlay Companion is narrower and shorter so it obscures less of the
  social video. Its teleprompter panel has visible scrolling and Size − /
  Size + controls.
- Overlay Companion has independent Start scroll and Reset text controls.
  It does not pretend to start or stop Instagram/YouTube/TikTok recording:
  Android does not provide third-party apps a supported way to control
  another app's camera or recording session. Use the social app's native
  record/pause/stop controls. For TalkReel-owned clips, use Studio mode's
  Record, Pause/Resume, and Stop controls.

## What v0.7.0 does

### Record and teleprompter

- Front camera preview, portrait 9:16, record to Movies/TalkReelAR, with audio.
- Teleprompter starts with Record, pauses with the video, resumes from the
  last line. Speed 0.5x, 1x, 1.5x, plus auto speed that follows your voice.
- Script editor, timer, Shy Mode with only 2 lines at a time, Prompts Mode
  with keywords only.
- Record, Pause and Resume, Stop. One owner only, MainActivity, so Record
  does one thing and Stop does one thing. See docs/PRO.md.
- Volume Down starts and stops a clip.
- Clips list, Share last clip by content URI, so it works on Android 7 and newer.

### Overlay Companion and recording controls

1. **TalkReel Studio:** TalkReel owns the camera and recording. Use Record,
   Pause/Resume, and Stop in TalkReel. Face: On selects the front camera;
   Face: Off selects the rear camera. This is the mode to use when you want
   TalkReel to control the clip.
2. **Overlay Companion:** Tap Overlay, allow Draw over other apps, then open
   Instagram, YouTube, or TikTok. The compact overlay provides Start scroll,
   Reset text, text colour, Music, Size − / Size +, and Close. Drag it to
   reposition it. The script and music panels have independent scrolling.

Use the social app's native camera controls to start, pause, and stop a
social-app recording. Android does not expose another app's camera or
recording session to a third-party overlay, and only one app can own the
camera at a time. For the same reason, Face: On/Off changes the camera used
by TalkReel Studio; in Overlay Companion, switch the camera in Instagram,
YouTube, or TikTok itself. TalkReel cannot place its own live camera feed
inside another app's recording.

### Music, v0.7.0, new

The old Sound button played synthesized melodies made in code. That music
was awful, so it is deleted. SoundEngine.java and the SOUNDS list are gone.

Now there is a Music section on its own, at the bottom of the main screen,
under the script editor. 9 real songs, from open sources, free to download.

- Play: test a track first. If it is already downloaded, it plays the local
  file. If it is not downloaded yet, it streams the same download URL, so
  you can hear it before you spend data on a download. Tap Stop to stop it.
- Download: saves the MP3 in TalkReel's app-specific Music/TalkReelAR folder, about
  2 to 5 MB per track. Nothing is downloaded unless you tap Download.
  Nothing is bundled in the APK, so the app stays small.
- Use: picks this track for the next clip. Enable Music in settings and
  download the selected track before recording. In the Gradle/Media3 build,
  TalkReel mixes that track under microphone audio during finished-video
  export. The overlay's Music control is a track preview control.

The tracks, all verified on 2026-11-05, each file downloaded with curl and
confirmed as a real MP3:

- Feel Good Upbeat Motivational Pop, Music_for_Creators, 2:34, Pixabay
- Happy Upbeat Pop, LNPlusMusic, 2:04, Pixabay
- Good Mood, SoulProdMusic, 1:37, Pixabay
- Energetic Upbeat Background Music, HitsLab, 1:34, Pixabay
- Upbeat Promo Music, Maksym Malko, 1:09, Pixabay
- Upbeat Happy Indie Pop, Vivaleum, 2:38, Pixabay
- Inspirational Motivational Music, LNPlusMusic, 1:57, Pixabay
- Motivational Uplifting Outdoors, DayNigthMorning, 2:27, Pixabay
- Monkeys Spinning Monkeys, Kevin MacLeod, 2:05, CC BY 4.0, credit required

Viral means here: upbeat, happy, motivational, and funny tracks already used
a lot in Reels, Shorts, and TikTok, short enough for a 30 to 60 second clip,
with a clear hook in the first seconds.

Licenses: Pixabay License, free for commercial and non-commercial use, no
attribution required. Do not sell or re-upload a track on its own, and do
not claim you made it. Kevin MacLeod is CC BY 4.0, free even for monetized
videos, but you must credit him in the video description:
"Monkeys Spinning Monkeys by Kevin MacLeod, incompetech.com, CC BY 4.0"

Full track list, page links, download links, and other free sources are in
docs/MUSIC.md. That file also explains how to add a new track, and the rule
that a track must be downloaded and checked first.

The Media3-enabled Gradle build mixes the downloaded music track into a
finished MP4 after recording, while preserving the microphone audio. The
standalone manual build remains a fallback and does not include this optional
Media3 export stage. Export failure leaves the original recording intact.

### Captions and language

- Live on-screen captions, on-device first, online fallback.
- Auto language detection: English, Persian, Arabic, French. RTL display
  for Arabic script. Language button cycles Auto, English, Persian, Arabic,
  French.
- Persian and Arabic captions depend on the phone's Google speech service,
  so they need internet the first time if the offline model is not installed.
- Pro captions are saved as SRT and are burned into the finished MP4 by the
  Media3-enabled Gradle export path. The standalone manual build keeps SRT
  sidecars only. See docs/PRODUCT-READINESS.md.

### Framing, Pro, and Script Coach

- GPU-rendered AR effects (Glow, Glasses, Bunny, Crown, Neon, Beauty,
  Makeup, Mask, Cat, Dog) are composited into both the Studio preview and the
  recorded video. v0.10.0 rebuilds the pipeline: correct camera rotation,
  front-camera mirroring and cover-crop, eye/mouth head tracking smoothed
  across frames, and antialiased shader art. This is still Camera2 face
  detection with eye and mouth points, not a dense 3D landmark mesh or
  segmentation. Device validation remains a release gate.
  See docs/PRODUCT-READINESS.md.
- Text colour and Caption colour buttons, cycling White, Yellow, Green,
  Cyan, Orange, Pink, saved in settings.
- Face mode: Front camera (Face: On) or rear camera (Face: Off) for
  recordings made inside TalkReel. Choose before recording.
- Pro tier, preview only, payment NOT wired yet: Free is 720x1280, Pro is
  1080x1920, plus SRT captions, silence cut list, and full Script Coach.
  Real payment is Google Play Billing, to be added before Play release.
  Pro is $9.99 CAD per month or $59.99 per year in the plan, not charged
  in this build.
- Script Coach gives a hook score, word count, and estimated seconds.
  Pro-only full advice.
- Mic level meter, light check, silence warning after 3 seconds of quiet.

### Open Instagram, YouTube, TikTok

Buttons open the app directly by launch intent, never by Play Store or
browser. If the app is not installed, the app says which one is missing.

## Remaining product gaps

- AR effects are embedded in saved video through the GPU compositor. Tracking quality depends on the device's Camera2 face detector; dense landmark tracking and segmentation are not in this build.
- Media3 music/caption export is implemented in the integration branch, but its new Gradle CI job and physical-device audio/export tests must pass before release.
- Pro payment is not wired; the local Pro toggle is only for testing.
- Permanent signing, privacy/legal review, music-rights review, Android instrumentation tests and physical-device QA remain release blockers.
- See docs/PRODUCT-READINESS.md for the complete release checklist.
- Overlay cannot change or crop Instagram's own recording. It is a frame
  on top of the other app.
- Pro payment is not wired. The Pro toggle is for testing only.
- Full MediaPipe face tracking and background segmentation are not in this
  build. That was v1 work in the original plan, not shipped yet.

## What v0.6.1 fixed, after v0.6.0 blinked

v0.6.0 opened and closed at once on Android 14. Root cause, proved by
dexdump of the shipped APK: MainActivity.onCreate called the 2-argument
registerReceiver. Android 14 requires RECEIVER_EXPORTED or
RECEIVER_NOT_EXPORTED, so the 2-argument call throws SecurityException in
onCreate. v0.6.1 uses RECEIVER_NOT_EXPORTED on SDK 33 and newer, and also
fixes startForeground to pass the camera and microphone service types,
and retries opening the camera after the permission dialog, with a retry
if Record is tapped before the camera is ready.

Full proof, emulator attempts, and the phone test are in docs/TEST-0.6.1.md.
The emulator could not run in the build VM, no KVM, no x86_64 hardware
acceleration, ARM image not supported on an x86_64 host. Phone test by the
user is still required for every release.

## Install on a phone

APKs are NOT committed to this repo. Get the APK from a GitHub Release,
not from this repo.

1. Uninstall any older TalkReel first, if the signing key changed, clean
   install is safest. v0.3 and later use the release key, v0.1 and v0.2
   used the debug key, so they cannot update over each other.
2. On the phone, allow Install unknown apps for Files or Chrome.
3. Tap the APK to install.
4. Open TalkReel AR, allow Camera, Mic, and Notifications.
5. Tap Overlay, allow Draw over other apps, when Android asks.
6. Paste a script, pick a track in Music, tap Play to test it, tap Download
   if you want it on the phone, tap Use, press Record, talk, press Stop.
7. Find exported clips in Movies/TalkReelAR. Downloaded tracks are stored in TalkReel's app-specific music folder and are removed when the app is uninstalled.

If Play Protect warns, tap Details, then Install anyway, or turn off Scan
apps in Play Protect during install and turn it back on. That warning is
normal for a sideloaded app. Only a Play Store release removes it fully.
See docs/TRUST.md.

## Build the APK

Dependency-free manual fallback build (no Media3 digital mix or burned-in captions):

```
cd manual-apk
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export TALKREEL_KEYSTORE_PASS=your-keystore-password
bash build.sh
```

It uses aapt2, d8, zipalign, and apksigner from Android SDK 36, with JDK 17. It intentionally excludes the optional Media3 pipeline.
It needs a release keystore at manual-apk/release.keystore. Create your own
keystore, never commit a keystore or a password to GitHub. The build script
does NOT auto-generate a keystore, and does NOT contain a password.

Output is manual-apk/app-debug.apk. Rename it to talk-reel-ar-v0.7.0.apk
for a release. Verify it:

```
aapt2 dump badging app-debug.apk
apksigner verify --verbose app-debug.apk
```

Expect package com.talkreel.ar, versionCode 15, versionName 0.10.0, minSdk 26,
targetSdk 36, v2 and v3 enabled, 1 signer.

Media3-enabled build (required for digital music mixing and burned-in SRT captions):

```
gradle -p manual-apk --no-daemon assembleDebug
```

The resulting debug APK is a CI/test artifact, not a permanently signed production release. See docs/PRODUCT-READINESS.md for release-signing requirements.

## Backend

See backend/. Simple Node Express server, no database.
Endpoints: GET /, GET /api/trends, GET /api/templates, POST /api/viral-score
Run: cd backend, npm install, npm start, port 3000

For production, use Supabase Free, $0. See docs/backend-free.md.

## Money plan

Freemium plus subscription is the main model. Pro $9.99 CAD per month or
$59.99 per year, free tier with watermark, Pro removes watermark and unlocks
all filters, 4K export, AI hook writer. Plus $2.99 credit packs for 10 AI
videos, AR filter marketplace taking 30 percent, branded AR filters $200 to
$1,000 for a month, affiliate commission on reviewer product links, $4.99
one time remove ads and watermark, later a B2B version. Google Play takes
15 to 30 percent. No pricing decision has been locked in yet, and no payment
is wired in v0.7.0.

## Docs

- docs/FEATURES.md: full 20 viral features, v1, v2, and v3 groups.
- docs/MUSIC.md: full music track list, licenses, links, and other sources.
- docs/PRO.md: Pro tier, and what v0.6 fixed.
- docs/TEST-0.6.1.md: v0.6.1 crash audit, dexdump proof, emulator attempts,
  and the 4-step phone test.
- docs/TRUST.md: Play Protect, signing key, and why the phone warns.
- docs/PRODUCT-READINESS.md: current production gaps, completed integration work, and release gates.
- docs/backend-free.md: free backend hosting options.

## v0.9.0 — Snapchat-inspired AR preview effects

- Added selectable live-preview face effects: Guide, Glow, Glasses, Bunny, Crown, and Neon.
- Effects use the existing Camera2 face-detection rectangles and draw on the transparent preview layer.
- The selection persists between launches; Guide remains the default and restores the clean framing guide.
- Existing recording, captions, teleprompter, music, overlay, clips, and sharing paths are retained.

**Integration candidate:** this branch now routes Camera2 frames through an OpenGL ES compositor and renders the selected shader effect to both the Studio preview and MediaRecorder encoder surface. The compositor uses device-provided face rectangles and stylized overlays, not dense facial landmarks or segmentation. CI compilation and physical-device verification are still required; do not treat this as a released, device-validated feature yet.
## v0.10.0 — camera direction and face AR rebuild

- Camera direction fixed. Every frame is rotated from sensor orientation to
  upright for the current display, and the front camera is mirrored, inside the
  GPU compositor, for both the preview and the recorded MP4. Before this, the
  buffer was drawn sideways and the selfie view was not mirrored.
- Preview aspect fixed. The preview is a real 9:16 box and the camera image is
  cover-cropped in GL, so the preview, the AR overlay and the exported video
  share one composition. The old fixed 520 px box squashed the image.
- Face alignment fixed. Face, eye and mouth points are mapped from the sensor
  active array through the same rotation, mirror and crop as the video, so
  effects sit on the face instead of drifting off it. Capture uses a
  sensor-aspect size so those coordinates stay valid.
- Tracking upgrade: eye and mouth points give head roll and accurate eye
  placement, and positions are smoothed with a short hold so effects stay
  steady when the detector drops a frame.
- Effect upgrade: soft bloom, skin-masked beauty smoothing, gradient glasses
  with lens highlights, gold crown with jewels, animated neon rim,
  blush/lip/liner makeup, glitter masquerade mask, and cat/dog with ears,
  whiskers and muzzles. Edges are antialiased in display pixel space, so they
  are crisp at 720p and 1080p.
- Capture and export quality: explicit video bitrate, plus autofocus,
  stabilisation and a 30 fps target requested when the device supports them.
- Probable-bug fixes found in the review: overlay-only foreground service no
  longer passes a zero foreground type on Android 10 to 13, edge-to-edge
  insets are handled on Android 11 to 14, and a failed camera open no longer
  leaves Record stuck waiting for the camera.
