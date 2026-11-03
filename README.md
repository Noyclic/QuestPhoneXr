# QuestPhoneXR

QuestPhoneXR is a phone-first Android VR prototype. The Android phone is intended to be the computer and display; a phone viewer such as VR Shinecon provides the lenses. A Quest headset is optional and is not required for the target phone-only mode.

## Current project status

- Android app foundation with CameraX and MediaPipe hand tracking.
- Android gamepad input capture and a normalized virtual-controller model.
- A first in-app VR game library with per-device test-report records.
- Explicit game-adapter contracts for source ports and supported plugins.
- A first OpenGL ES side-by-side stereo diagnostic scene with phone rotation-vector (3DoF) tracking for VR Shinecon.
- The current app is still a prototype: the stereo preview has no calibrated Shinecon lens-distortion profile, no 6DoF positional tracking, no phone OpenXR runtime, and no ability to run arbitrary Quest APKs.

## Compatibility promise

The library distinguishes project information from real device testing. A Quest APK, Android port, or source repository is not evidence that a game works on a phone. A device-specific status appears only after a test report is recorded for that exact manufacturer and model. Reports entered in this build are stored locally on the device and are not uploaded or published.

The first planned test device is Redmi Note 13 4G on Android 15. No game is marked as tested on that device until an actual launch and gameplay test has been completed.

## Game support architecture

A game can become phone-compatible through one of three routes:

1. Use a separate Android build that already supports the phone's VR display and input model.
2. Port the game's source project to the phone-native VR renderer and input contracts.
3. Add a game-specific adapter or plugin when the game exposes a supported integration point.

The QuestPhoneXR input adapter is not a system-wide driver and cannot inject controller input into every unrelated Android APK. Quest-only applications may require source-level changes, replacement of Meta-specific APIs, or a separate Android build. Some games may remain incompatible.

### Universal APK triage (prototype)

The APK library now classifies imported archives into four honest routes: ordinary Android launch candidate, XR source/adapter required, Meta Quest runtime required, or blocked by device requirements. It scans APK archive entries for common Unity/Unreal/Godot and OpenXR/Oculus/SteamVR markers, checks Android API/ABI/required hardware, and explains the likely next step. This is static triage only; it does not prove compatibility or execute APK code.

A separate action can open an installed Android package through Android's launcher. That starts the app as a normal Android application, outside QuestPhoneXR's VR renderer. Quest-only APKs still need a real compatible runtime or a source port; the classifier does not emulate Quest services.

## Development setup

- Android Studio with Android SDK 36.
- JDK 17.
- A physical Android phone for CameraX and MediaPipe tests.
- Android NDK/CMake will be required for the future native runtime layer.

Open the repository root in Android Studio and sync Gradle. GitHub Actions builds the debug APK using a provisioned Gradle installation; the repository does not yet include the binary Gradle wrapper JAR.

## Automated CI checks

Every push or pull request to `main` runs the native EndXR core tests and Kotlin/JVM unit tests, builds the debug APK, and validates that the application package, MediaPipe hand model, and arm64 native library are present. This automatic path does not require manually starting a workflow.

The navigation smoke test on an Android emulator is available as an optional manual-dispatch CI step. When run, it saves screenshots, emulator `logcat`, and test reports in the `QuestPhoneXR-CI-diagnostics` artifact for 14 days. The debug APK is uploaded by the normal automatic build when that build succeeds.

An emulator pass is a launch/navigation smoke test, not proof of Redmi hardware compatibility. It cannot validate the physical camera feed, MediaPipe performance on Adreno 610, EndXR rendering on the phone GPU, Joy-Con Bluetooth behavior, thermal throttling, or real 20–30 FPS. Those still need a device test.

## Roadmap

1. Phone-first library, device-specific compatibility records, and game-adapter contracts.
2. Calibrated VR Shinecon viewer profile, lens distortion correction and stereo alignment.
3. Stable phone-VR head tracking, hand/controller calibration and Joy-Con mapping.
4. EndXR phone runtime and first game port/adaptation, starting with a lightweight test project.
5. Carefully scoped Quest/OpenXR compatibility adapters for games that can legally and technically be ported.

## Device compatibility diagnostics

The library includes a non-blocking device preflight for phone-VR sensors, OpenGL ES, Android-reported Vulkan features, memory and CPU/ABI. It reports missing capabilities without preventing users from trying available modes. See [docs/DEVICE_COMPATIBILITY.md](docs/DEVICE_COMPATIBILITY.md) for the limits of the probe; in particular, Vulkan compositor extensions and real runtime compatibility are not yet verified.

## EndXR runtime work

See [docs/ENDXR_RUNTIME.md](docs/ENDXR_RUNTIME.md) for the implementation status, milestones and explicit compatibility limits.

## Licensing

Project source: MIT unless a third-party dependency or asset specifies otherwise. Third-party SDKs, game source code, assets and models retain their own licenses.
