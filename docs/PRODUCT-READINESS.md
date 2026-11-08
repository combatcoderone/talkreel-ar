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
- Replaced ML Kit face rectangles/contours with MediaPipe Face Landmarker: 478 3D landmarks, 52 ARKit blendshape coefficients, 4x4 facial pose matrices and up to three simultaneous faces. The model is downloaded during the Gradle build and packaged in the APK for offline inference.

## Release blockers — not complete

| Area | Status | Required work before a real release |
|---|---|---|
| Face tracking and effects | **Implemented foundation; advanced rendering and device QA remain** | MediaPipe Face Landmarker analyzes the Camera2 YUV stream off the camera/GL thread, returning 478 xyz landmarks, 52 blendshape scores and pose matrices for up to three faces. A smoothed result feeds the shared EGL renderer. Thirty face-anchored looks (makeup, Halloween, glasses, crown, jewelry and accessories) are rendered to both preview and encoder surfaces, so the live effect is recorded in the MP4. This is not yet a full commercial lens engine: true face-part segmentation, depth-based occlusion, rigged 3D assets and production-quality physically based makeup are still outstanding. Test orientation, thermal load, FPS, latency and exported output on physical devices. |
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

1. The MediaPipe landmark-driven renderer is integrated into the shared camera/encoder EGL path, and physical-device tests prove that effects remain aligned and appear in the saved MP4 across front/rear cameras, head motion and recording lifecycle. CI compilation alone does not satisfy this gate.
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


## Face-tracked AR renderer milestone (v0.9.6 candidate)

- MediaPipe Tasks Vision 1.1.1 is used with Face Landmarker in VIDEO mode. Input frames are converted from Camera2 YUV_420_888 to upright RGB bitmaps on a dedicated worker thread, with stale frames dropped while inference is busy.
- Up to three faces are tracked. Each result carries 478 xyz landmarks, the full set of 52 ARKit blendshape coefficients and a 4x4 canonical-to-observed pose matrix. Landmark smoothing reduces frame-to-frame jitter.
- The GPU compositor now renders face-anchored makeup, Halloween and accessory geometry after the camera texture pass. The exact same draw pass is invoked for both preview and encoder EGL surfaces; therefore selected effects are included in the camera recording rather than being UI-only.
- The 30-look catalog includes ten makeup styles, ten Halloween styles and ten accessory/creative styles.
- The model is downloaded by a Gradle preBuild task and packaged into the APK, so no first-run model download is required on the device.
- Current renderer is a 2D overlay renderer driven by 3D face landmarks and expression scores. It is not yet a fully rigged 3D asset pipeline; no dense face-part segmentation or depth/occlusion compositing is implemented. Those remain explicit release blockers for claims of Snapchat-equivalent quality.
- The MediaPipe frame conversion and 3-face inference path needs physical-device performance validation. Build success alone does not establish stable FPS, thermal behavior, accurate camera rotation/mirroring or effect alignment.


## Multi-face tracking stabilization follow-up

- Face detections are now re-associated between frames by nearest face-center matching before temporal smoothing. This avoids blending the previous frame's landmark history into a different person when MediaPipe changes the ordering of up to three simultaneous detections.
- This is a tracking-stability improvement, not persistent biometric identity tracking. A face that disappears and re-enters the frame receives a new smoothing history.
- The renderer remains a 2D GPU overlay system. The 3D landmark z-coordinate and pose matrices are retained in the frame payload but are not yet a complete 3D asset/occlusion pipeline.


## AR tracking hardening follow-up

- Multi-face temporal smoothing now associates each incoming detection with the nearest prior face center before blending landmark history. This prevents detector result reordering from mixing two people's landmark meshes.
- The association logic is isolated in a pure-Java helper and has JVM tests for reordered detections, unmatched/new faces and center calculation. Android CI now runs `testDebugUnitTest` before packaging.
- Face-anchored cheek accents, cat/bunny ears and halo dimensions scale with measured inter-temple distance, reducing screen-fixed sizing as the subject moves.
- The camera coach/status line reports per-frame inference time in milliseconds to make tracking latency measurable during device QA.
- These changes improve stability and diagnostics. They do not add rigged 3D assets, face segmentation, depth occlusion or physical-device certification.
- Selected looks now use smile and blink blendshape scores for subtle cheek and eyelid accents; jaw-open scores continue to drive Halloween fang/teeth length.