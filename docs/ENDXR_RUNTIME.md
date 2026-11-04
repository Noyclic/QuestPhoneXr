# EndXR Runtime — implementation status

## Direction

QuestPhoneXR uses an app-owned runtime implementation named **EndXR**.
Monado is not a required dependency in the active architecture or build path.

## What exists now

- Native C++ runtime and frame lifecycle code.
- A loader-facing native library, libendxr_openxr.so, built separately from
  the app JNI library.
- OpenXR loader negotiation through xrNegotiateLoaderRuntimeInterface.
- Android runtime service metadata for
  org.khronos.openxr.OpenXRRuntimeService, including the library filename and
  OpenXR major version.
- An OpenXR entry-point table covering loader bootstrap, instance, system,
  GLES session, stereo views, swapchain, frame lifecycle, spaces,
  projection-layer composition and the core Action / Input API command family.
- Action-set/action creation and destruction, per-instance path interning,
  suggested interaction-profile bindings, session attachment, xrSyncActions,
  boolean/float/vector/pose state queries, action spaces and localized source
  name queries are implemented and covered by native host tests.
- The Sandbox publishes independent left/right controller samples to an
  in-memory input snapshot store. A read-only exported Android ContentProvider
  exposes one row with trigger, grip, thumbstick, button mask, sequence and
  timestamp plus a normalized phone head-orientation quaternion and its own
  freshness lease. When an imported OpenXR candidate is launched from
  ApkImportActivity, QuestPhoneXR grants that exact read-only URI to the game
  package so the game does not have to add a package-visibility query for the
  provider authority. Controller samples expire after 300 ms; head orientation
  expires independently after 500 ms. Stale values are neutral/inactive.
- The loader-facing runtime retains the Android VM and Activity supplied through
  XR_KHR_android_create_instance. At xrSyncActions it polls the provider from
  the game process (throttled to one query per 8 ms) and refreshes the runtime's
  local Action input cache. The separate client APK verifies that a different
  package can query the provider; Android CI now asserts this IPC checkpoint.
- A user-started camera foreground service now owns the CameraX lifecycle and
  listens to the phone rotation-vector sensor when the user enables
  "Фоновое отслеживание рук для Quest-игр" in Sandbox. It republishes MediaPipe
  pinch/grip and a normalized head-orientation quaternion while Sandbox is
  paused, and clears the IPC snapshot when stopped. It must be started while
  QuestPhoneXR is visible and camera permission is granted; Android keeps a
  persistent notification while the camera remains active.
- The foreground producer provides phone head orientation, camera-hand
  pinch/grip and approximate left/right palm grip poses. Pose estimates use
  image position, monocular apparent palm width and MediaPipe hand-local
  landmarks. OpenXR POSE_INPUT / xrLocateSpace expose POSITION_VALID and
  ORIENTATION_VALID, deliberately without TRACKED flags. Android gamepad key
  or motion events are not globally intercepted after another app receives
  focus; true controller tracking and haptics remain unavailable. Phone
  translation is not measured, so this is not 6DoF. Physical-device
  sensor/camera lifecycle and thermal impact remain to be verified on Redmi.
- Haptic output entry points validate their inputs but return
  XR_ERROR_FEATURE_UNSUPPORTED until a device haptic transport exists.
- In-process Sandbox diagnostics have exercised 30 consecutive stereo
  projection frames and read back the expected left/right colors on the
  Sandbox EGL surface.
- A separate Android client APK runs in another package/process and can
  discover the exported EndXR service and read the runtime library path.
- A separate client path uses the official Khronos Android OpenXR Loader.
  It records loader initialization, runtime extension enumeration,
  xrCreateInstance, HMD system discovery and stereo view configuration.
- Runtime readiness reporting distinguishes partial Action API coverage from
  live controller input, 6DoF position tracking and OpenXR hand tracking.

The Sandbox result is scoped to the Sandbox EGL context. It does not establish
that an unrelated game process has created an instance, session or frame.

