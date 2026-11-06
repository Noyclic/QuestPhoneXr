# QuestPhoneXR architecture

## Project direction

QuestPhoneXR explores phone-based XR on Android. The phone provides the display, sensors, camera, input and application runtime; a smartphone viewer such as VR Shinecon can be used for stereoscopic viewing.

The Android runtime work is named **EndXR**. PC-to-phone VR streaming is a separate planned direction named **EndVR**. The two projects may share a tracking protocol, but they have different responsibilities and should not be coupled into one runtime.

## Main components

### Android application

The Kotlin application contains the game library, APK import and static analysis, Sandbox, device diagnostics, camera lifecycle and user-facing controls.

### Camera and hand tracking

- CameraX supplies camera frames for analysis.
- MediaPipe Tasks Vision Hand Landmarker detects up to two hands and their landmarks.
- Camera analysis uses a latest-frame strategy to avoid building a queue of stale frames.
- A user-started foreground service can keep camera-based hand tracking active while another application is in the foreground, subject to Android camera permissions and foreground-service rules.
- Pinch/grip values and approximate palm poses are published as input snapshots.

The estimated palm position is camera-relative and uses monocular depth heuristics. It is not calibrated room-space tracking and must not be described as true 6DoF.

### Input and IPC

The app has a normalized controller/action model for Sandbox and supported integrations.

The EndXR runtime can read a snapshot from the app's exported read-only ContentProvider in a separate Android process. The producer publishes controller values, hand-pose estimates and phone head orientation with freshness limits; stale input is neutralized.

This is an early cross-process bridge, not Android-wide input injection. Android does not allow this app to globally capture another foreground application's gamepad events through the ordinary activity input path. Haptic output is not implemented.

The exported provider retains the signature permission `com.noyclic.questphonexr.permission.READ_INPUT_SNAPSHOT` as its default access rule. QuestPhoneXR can explicitly grant read-only access to the exact `controller_state` URI for one selected installed app after user confirmation. The approval is bound to the package's signing-certificate fingerprint and can be revoked; this transport grant does not make a game compatible unless it implements the EndXR input contract. See [IPC security](IPC_SECURITY.md).

### EndXR native OpenXR runtime

EndXR is an app-owned native Android OpenXR runtime implementation. The native runtime library is built separately from the app's JNI library and exposes the Android runtime service metadata expected by the Khronos loader.

The current implementation includes:

- OpenXR loader negotiation.
- Instance and system entry points.
- Android GLES session and stereo view setup.
- Swapchain and frame-lifecycle entry points.
- Projection-layer composition in the in-process Sandbox path.
- An initial OpenXR Action / Input API surface.
- Estimated hand-pose input exposed through `POSE_INPUT` and `xrLocateSpace`, without OpenXR `TRACKED` flags.

The Sandbox has exercised its own stereo projection path. That proves the in-process diagnostic path, not that a separate game can render through EndXR.

### External OpenXR client probe

The `runtime-client-probe` module is a separate Android package. It uses the official Khronos Android OpenXR loader to test the boundary between an external app and EndXR.

The probe records runtime service discovery, loader initialization, active-runtime availability, instance creation and—if the runtime is selected—HMD system discovery, GLES session creation, swapchain operations and a frame lifecycle.

Android RuntimeBroker selection is a separate step. Seeing the EndXR service does not mean Android has selected it as the active runtime. The emulator currently has no RuntimeBroker provider, so `XR_ERROR_RUNTIME_UNAVAILABLE` can be the expected result there.

### PC tracking bridge and EndVR preview

The experimental PC bridge sends head-orientation and MediaPipe hand telemetry over UDP. The current tracking packet is a latest-state snapshot, not a reliable video transport.

It is not:
- a PC VR video streamer;
- a SteamVR driver;
- a calibrated room-space tracking system;
- proof that the PC can use the phone as a complete VR headset.

EndVR is a separate PC-to-phone system. Its first EV-0 slice has a synthetic MJPEG PC host and a standalone Android client that displays the generated left/right-eye test pattern. It does not yet capture desktop/SteamVR frames, use WebRTC/H.264, send phone tracking back to the PC or provide a usable live SteamVR headset path. The module boundaries and remaining milestones are documented in [EndVR streaming design](ENDVR_STREAMING_DESIGN.md).

### EndGVR — separate legacy Gear VR track

EndGVR is an isolated package inside the Android app for Gear VR evidence,
legacy dependency reports and future title-specific compatibility work. It
is not an extension of the modern OpenXR runtime and it must not change
EndXR's loader, runtime service, IPC permission or Sandbox path.

The initial EndGVR code statically distinguishes explicit Samsung Gear VR
markers from generic legacy Oculus Mobile SDK evidence. It does not implement
VrApi, Gear VR Services, a legacy session compositor or APK execution. A
future native compatibility component should be designed only after a real
classic APK has been inspected and its required service/API calls are known.

### EndVR — separate PC streaming track

EndVR is the separate PC streaming system described above. Its generated-frame phone preview is an early test path, not a finished headset. It is not the Gear VR compatibility layer.

## Runtime boundaries and compatibility

QuestPhoneXR does not emulate Meta/Oculus services. Quest-only APKs may require vendor APIs, extensions, input paths, graphics features or system services that EndXR does not currently implement.

The APK analyzer performs static triage only. It can identify likely engines, XR markers, ABI and declared device requirements, but it cannot determine with certainty whether a game will run.

Each title must be evaluated separately. A successful loader probe or OpenXR instance creation is only one interoperability checkpoint, not proof of full game compatibility.

## Performance principles

- Keep the Android runtime and the basic phone-VR path lightweight.
- Make camera hand tracking optional when it is not needed.
- Use conservative render resolution and frame pacing on weaker phones.
- Measure sustained performance and thermal behaviour on real hardware.
- Treat 20–30 FPS as an initial target, not a measured result.
- For future EndVR work, account for both game rendering and video encoding load on the PC.

## Testing strategy

Use separate checkpoints so a passing test cannot be mistaken for a later stage:

1. Native C++ unit tests.
2. Kotlin/JVM unit tests.
3. Android APK build and package validation.
4. Android emulator navigation and OpenXR diagnostic tests.
5. Physical-device camera, sensor and thermal tests.
6. RuntimeBroker selection and external Khronos-loader tests on the phone.
7. External GLES session, swapchain and frame-lifecycle validation.
8. Individual game compatibility tests.

## Rules for future changes

- Keep EndXR (Android runtime) separate from EndVR (PC streaming).
- Do not reintroduce Monado as a required dependency.
- Do not call the current implementation a complete or fully conformant general-purpose Quest runtime.
- Do not label estimated palm poses as tracked 6DoF controllers.
- Do not describe static APK analysis as a compatibility guarantee.
- Preserve the working Sandbox, MediaPipe and CI paths.
- Report runtime discovery, active-runtime selection, instance creation, session creation and frame rendering as distinct milestones.
- Prefer test evidence over percentage-based completion estimates.
