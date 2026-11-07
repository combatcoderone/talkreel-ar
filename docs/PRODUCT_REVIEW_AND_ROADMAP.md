# Product review and paid-product roadmap

## Product thesis

TalkReel should be a creator workflow, not a collection of camera toys. The paid job is:

> Help a solo creator record a publish-ready vertical talking-head video, with a readable script, stable framing, tasteful face treatment, captions, and a finished export, without editing on a desktop.

The product wins when a creator can repeatedly make a good clip faster and with fewer failed takes. The number of filters, buttons, or social-app shortcuts is not the value proposition.

## Current product verdict

**Promising prototype; not yet release-ready as a paid product.**

The codebase has useful product foundations: a teleprompter, camera recording, script controls, caption capture, saved clips, sharing, PiP/floating preview, music selection, and an ARCore face-studio path. The GPU compositor is now wired into the TalkReel camera recording path so the same rendered frame is sent to preview and the MediaRecorder video input.

The following are still release blockers or material limitations:

- The integrated compositor currently applies GPU color treatments and approximate face-rectangle effects. It does not yet composite the ARCore 468-point face mesh into the main TalkReel recording path.
- The soundtrack is still played through the device speaker during recording and may be picked up acoustically by the microphone. It is **not** a clean digital music mix. Do not market this as soundtrack mixing.
- Camera face rectangles are not dense landmark tracking. Do not market rectangle-attached effects as full AR face tracking.
- The compositor has not been validated on physical devices for orientation, front-camera mirroring, aspect ratio, dropped frames, thermal behavior, and lifecycle transitions.
- The app's Pro switch is a local development/test flag. It does not process payment, verify a purchase, or enforce server-backed entitlements.
- No production billing, account/subscription restore, privacy policy review, or release signing flow is claimed by this branch.

## Product experience to optimize

### First session

1. Ask for camera and microphone permission only when the user enters the relevant workflow.
2. Land on a clear recording workspace: live preview, script, scroll speed, framing, record, pause, stop.
3. Show one obvious recommended path: choose or paste a script, position the face, optionally choose a look, record.
4. After recording, show a clip review screen with playback, duration, retake, export, and share.
5. Make export status explicit. Never imply that a preview-only effect or speaker-played music is embedded in the MP4.

### Repeat session

- Restore the last script, scroll speed, framing, effect, caption language, and audio choice.
- Keep the recording path one tap away.
- Make previous clips easy to find and reuse.
- Preserve originals when an optional post-production export fails.

### Reliability behaviors

- Prevent double-start and double-stop races.
- Keep camera, EGL, encoder, and recorder lifecycle operations on one camera/render thread.
- Handle permission denial, camera contention, surface recreation, backgrounding, low storage, encoder failure, and very short recordings with actionable messages.
- Do not publish/share a file until finalization and validation succeed.
- Use temporary output names for post-processing; replace the final asset only after successful validation.

## Monetization recommendation

Do not charge for access to a filter carousel. Charge for a repeatable creator workflow and finished deliverables.

Suggested entitlement design (not yet implemented):

| Free | Creator / paid |
|---|---|
| Core teleprompter and camera recording | Everything in Free |
| Basic framing and a small starter set of looks | Full effect and makeup/face-studio library |
| Local clip storage and standard export | Clean export controls and higher-quality export presets, once validated |
| Limited caption workflow | Burned-in caption styles, SRT export, reusable caption presets |
| Basic music preview | Digital soundtrack mixing with independent voice/music gain, fade and ducking controls |
| No watermark only if economically sustainable | No watermark, batch/repeatable presets and saved creator setups |

The table is a product proposal, not a statement of current app behavior. Before shipping paid entitlements:
- implement Google Play Billing with server-side purchase validation or a trusted entitlement service;
- support restore purchases and subscription state changes;
- gate paid capabilities from verified entitlements, not a local boolean;
- make pricing, renewal, trial, cancellation, and refund terms visible;
- keep the free tier genuinely useful and avoid locking users out of their own recordings.

Avoid subscriptions until retention supports them. A one-time Creator unlock or a low-cost monthly plan can be tested after product-market evidence exists. Do not set a price before measuring creator willingness to pay and ongoing licensing/compute costs.

## What to build next, in order

### P0 — make the exported clip truthful and dependable

- GPU camera-to-encoder compositor (integrated in this branch; validation remains).
- Add automated export inspection: MP4 parses, video/audio tracks exist, duration and dimensions are sane, timestamps are monotonic, and the rendered effect is visible in decoded output frames.
- Fix and test preview/record aspect ratio, sensor rotation, front-camera mirroring, and crop behavior.
- Add failure cleanup and ensure incomplete clips are never shown as saved.

### P1 — digital audio mix

- Replace speaker playback as the soundtrack mechanism.
- Retain microphone audio as the primary voice track.
- Decode the selected licensed/local music source and mix digitally with a conservative default gain, independent voice/music gain, optional fade-in/fade-out, and voice ducking.
- Loop or trim the music to the final video duration; do not extend the clip because the song is longer.
- Preserve the original recording if mix/export fails.
- Validate audio/video synchronization, clipping, loudness, and all supported input formats.
- Keep music licensing metadata and attribution with the selected catalog item. User-imported files remain the user's responsibility.

### P2 — connect ARCore face mesh to the recording renderer

- Feed ARCore face mesh vertices/UVs/pose into the same render graph that produces the encoder frames.
- Keep ARCore session camera ownership and Camera2 ownership mutually exclusive; do not open both against the same physical camera.
- Build a renderer adapter so face effects are not tied to the demo ARCore Activity.
- Start with one or two high-quality, stable effects; add more only after tracking loss/reacquisition and occlusion tests pass.

### P3 — paid-product foundations

- Clip review and export progress UI.
- Google Play Billing and verified entitlements.
- Privacy/permission disclosures, data deletion/export controls, music license UX.
- Crash/ANR and export failure telemetry with privacy-preserving diagnostics.
- Device support matrix and release checklist.

## Success metrics

Measure:
- first successful recording rate;
- first export completion rate;
- time from launch to first saved clip;
- recording failure and export failure rate;
- repeat recording sessions per active creator;
- share/export completion rate;
- retention after first successful clip;
- free-to-paid conversion and refund/cancellation reasons.

Do not optimize downloads or filter taps as primary success metrics. The core activation event is a creator successfully exporting a video they are willing to publish.

## Release gate

This is not a paid release until the P0 export tests and physical-device matrix pass, digital audio mixing is either complete or clearly excluded from the paid promise, ARCore effects are accurately labelled, and billing/entitlements are real. A successful Gradle build alone is not release acceptance.
