# Finished-video export (v0.8.0 work in progress)

TalkReel keeps the existing Camera2 + MediaRecorder capture path. Media3 Transformer is a separate post-recording stage; it does not replace camera capture.

## Compatibility policy

- Minimum SDK remains 26 (Android 8.0).
- Capture remains available if Media3 export cannot run.
- Export checks for an H.264 encoder before starting.
- The original recording is retained. Export writes to a temporary MP4 and only renames it to `*-final.mp4` after Transformer reports success.
- Export failures leave the original clip untouched.
- The existing `build.sh` manual APK path remains available and excludes the optional Media3 class. It continues to produce a record-only build. Use Gradle to include Media3 export.

## Gradle build

Requires JDK 17, Android SDK Platform 35, Android build-tools, and Gradle 8.13 or newer:

```sh
cd manual-apk
gradle assembleDebug
```

## Export behavior

When a recording has captions and/or a downloaded selected music track, the Gradle build starts a Media3 export after the recording is closed:

- SRT caption timings are rendered as a timed text overlay.
- The selected local music track is added as a looping background-audio sequence, mixed with the recorded microphone audio.
- The source recording remains in Movies/TalkReelAR; the completed file is saved beside it with `-final.mp4`.

Music is no longer played through the phone speaker during capture, preventing speaker music from leaking into the microphone track. A selected track must be downloaded before recording to be included in the final export.

## Device testing still required

A successful source change or Gradle compile is not proof of device compatibility. Test at minimum:
- Android 8 / API 26 lower-end device: capture-only and export.
- Android 10 / API 29 mid-range device: captions + music export.
- Android 14 / API 34 device: full capture, pause/resume, and export.
- A device without a supported H.264 encoder (or with insufficient free storage): graceful export failure while the source recording remains playable.

Do not ship a release until these physical-device checks and a clean Gradle build pass.
