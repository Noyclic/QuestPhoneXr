# QuestPhoneXR architecture

## Product direction

The primary target is standalone VR on an Android phone inside a smartphone viewer such as VR Shinecon. The phone owns the camera, hand tracking, gyroscope, controller input, rendering and game execution. A Meta Quest headset is optional and is not a required host.

## Current implementation

### Phone tracking

- Kotlin Android application.
- CameraX provides phone-camera preview and RGBA analysis frames.
- Google MediaPipe Tasks Vision Hand Landmarker runs in LIVE_STREAM mode.
- The initial delegate is CPU for predictable startup across a wider range of Android devices.
- CameraX uses KEEP_ONLY_LATEST; inference is limited to one in-flight frame.
- Camera frames are copied into owned bitmap memory before the CameraX frame is released.

### Controller abstraction

Phone hand tracking and Android gamepad input are combined into a device-independent VirtualControllerFrame. Camera-relative hand pose is not yet an absolute room-space pose. A calibration and tracking stage is required before using hand position for world-space grabbing or saber swings.

### Game library and test evidence

GameCatalog holds project metadata and known adaptation paths. GameTestReportStore stores manually entered test results locally. GameCompatibilityEvaluator marks a game as tested only when the report manufacturer and model match the current device. Android version, app version, FPS and notes remain attached to each report.

A manually entered report is not independently verified and is not uploaded. Shared/community reports and automatic benchmark capture are future work.

### Game input adapters

GameInputAdapter converts VirtualControllerFrame into a stable GameActionFrame. GameAdapterRegistry resolves adapters only for explicitly registered game IDs. This prevents the project from implying that a separate Android app can inject controls into arbitrary third-party APKs.

### Phone stereo preview

PhoneVrPreviewActivity opens a native OpenGL ES 2.0 scene with two horizontally split eye views. The phone's game rotation vector (or rotation vector fallback) updates a 3DoF head orientation matrix. The preview is useful for checking stereo separation and basic head response in a smartphone viewer.

This is not yet the final Shinecon renderer. It has no lens distortion correction, viewer QR/profile database, IPD calibration or 6DoF positional tracking. Those values must be calibrated against the exact Shinecon model.

## Target phone-native VR pipeline

Phone sensors + camera
  -> head orientation and hand tracking
  -> calibration into phone-VR play space
  -> controller/action abstraction
  -> phone-native stereo renderer
  -> per-game integration/port
  -> display through the VR viewer lenses

## OpenXR and Quest boundary

The project currently includes the Khronos Android OpenXR loader dependency, but it does not create an OpenXR instance/session, provide an OpenXR runtime, or render an OpenXR frame. A loader library alone is not an OpenXR runtime.

The old phone-to-Quest UDP sender and Unity sample remain optional experiments under integrations/bbvr. They are not part of the primary phone-only architecture and do not enable universal controller injection into Quest or Android games.

## Performance principles

- Keep the phone-native base runtime small.
- Make advanced hand tracking optional so simple head tracking and controller input can run on weaker phones.
- Use scalable render resolution, frame pacing and conservative defaults.
- Keep expected game load as an estimate until real device measurements exist.
- Never label a game as tested on a phone without a matching test report.
