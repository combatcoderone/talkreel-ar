# TalkReel AR

Talk, record, and post. AR teleprompter app for Reels, Shorts, and TikTok.

Current development version: 0.11.0 (ARCore feature branch)
Package: com.talkreel.ar
Repo: combatcoderone/talkreel-ar
Min SDK: 26 (Android 8.0), Target SDK: 36 (Android 16)
Build: Gradle Android application module, with source retained in manual-apk/src
ARCore: com.google.ar:core:1.57.0
Status: development candidate; not yet device-validated or Play-ready

## What it is

TalkReel AR is for a person who reviews a product, talks to camera, and
posts to Instagram, YouTube, or TikTok. Open the app, paste a script, press
Record, talk, press Stop, share the clip. The teleprompter scrolls while you
talk, so you do not have to memorise the script. A floating teleprompter can
also stay on top while Instagram or YouTube records.

## What is shipped

The maintained Android source is plain Java in `manual-apk/src/`. The
`:app` Gradle module compiles that source and includes ARCore. The manual build script invokes the same Gradle debug and lint tasks
and expects JDK 17, Gradle 8.13, and Android SDK 36. Release signing is not
configured in this development branch. `app/src/` remains a historical Kotlin prototype and is not used
by the current build.

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
- Download: saves the MP3 locally, in Music/TalkReelAR on the phone, about
  2 to 5 MB per track. Nothing is downloaded unless you tap Download.
  Nothing is bundled in the APK, so the app stays small.
- Use: picks this track for the next clip. The Music: On button at the top
  turns music in a clip on or off. When it is on, the selected track plays
  on loop while you record, and stops when you tap Stop. In the overlay,
  the Music button plays or stops the picked track.

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

Honest limit: music plays through the phone speaker while you record, so
the microphone hears it. It is background music in the room, not mixed into
the video file by an encoder. Mixing it into the file needs a video encoder
step, planned after v0.7. If you want clean music in the exported clip, add
the downloaded MP3 in CapCut, Instagram Edits, or YouTube Create after you
export from TalkReel. The downloaded file is already on your phone.

### Captions and language

- Live on-screen captions, on-device first, online fallback.
- Auto language detection: English, Persian, Arabic, French. RTL display
  for Arabic script. Language button cycles Auto, English, Persian, Arabic,
  French.
- Persian and Arabic captions depend on the phone's Google speech service,
  so they need internet the first time if the offline model is not installed.
- Captions are NOT burned into the video yet. Pro export writes an .srt
  file beside the clip, and a -cuts.txt silence list. See Pro below.

### Framing, Pro, and Script Coach

- Basic AR filters are removed, on purpose, in v0.6. No Beauty, Cartoon,
  Animal, Glasses, Glow, or Circle filter. Framing is a clean Pro guide
  only: thin white guide circle, rule of thirds, orange face box, no dim
  or tint. See docs/PRO.md.
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

## What v0.7.0 does NOT do yet

- Captions are not burned into the video, SRT file only.
- Music is not mixed into the video file, it plays through the speaker
  while recording. See Music above.
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
7. Find clips in Movies/TalkReelAR, music in Music/TalkReelAR.

If Play Protect warns, tap Details, then Install anyway, or turn off Scan
apps in Play Protect during install and turn it back on. That warning is
normal for a sideloaded app. Only a Play Store release removes it fully.
See docs/TRUST.md.

## Build the APK

Manual build, used for every release, no Gradle memory problem:

```
cd manual-apk
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
export TALKREEL_KEYSTORE_PASS=your-keystore-password
bash build.sh
```

It uses aapt2, d8, zipalign, and apksigner from Android SDK 34, with JDK 17.
It needs a release keystore at manual-apk/release.keystore. Create your own
keystore, never commit a keystore or a password to GitHub. The build script
does NOT auto-generate a keystore, and does NOT contain a password.

Output is manual-apk/app-debug.apk. Rename it to talk-reel-ar-v0.7.0.apk
for a release. Verify it:

```
aapt2 dump badging app-debug.apk
apksigner verify --verbose app-debug.apk
```

Expect package com.talkreel.ar, versionCode 9, versionName 0.7.0, minSdk 26,
targetSdk 34, v2 and v3 true, v1 false, 1 signer.

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
- docs/backend-free.md: free backend hosting options.

## v0.9.0 — Snapchat-inspired AR preview effects

- Added selectable live-preview face effects: Guide, Glow, Glasses, Bunny, Crown, and Neon.
- Effects use the existing Camera2 face-detection rectangles and draw on the transparent preview layer.
- The selection persists between launches; Guide remains the default and restores the clean framing guide.
- Existing recording, captions, teleprompter, music, overlay, clips, and sharing paths are retained.

**Current limitation:** these are live preview overlays only. The existing MediaRecorder records the camera stream directly, so the AR artwork is not composited into the saved MP4. Embedding effects in exports requires a separate video-compositing/encoder pipeline; this update deliberately avoids changing that stable path.

## Current 0.11.0 development status

The ARCore branch adds a dedicated front-camera Augmented Faces studio,
live 468-point face mesh rendering, selectable preview treatments, and live
tracking guidance. It also improves recording startup validation, asks for
microphone permission only when the user starts recording, stops/cancels
recordings safely when the app leaves the foreground, and reports recording
failures rather than silently claiming that recording started.

Media storage now uses app-specific directories to comply with Android's
scoped-storage model. On Android 10 and newer, completed clips are also
published to `Movies/TalkReelAR` through MediaStore. Existing files in the
legacy public folder remain discoverable when accessible. New music downloads
are stored in app-specific storage; the former public Music folder is still
checked for existing tracks. Downloads are staged and validated before they
replace a saved track.

The Clips control now opens a selectable clip list. The Pro switch is
explicitly labeled a preview unlock; it is not a payment system.

### Release-blocking limitations

- AR Studio is a live preview only. The current camera/MediaRecorder pipeline
  does not render ARCore effects into the saved MP4.
- The face treatments are mesh visualizations, not production face assets
  such as glasses, makeup, masks, or 3D props.
- Music is still played through the phone speaker and is not mixed into the
  recorded audio track.
- Captions are still live preview plus optional SRT; they are not burned into
  the exported MP4.
- Pro purchases, subscription validation, restore purchases, and entitlement
  checks are not implemented.
- Device validation is required on the Galaxy S23 FE, including camera
  orientation, front-camera mirroring, thermal behavior, sustained frame
  rate, and recording reliability.

See `docs/RELEASE-0.11.0.md` for the acceptance checklist and remaining work.
