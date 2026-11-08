# Product readiness audit — 2026-11-07

This audit describes the MVP and subsequent corrective work on camera orientation and visual treatment.
It is not a claim that the app is ready for public distribution.

## Completed in this branch

- Standardized the Android build on Gradle because the real-time face detector and Media3 now require external Android dependencies.
- Added Media3 Transformer export to the Gradle build.
- Routed the selected, downloaded music track into the final MP4 as a separate audio sequence under the microphone recording. Music is mixed at a conservative 20% gain and loops to the clip duration.
- Removed speaker playback of the selected music during TalkReel-owned recording. This avoids recording the music twice (once acoustically through the microphone and once as the digital music track).
- Added SRT caption overlay processing to the finished-video export path when a Pro caption file exists.
- Kept the original recording intact if final export fails; the original is used for review as a fallback.
- Added a CI job that compiles the Media3-enabled Android source and uploads a debug APK artifact.
- Targeted Android 16 / API 36 in both build paths and added edge-to-edge system-bar insets for Android 15+.
- Separated overlay and recording foreground-service modes: the user-requested overlay uses the declared special-use type, while camera/microphone types are activated only for recording.
- Added bundled ML Kit face contour detection to the Camera2 YUV analysis stream. The bundled model avoids a first-use model download.

## Release blockers — not complete

| Area | Status | Required work before a real release |
|---|---|---|
| Face tracking and effects | **Partially implemented; not release-ready** | ML Kit contour mode analyzes a Camera2 YUV stream and returns facial contours for eyes, lips and face shape. The preview renderer now has 20 contour-driven makeup/Halloween looks. Contour mode targets the most prominent face. These effects currently render in the live preview only; they are not yet composited into recorded/exported video. No 3D mesh, depth occlusion, expression blendshapes, multi-face effects or segmentation-based foundation is implemented. Device QA and encoder integration remain mandatory. |
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
- `manual-apk/build.sh` now delegates to Gradle. The previous dependency-free javac/d8 path is no longer supported because ML Kit and Media3 dependencies are required.
- The floating overlay cannot take ownership of another app's camera or control its recording session. Its foreground service uses the Android `specialUse` type only while the user-requested overlay is visible; recording uses camera/microphone types.
- The existing Pro flag is a local testing switch, not a purchase entitlement.
- No physical-device validation is represented by a successful GitHub Actions build.

## Required release gate

Do not label this app production-ready until all of the following are true:

1. The face-contour renderer is integrated into the shared camera/encoder render path, and physical-device tests prove all selected effects appear in the saved MP4. The current branch only renders contour effects in the live preview.
2. The Media3 build passes CI and the resulting MP4 is inspected on physical devices.
3. The device test matrix covers at least one current Pixel-class device and one Samsung device, plus the oldest Android API level the product supports.
4. Billing, permanent signing, privacy/legal review and music-rights review are complete.
5. Automated tests and a documented manual QA run pass.
6. A release candidate is signed with the permanent release key and an update from the previous public version is verified.

A successful compile is a necessary gate, not a substitute for these checks.


## Release-candidate pipeline (prepared; owner setup required)

A manual-only workflow is available at `.github/workflows/release-candidate.yml`. It builds and verifies a signed Android App Bundle (AAB) for owner review. It does **not** upload to Google Play or publish a release.

Before running it, the repository owner must create these GitHub Actions secrets from the permanent, backed-up upload keystore:

- `TALKREEL_RELEASE_KEYSTORE_BASE64`: base64-encoded JKS/PKCS12 keystore bytes
- `TALKREEL_RELEASE_STORE_PASSWORD`
- `TALKREEL_RELEASE_KEY_ALIAS`
- `TALKREEL_RELEASE_KEY_PASSWORD`

Do not use the temporary CI key generated by `.github/workflows/android-build.yml`. Do not commit the keystore or passwords. The owner must verify the upload key fingerprint and keep an offline backup before creating these secrets. The manual workflow requires an explicit boolean confirmation and only uploads the resulting AAB as a short-lived GitHub Actions artifact. It deliberately has no Play publishing credentials or deployment step.

The AAB is still a release *candidate*, not a release: physical-device acceptance, privacy and music-rights sign-off, Play Console declarations, store listing, billing decision/implementation and staged rollout approval remain required. The current app version is an integration candidate; increment versionCode and set the approved release version before the first store submission.


## Corrective work after tester feedback

- Treat upside-down or incorrectly rotated camera output as a release-blocking defect. The corrective branch `fix/camera-orientation-and-premium-look` calculates orientation from Camera2 sensor orientation, lens facing and display rotation, then applies the transform in the shared GPU path used by preview and recording.
- Removed novelty face-sticker effects (bunny ears, crowns, cartoon masks, etc.). The selector now offers restrained color looks: Natural, Warm, Cool, Cinematic, Soft and Monochrome.
- Removed the face rectangle overlay from the preview. The face detector currently provides framing feedback only; this is not landmark-based AR.
- These code changes require successful CI and physical-device validation across front/rear cameras and saved-video playback before they can be called fixed.


## Face tracking and creator UI milestone (v0.9.5 candidate)

- Added bundled ML Kit face detection with contour mode to the Camera2 YUV analysis stream. Frames are throttled: only one inference is processed at a time and stale frames are dropped.
- Uses eye, lip and face contours plus cheek landmarks to anchor preview makeup. The collection contains 20 named looks: Natural Makeup, Soft Glam, Smoky Eye, Rose Gold, Bronze Glow, Editorial Red, Nude Gloss, Berry Lip, Copper Shine, Bridal Glow, Halloween Skull, Vampire, Pumpkin, Zombie, Witch, Ghost, Demon, Spider Queen, Porcelain Doll and Neon Phantom.
- The Halloween set uses contour-anchored 2D makeup accents. It is not a library of 3D animated lenses, and should not be described as Snapchat-equivalent.
- The overlay was redesigned as a narrower, opaque, collapsible teleprompter panel with a dedicated drag handle and simplified controls.
- The ML Kit contours are currently preview-only. The encoder still receives the GPU camera compositor and basic color grade; the contour makeup is not burned into the saved recording. This is a hard release blocker for a camera-effects product.
- ML Kit contour mode provides detailed contours for the prominent face, not MediaPipe's 478-point 3D face mesh or 52 expression blendshapes. Those capabilities remain future work.
- The manual build script now invokes Gradle. A dependency-free javac build is not compatible with the bundled ML Kit dependency.
