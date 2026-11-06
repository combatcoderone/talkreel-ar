# TalkReel test, v0.6.1, 2026-11-05 evening

## What the user reported
v0.6.0 APK is blinking, not working. Asked: what did you do exactly, test the APK as well.

## What I did in v0.6.0, and what I had only checked
v0.6.0 was built with the manual aapt2, d8, zipalign, apksigner flow.
Checked only: aapt2 dump badging (package, versionCode 7, versionName 0.6.0, minSdk 26, targetSdk 34),
apksigner verify (v2 and v3 true, 1 signer), and a grep that TalkReelService never sets isRecording.
That is a package check, not a run test. It cannot catch a launch crash.
That was the mistake. v0.6.0 was never run on Android.

## Root cause, proved from the compiled APK, not only from source
MainActivity.onCreate called registerReceiver(controlReceiver, filter) with the old 2-arg call.
Android 14, which v0.6.0 targets (targetSdk 34), requires RECEIVER_EXPORTED or
RECEIVER_NOT_EXPORTED when registering a receiver. The 2-arg call throws
SecurityException on Android 14, in onCreate, so the app opens and closes at once.
That matches blinking exactly. The same call was in v0.5 and v0.6.

Dex proof, from dexdump on the shipped APKs:
- v0.6.0: invoke-virtual MainActivity.registerReceiver:(BroadcastReceiver, IntentFilter) only, 2-arg.
- v0.6.1: invoke-virtual MainActivity.registerReceiver:(BroadcastReceiver, IntentFilter, int) 3-arg,
  plus the 2-arg fallback only in the else branch for SDK under 33, where it is allowed.
- v0.6.0 startForeground: only (int, Notification), 2-arg.
- v0.6.1 startForeground: (int, Notification, int) 3-arg with
  FOREGROUND_SERVICE_TYPE_CAMERA | FOREGROUND_SERVICE_TYPE_MICROPHONE,
  plus the 2-arg fallback for older Android.

## Fixes in v0.6.1, versionCode 8, versionName 0.6.1, 77K, signed v2 and v3
1. registerReceiver now uses Context.RECEIVER_NOT_EXPORTED on SDK 33 and newer.
   This receiver is only for our own overlay, so it must NOT be exported.
2. TalkReelService startForeground now passes camera and microphone type on SDK 29 and newer,
   matching the manifest. Android 14 requires a foreground service type.
3. Camera not shown: the camera was tried before the permission dialog finished, failed,
   and never retried. Now onRequestPermissionsResult retries openCamera after the user allows Camera.
   onResume also reopens the camera when it is missing for any reason, not only after Instagram.
   TextureView surface recreate now restarts the preview when the camera is already open,
   instead of returning early and leaving a black preview.
4. Record with no camera: startRecording now waits for the camera, up to 5 tries, 400ms apart,
   instead of starting MediaRecorder with no camera and showing REC with no video.

## Emulator test, attempted for real, and why it could not run here
I downloaded the real Android emulator (37.3.7), platform-tools, and Android 14 system images,
x86_64 and arm64, and created AVDs test34 and test34arm.
- x86_64: emulator log says "x86_64 emulation currently requires hardware acceleration!
  CPU acceleration status: KVM requires a CPU that supports vmx or svm"
  This VM has no /dev/kvm, and /proc/cpuinfo has no vmx or svm flags at all.
  So the x86_64 emulator cannot run on this machine, ever.
- arm64: emulator log says "FATAL Avd's CPU Architecture 'arm64' is not supported by the
  QEMU2 emulator on x86_64 host. System image must match the host architecture."
  The qemu-system-aarch64 binary in the emulator folder is the same emulator wrapper, not a
  standalone QEMU, so there is no direct QEMU path either.
So no emulator boot, no logcat run, was possible in this VM. I did not pretend otherwise.
The test that was possible here is the dexdump proof above, plus aapt2 badging and apksigner verify.
The phone test is still needed, by the user, and is the real test for camera, Record, and overlay.

## Package verification, v0.6.1
aapt2 dump badging: package com.talkreel.ar versionCode 8 versionName 0.6.1, sdkVersion 26, targetSdkVersion 34.
apksigner verify --verbose: v2 true, v3 true, v1 false, 1 signer.

## Phone test for the user, please tell me the result
1. Uninstall old TalkReel first, then install v0.6.1. Same release key as v0.3 to v0.6, so an update should work, but clean install is safest.
2. Open the app. It should stay open, not blink, and the camera should show once.
3. Tap Record, wait 5 seconds, tap Stop. A clip should save in Movies/TalkReelAR.
4. Tap Overlay, then Instagram. The teleprompter should float, no crash.
Tell me which step fails, if any, and what the phone shows. That log is what I need to fix the next one.