## Android runtime selection is a separate stage

EndXR's exported runtime service and native library are visible to a separate
Android package in CI. That is not the same as selecting EndXR as the active
runtime. The Khronos Android loader obtains active-runtime information from an
Android RuntimeBroker content provider or a vendor system broker. The
CI emulator currently has neither provider installed, so the external-loader
test correctly reports XR_ERROR_RUNTIME_UNAVAILABLE rather than inventing a
successful selection.

See OPENXR_LOADER_VALIDATION.md for the client probe and for interpreting the
xrCreateInstance result.

## Important limitations

EndXR is an early, minimal runtime implementation, not a conformant general
purpose Quest runtime. It currently does not implement:

- Continuous cross-process hand input is partially implemented through the
  user-started camera foreground service and ContentProvider polling at
  xrSyncActions. Android gamepad events are not globally intercepted when
  another app owns focus, and no input is injected into another app's window.
- Haptic output transport.
- 6DoF positional tracking.
- The XR_EXT_hand_tracking runtime extension. Estimated MediaPipe palm grip
  poses are available through POSE_INPUT, but this is not joint-level
  XR_EXT_hand_tracking.
- Meta/Oculus Android runtime services required by many Quest-only APKs.
- A tested third-party graphics session and rendered frame on the Redmi.

A successful Khronos-loader xrCreateInstance will still not mean that Open
Saber, Granny VR or Baldi's Basics VR can be played. Those titles must be
checked individually for required runtime extensions, input APIs, native ABI,
graphics backend and vendor services.

## Milestones

1. **Native lifecycle and phone GLES scene** — implemented and CI-tested.
2. **Minimal OpenXR runtime bootstrap and projection** — implemented and
   exercised in-process on the Sandbox surface; emulator report covers
   30 consecutive projection frames.
3. **Android discovery** — exported service and library visibility from a
   separate package are covered by emulator instrumentation.
4. **Official Khronos loader integration** — separate-package Khronos loader
   initializes successfully in CI. xrCreateInstance correctly returns
   XR_ERROR_RUNTIME_UNAVAILABLE on the emulator because it has no RuntimeBroker.
   Actual active-runtime selection must be confirmed on the Redmi.
5. **OpenXR Action API surface** — initial core action/path/attach/sync/state
   entry points are implemented and host-tested. This is an API milestone,
   not live controller input.
6. **In-process Sandbox-to-EndXR input bridge** — initial implementation
   maps left/right MediaPipe pinch/grip samples to Boolean/Float actions and
   estimated palm grip poses to POSE_INPUT / xrLocateSpace. Native tests cover
   hand isolation, active profile selection, pose location and bound-source
   enumeration. Estimated poses are not marked TRACKED.
7. **Cross-process input IPC** — snapshot provider and native runtime polling
   are implemented; separate-package Android CI verifies the endpoint.
   User-started foreground camera publication is implemented; Android-wide
   gamepad capture and haptics remain incomplete.
8. **6DoF tracking and OpenXR hand tracking** — not implemented.
9. **Third-party session/frame interoperability** — not yet verified.
10. **Game-by-game APK support** — not yet verified.

## Performance target

The initial target remains 20–30 FPS on the Redmi Note 13 4G. This is a goal,
not a measured result. Resolution, rendering quality, camera workload and
thermal throttling must be tuned on the physical device before making any
performance claim.

## Rules for future changes

- Do not reintroduce Monado as a required dependency.
- Do not call the current minimal implementation a complete or conformant
  OpenXR runtime.
- Do not call an IPC snapshot a live controller feed while the Sandbox producer
  is paused. Do not call neutral/inactive Action API results real controller input.
- Keep the working Sandbox and MediaPipe paths intact.
- Separate service discovery, active-runtime selection, instance creation,
  session creation and frame rendering in diagnostics.
- Prefer measurable milestones: native tests, APK build, emulator smoke test,
  then a clearly identified physical-device test.
