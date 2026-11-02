# QuestPhoneXR

QuestPhoneXR is a native Android XR prototype with two device paths:

- **Android phone** — CameraX input and MediaPipe Hand Landmarker for low-latency hand tracking experiments.
- **Meta Quest** — an early installable APK with the Khronos OpenXR Android loader and an immersive-HMD launch entry. The native OpenXR session/render loop is the next verification milestone.

## First implementation pass

1. Establish a clean Kotlin + Android Gradle application.
2. Add CameraX and MediaPipe Hand Landmarker in LIVE_STREAM mode (CPU-first for predictable device compatibility).
3. Package the Android OpenXR loader and expose the app as an immersive-HMD launch target.
4. Keep tracking output behind a device-independent hand-tracking contract so the future sandbox does not depend directly on CameraX or MediaPipe.

## Development setup

- Android Studio with Android SDK 36.
- JDK 17.
- A physical Android phone for the phone-camera tracking test.
- A Meta Quest headset for immersive launch/runtime verification.
- Android NDK and CMake will be required when the native OpenXR renderer is introduced.

Open the repository root in Android Studio and sync Gradle. GitHub Actions builds the debug APK using a provisioned Gradle installation; the repository does not yet include the binary Gradle wrapper JAR.

## Roadmap

1. Android foundation + MediaPipe live hand tracking.
2. OpenXR instance/session + an early Quest APK install and runtime check.
3. Sandbox test room: platform, cubes, balls, targets and props.
4. Pinch-to-grab, 3D hand representation and physics.
5. Joy-Con input and performance controls.
6. Broader SteamVR compatibility.

The Quest APK is an early checkpoint, not a final stage. Phone camera tracking and Quest-native hand tracking will use separate providers behind the same hand-tracking contract. Quest-native hand tracking should use the runtime's OpenXR hand-tracking support; the app will not assume that raw headset camera frames are available to ordinary Android apps.

## Licensing

Project source: MIT unless a third-party dependency or asset specifies otherwise. Third-party SDKs and models retain their own licenses.
