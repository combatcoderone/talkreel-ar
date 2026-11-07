# Product readiness audit — 2026-11-07

This audit describes the integration branch `integration/product-readiness-media-export`.
It is not a claim that the app is ready for public distribution.

## Completed in this branch

- Added a Gradle Android build alongside the existing dependency-free manual build.
- Added Media3 Transformer export to the Gradle build.
- Routed the selected, downloaded music track into the final MP4 as a separate audio sequence under the microphone recording. Music is mixed at a conservative 20% gain and loops to the clip duration.
- Removed speaker playback of the selected music during TalkReel-owned recording. This avoids recording the music twice (once acoustically through the microphone and once as the digital music track).
- Added SRT caption overlay processing to the finished-video export path when a Pro caption file exists.
- Kept the original recording intact if final export fails; the original is used for review as a fallback.
- Added a CI job that compiles the Media3-enabled Android source and uploads a debug APK artifact.
- Kept the standalone manual APK build working without Media3 dependencies. That fallback build does not provide digital music mixing or burned-in captions; use the Gradle build for those features.

## Release blockers — not complete

| Area | Status | Required work before a real release |
|---|---|---|
| Recorded AR effects | **Integrated in this branch; device proof pending** | The settings/review flow now uses the GPU compositor for both preview and encoder surfaces. CI compilation and physical-device proof are still required. The compositor uses Camera2 face rectangles and stylized shader overlays; it is not dense landmark tracking or segmentation. |
| Media export integration | **Build validation pending** | The Media3 path is wired here, but the new Gradle CI job must pass. Test music looping, audio gain, source audio preservation, caption timing, orientation, pause/resume and export failure on physical devices. |
| Camera and recording | **Device validation pending** | Test front/rear cameras, permission denial/recovery, camera contention with social apps, app background/foreground, rotation, repeated record cycles, pause/resume and low-storage handling on supported Android versions. |
| Audio quality and synchronization | **Device validation pending** | Listen to exported files on device; verify microphone/music balance, clipping, silence, A/V sync, Bluetooth routing, interruptions and long clips. |
| Subscription and entitlements | **Not implemented** | Replace the local Pro/test toggle with Google Play Billing, server-side purchase validation, entitlement restoration, cancellation/expiry handling and clear paywall terms. Do not charge users until this is implemented and tested. |
| Permanent app signing | **Not release-safe** | Configure an owner-controlled, backed-up release keystore in protected CI secrets. Current CI signing keys are temporary and cannot support reliable in-place app updates. Never commit the keystore or passwords. |
| Privacy and data governance | **Not release-ready** | Publish a privacy policy and terms; document microphone/camera use, speech recognition/network behavior, music downloads, retention, deletion and any backend processing. Complete the Play Data safety declaration. |
| Music rights | **Needs legal/product review** | Keep the license and attribution record for every track, verify commercial-use and redistribution restrictions, and provide required attribution in the exported/share flow for CC BY tracks. A track's availability on a free-music site is not a blanket license for every use. |
| Automated Android tests | **Insufficient** | Add unit tests for settings/template persistence, SRT parsing/timing, export fallback, media naming and file handling, plus instrumentation tests for camera lifecycle and MediaStore export. CI compilation alone is not functional validation. |
| Accessibility and UX | **Not verified** | Test TalkBack, touch target sizes, contrast, font scaling, landscape/large-screen behavior, error recovery and permission explanations. |
| Store/distribution readiness | **Not complete** | Both build paths target Android 16 / API 36. The overlay now declares a `specialUse` foreground-service subtype; complete the Play Console foreground-service declaration and provide the required demo evidence. Also finish app listing/assets, content rating, support contact, crash reporting, staged rollout and rollback plan. |
| Overlay limitations | **Platform constraint** | The floating overlay cannot show TalkReel's live camera while Instagram/YouTube/TikTok owns the camera. Keep the live preview in TalkReel Studio and describe the overlay as a teleprompter companion only. |

## Known technical boundaries

- The Media3 export path is an offline post-recording transformation. It does not mix the audio live while recording.
- The selected music file must be downloaded before recording. A missing file prevents recording when music is enabled rather than silently recording an empty music track.
- The standalone `manual-apk/build.sh` intentionally excludes `Media3ExportPipeline.java`; it remains a dependency-free fallback and does not offer digital music mix/caption burn-in. The AR compositor is included in both build paths.
- The floating overlay cannot take ownership of another app's camera or control its recording session. Its foreground service uses the Android `specialUse` type only while the user-requested overlay is visible; recording uses camera/microphone types.
- The existing Pro flag is a local testing switch, not a purchase entitlement.
- No physical-device validation is represented by a successful GitHub Actions build.

## Required release gate

Do not label this app production-ready until all of the following are true:

1. The AR compositor and settings/review workflow compile together, and physical-device tests prove selected effects appear in the saved MP4.
2. The Media3 build passes CI and the resulting MP4 is inspected on physical devices.
3. The device test matrix covers at least one current Pixel-class device and one Samsung device, plus the oldest Android API level the product supports.
4. Billing, permanent signing, privacy/legal review and music-rights review are complete.
5. Automated tests and a documented manual QA run pass.
6. A release candidate is signed with the permanent release key and an update from the previous public version is verified.

A successful compile is a necessary gate, not a substitute for these checks.
