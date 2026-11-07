# Main settings, templates, recording, and export workflow

This document defines the product and implementation contract for the next TalkReel AR release. The shipped Android app is the Java implementation in `manual-apk/`; changes must be made there rather than in the archived Kotlin prototype under `app/`.

## Product flow

1. **Set up** the shot on the main screen. The front-camera preview is live as soon as camera permission is granted; previewing must never create a file or start audio recording.
2. **Save as template** to preserve the complete setup.
3. **Live** starts an actual TalkReel-owned recording. During recording, the teleprompter scrolls and recording controls become Pause/Resume and Stop.
4. **Review** opens the completed clip in an in-app playback screen. The user can replay it, discard it, or export/save it.
5. **Export** writes the final MP4 to Android shared storage and reports the destination. Use MediaStore on Android 10+ and the appropriate legacy path/permission behavior on older supported versions.

## Main screen: one coherent Settings area

Replace scattered cycling controls and ambiguous abbreviations with labeled, grouped settings. Keep the camera preview visible while editing.

### Template settings

A template is a named, editable snapshot containing:

- AR / makeup effect selection (including None / Guide and each supported effect).
- Music selection, music enabled state, and selected track.
- Teleprompter script text.
- Teleprompter text size and scroll speed.
- Selfie-frame shape: Rectangle, Circle, Heart, and Oval. Show a small live frame preview and preserve the selected shape.
- Camera choice (front/rear), caption visibility, and caption/text styling where supported.

Provide **Save template**, **Update template**, **Apply template**, **Duplicate**, and **Delete** actions. Applying a template updates the controls and preview immediately; it must not start recording. Persist templates locally (SharedPreferences JSON is acceptable for a small collection; use a versioned schema so future fields can be added). Preserve a built-in Default template and never overwrite it implicitly.

### Teleprompter editor

- Use the clear label **Teleprompter text** rather than “script” in user-facing controls.
- The user can edit the text, adjust text size, and adjust scroll speed independently.
- Preview the teleprompter overlay while idle; scrolling is manual/preview-only until Live is pressed.
- During recording, scroll automatically; Pause freezes both recording and scroll position; Resume continues from the same position.
- Persist the latest text independently of templates so unsaved edits are not lost.

### Camera preview and frame shapes

- Keep the front-camera face preview active while the user configures settings.
- Draw the selected frame shape over the preview only; it is a composition guide/mask, not a camera switch and not a recording state.
- Circle and oval use clipped/rounded geometry; Heart uses a path; Rectangle uses a rounded rectangle. Keep face positioning and crop behavior consistent across shapes.
- Clearly label preview state **Preview — not recording**. Do not show a recording timer or red REC indicator in this state.

## Recording semantics

- The primary action is labeled **Live** while idle and **Stop** while recording (or retain a clearly equivalent Record/Stop label consistently). Its meaning is unambiguous: tapping Live starts video and audio capture.
- Merely opening the app, showing the camera preview, changing AR effects, applying a template, or opening the overlay must never start recording.
- Show a persistent, high-contrast **REC** indicator and elapsed time only after the recorder confirms that recording has started.
- Handle camera/microphone permission denial and camera-not-ready states without pretending that recording started.
- Stop finalizes the file before enabling Review. A failed finalization must show an actionable error and must not present an incomplete file as ready.

## Review and export

After a successful Stop, open a **Review video** screen with:
- Video playback, play/pause, and seek.
- Clip duration and file size.
- **Retake**, **Discard**, and **Export to phone** actions.
- Export confirmation with the actual saved destination (for example, Movies/TalkReelAR) and a Share action.

Use Android MediaStore and a content URI for export and playback. Avoid raw filesystem paths as share URIs. Prevent duplicate exports on repeated taps, show progress while saving, and report storage failures. Keep the original recording until the user explicitly discards it.

## UI copy cleanup

Use these exact, plain-language labels consistently:
- **Live** — start recording.
- **Preview — not recording** — idle camera state.
- **Pause**, **Resume**, **Stop** — recording controls.
- **AR effect**, **Music**, **Teleprompter text**, **Teleprompter size**, **Scroll speed**, **Selfie frame**, **Template**.
- **Review video**, **Retake**, **Discard**, **Export to phone**.

Remove cryptic abbreviations such as “BG”, “AR:” where the control can have a full label, and status strings that combine several unrelated settings into one line. Use section headings, helper text, consistent spacing, and accessible touch targets. Keep primary actions visually distinct from secondary configuration actions.

## Additional improvements

- Add an unsaved-changes indicator to the template editor and confirm before discarding edits.
- Add a 3-second optional countdown after Live is pressed, with Cancel, so users can position themselves before capture.
- Add a configurable teleprompter text-size control and a center reading guide; do not couple text size to scroll speed.
- Add a “Reset to template” action to undo accidental setting changes.
- Add an audio preview/test and explicit music on/off state; make clear whether music is actually mixed into the MP4. Do not imply that speaker playback is mixed into the recording.
- Make the recording screen distraction-free: collapse configuration controls and retain only teleprompter, timer, REC state, Pause/Resume, and Stop.
- Add accessibility content descriptions for icon-only controls and minimum 48dp touch targets.

## Acceptance checks

- App launch displays a working front-camera preview with no recording file, no microphone capture, and no REC state.
- Tapping Live creates exactly one recording; tapping it again / Stop finalizes exactly one clip.
- Applying or saving a template never starts capture.
- A saved template restores AR effect, music, teleprompter text, text size, speed, and frame shape after app restart.
- Rectangle, Circle, Heart, and Oval can be selected and are visible in idle preview and recording preview.
- Teleprompter text can be changed without changing its size or speed; each setting can be changed independently.
- Pause/Resume synchronizes video capture and teleprompter scrolling.
- Stop leads to Review only after successful finalization.
- Export creates a playable MP4 in Movies/TalkReelAR on the local Android phone and uses a valid content URI for playback/sharing.
- All primary controls have clear labels; no clipped, overlapping, or ambiguous overlay text at common phone widths and large font settings.

## Platform constraint: floating overlay

The floating Overlay Companion cannot show TalkReel's live camera feed on top of Instagram/YouTube/TikTok while those apps own the camera. Android camera ownership is exclusive in this architecture. The face preview and selfie-frame selection described above therefore belong to TalkReel's main screen / Studio preview. The floating companion remains a teleprompter and controls companion; it must not claim that its face window is a live camera feed or that its Live button controls another app's recording.
