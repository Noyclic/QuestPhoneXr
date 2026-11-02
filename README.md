# QuestPhoneXR

QuestPhoneXR is a phone-first Android VR prototype. The Android phone is intended to be the computer and display; a phone viewer such as VR Shinecon provides the lenses. A Quest headset is optional and is not required for the target phone-only mode.

## Current project status

- Android app foundation with CameraX and MediaPipe hand tracking.
- Android gamepad input capture and a normalized virtual-controller model.
- A first in-app VR game library with per-device test-report records.
- Game-adapter contracts for mapping controller frames into game-specific integrations.
- The current app is still a prototype: it does not yet render a stereoscopic VR scene, implement a phone OpenXR runtime, or run arbitrary Quest APKs on a phone.

## Compatibility promise

The library distinguishes project information from real device testing. A Quest APK, Android port, or source repository is not evidence that a game works on a phone. A device-specific status appears only after a test report is recorded for that exact manufacturer and model. Reports entered in this build are stored locally on the device and are not uploaded or published.

The first planned test device is Redmi Note 13 4G on Android 15. No game is marked as tested on that device until an actual launch and gameplay test has been completed.

## Game support architecture

A game can become phone-compatible through one of three routes:

1. Use a separate Android build that already supports the phone's VR display and input model.
2. Port the game's source project to the phone-native VR renderer and input contracts.
3. Add a game-specific adapter or plugin when the game exposes a supported integration point.

The QuestPhoneXR input adapter is not a system-wide driver and cannot inject controller input into every unrelated Android APK. Quest-only applications may require source-level changes, replacement of Meta-specific APIs, or a separate Android build. Some games may remain incompatible.

## Development setup

- Android Studio with Android SDK 36.
- JDK 17.
- A physical Android phone for CameraX and MediaPipe tests.
- Android NDK/CMake will be required for the future native rendering/runtime layer.

Open the repository root in Android Studio and sync Gradle. GitHub Actions builds the debug APK using a provisioned Gradle installation; the repository does not yet include the binary Gradle wrapper JAR.

## Roadmap

1. Phone-first library, device-specific compatibility records, and game-adapter contracts.
2. VR Shinecon viewer profile, split-screen stereoscopic test scene, lens distortion correction, and gyroscope-based 3DoF head tracking.
3. Stable hand/controller calibration and Joy-Con mapping for a phone VR play space.
4. Phone-native runtime and first game port/adaptation, starting with a lightweight test project.
5. Carefully scoped Quest/OpenXR compatibility adapters for games that can legally and technically be ported.

## Licensing

Project source: MIT unless a third-party dependency or asset specifies otherwise. Third-party SDKs, game source code, assets and models retain their own licenses.
