# TalkReel Unity AR Creator Module

This directory is a Unity project scaffold for advanced creator effects. It is intentionally separate from `manual-apk/`, which remains the shipped native Android app.

## Architecture

- Unity 2022.3 LTS
- AR Foundation 5.1.5
- ARCore XR Plugin 5.1.5 for Android face tracking
- Unity scene/prefabs for 3D accessories, masks, makeup materials, particles and animation
- A future Android bridge will launch the Unity experience from TalkReel and return the rendered recording/output to the native app.

The initial controller listens to AR Foundation face tracking and attaches a selected effect prefab to the tracked face transform. Add effect-specific anchors and calibration in prefabs; a single generic offset is only a prototype.

## Important technical distinction

ARCore Augmented Faces supplies a face mesh and region poses on supported Android devices. MediaPipe Face Landmarker is a separate optional tracking path for detailed landmarks and blendshape scores. It is not included or wired into this scaffold yet. Do not describe this scaffold as a finished MediaPipe integration.

## Unity setup

1. Open this folder in Unity Hub using Unity 2022.3 LTS.
2. Resolve packages from `Packages/manifest.json`.
3. Add AR Session and XR Origin (AR) to a new scene.
4. Add an AR Face Manager to the camera/XR Origin configuration and enable face tracking.
5. Create effect prefabs and assign one to `ARFaceEffectController.effectPrefab`.
6. Build for Android (ARM64). Configure the required camera permission and ARCore support in Player Settings.

## Integration status

This is a Unity-side prototype scaffold, not a completed Android integration or installable APK. The native app's camera, recording, teleprompter, captions, music and sharing paths have not been changed. A later integration step must decide whether Unity owns the camera during AR recording or receives frames from a native camera pipeline; both layers must not try to own the camera simultaneously.

## Next engineering steps

- Add face-specific anchors (left eye, right eye, nose, forehead, mouth) and effect calibration.
- Add tracking-loss handling, temporal smoothing and configurable confidence/visibility rules.
- Evaluate MediaPipe Face Landmarker as an alternative tracking provider where its landmark detail is needed.
- Render the AR camera output into the video encoder so effects are present in exported MP4 files.
- Integrate Unity as a Library into the native Android app and test lifecycle, camera ownership, audio and memory on real devices.
- Add iOS only if/when a cross-platform release is planned.

## Licensing

Unity, AR Foundation and ARCore have their own terms. Verify the applicable Unity plan and all third-party asset/model licences before commercial distribution.


## Face alignment guidance

ARCore Augmented Faces in AR Foundation exposes a face-center pose, three region poses (left forehead, right forehead and nose tip), and a 468-vertex face mesh. Android ARCore face tracking currently tracks one face at a time. Use these region poses and the face mesh to calibrate effects; do not use one generic center offset for every accessory.

- Glasses: calibrate against eye/face-mesh landmarks and verify head yaw.
- Crown/headwear: use forehead region poses and face width from the mesh.
- Nose accessories: use the nose-tip region pose.
- Face paint/masks: use the face mesh UVs and a face-following material; use a depth/occlusion setup where appropriate.
- Keep effect prefab pivots at their intended attachment point and author scale in meters.

For the face prefab, add `ARFaceMeshVisualizer` when a rendered face mesh is needed for debugging or occlusion. Do not ship a visible debug mesh as a consumer effect unless that is the intended design.
