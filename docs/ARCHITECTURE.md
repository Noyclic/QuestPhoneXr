# Architecture notes

## First two stages

### Stage 1 — Android foundation

- Kotlin Android application with a single app module.
- CameraX provides phone-camera preview and RGBA analysis frames.
- Quest devices skip phone-camera startup.
- The Khronos OpenXR Android loader AAR is packaged, and the activity is listed as an immersive-HMD entry point.

### Stage 2 — MediaPipe hand tracking

- Google MediaPipe Tasks Vision Hand Landmarker runs in LIVE_STREAM mode.
- The initial delegate is CPU for predictable startup across a wider range of Android devices.
- CameraX uses KEEP_ONLY_LATEST; the inference controller additionally limits itself to one in-flight frame.
- Camera frames are copied into owned bitmap memory before the CameraX frame is released.
- The model file is downloaded from Google's official model hosting during Gradle preBuild instead of being committed as a binary.

## Device paths

### Android phone

CameraX -> RGBA frame -> MediaPipe Hand Landmarker -> HandTrackingFrame.

### Meta Quest

The APK includes the Khronos OpenXR Android loader and the immersive-HMD manifest category. This is an early packaging/launch milestone only: it is not yet a complete OpenXR renderer. The next Quest-specific change must create the OpenXR instance and session, enumerate the runtime's supported extensions, and render a minimal frame before the sandbox is built.

Quest hand data should come from the runtime's supported OpenXR hand-tracking extension. Do not assume that ordinary Android camera permission exposes raw Quest tracking-camera images.

## Shared tracking contract

HandTrackingFrame, TrackedHand and HandPoint are device-independent value types. Later providers (OpenXR, Joy-Con/controller input, replay/test input) should map into this contract instead of making sandbox code depend on CameraX or MediaPipe classes.
