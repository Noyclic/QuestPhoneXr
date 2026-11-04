# EndXR Runtime — implementation status

## Direction

QuestPhoneXR uses an app-owned runtime implementation named **EndXR**.
Monado is not a required dependency in the active architecture or build path.

## What exists now

- Native C++ runtime and frame lifecycle code.
- A loader-facing native library, `libendxr_openxr.so`, built separately from
  the app JNI library.
- OpenXR loader negotiation through `xrNegotiateLoaderRuntimeInterface`.
- Android runtime service metadata for
  `org.khronos.openxr.OpenXRRuntimeService`, including the library filename
  and OpenXR major version.
- A minimal OpenXR entry-point table covering loader bootstrap, instance,
  system, GLES session, stereo views, swapchain, frame lifecycle, spaces and
  projection-layer composition.
- In-process Sandbox diagnostics that have exercised 30 consecutive stereo
  projection frames and read back the expected left/right colors on the
  Sandbox EGL surface.
- A separate Android client APK that runs in another package/process and can
  discover the exported EndXR service and read the runtime library path.
- A separate client path using the official Khronos Android OpenXR Loader.
  It records loader initialization, runtime extension enumeration,
  `xrCreateInstance`, HMD system discovery and stereo view configuration.
- Runtime readiness reporting that explicitly identifies unsupported Actions,
  controller input, 6DoF position tracking and OpenXR hand-tracking APIs.

The Sandbox result is scoped to the Sandbox EGL context. It does not establish
that an unrelated game process has created an instance, session or frame.

## Android runtime selection is a separate stage

EndXR's exported runtime service and native library are visible to a separate
Android package in CI. That is not the same as selecting EndXR as the active
runtime. The Khronos Android loader obtains active-runtime information from an
Android RuntimeBroker content provider or a vendor system broker. The
CI emulator currently has neither provider installed, so the external-loader
test is designed to report that condition rather than invent a successful
selection.

See [OPENXR_LOADER_VALIDATION.md](OPENXR_LOADER_VALIDATION.md) for the client
probe and for interpreting `xrCreateInstance` results.

## Important limitations

EndXR is an early, minimal runtime implementation, not a conformant general
purpose Quest runtime. It currently does not implement:

- OpenXR Action Sets, Actions, interaction profiles or the
  `xrSyncActions` / `xrGetActionState*` API family.
- OpenXR controller pose, button, trigger and haptic state.
- 6DoF positional tracking.
- The `XR_EXT_hand_tracking` runtime extension. MediaPipe hand tracking in
  QuestPhoneXR is app-internal until it is connected to the OpenXR API.
- Meta/Oculus Android runtime services required by many Quest-only APKs.
- A tested third-party graphics session and rendered frame on the Redmi.

A successful Khronos-loader `xrCreateInstance` will still not mean that
Open Saber, Granny VR or Baldi's Basics VR can be played. Those titles must be
checked individually for required runtime extensions, input APIs, native ABI,
graphics backend and vendor services.

## Milestones

1. **Native lifecycle and phone GLES scene** — implemented and CI-tested.
2. **Minimal OpenXR runtime bootstrap and projection** — implemented and
   exercised in-process on the Sandbox surface; emulator report covers
   30 consecutive projection frames.
3. **Android discovery** — exported service and library visibility from a
   separate package are covered by emulator instrumentation.
4. **Official Khronos loader integration** — separate-package loader probe is
   being added; actual active-runtime selection must be confirmed on a device
   with an appropriate RuntimeBroker.
5. **OpenXR Actions and controller input** — not implemented.
6. **6DoF tracking and OpenXR hand tracking** — not implemented.
7. **Third-party session/frame interoperability** — not yet verified.
8. **Game-by-game APK support** — not yet verified.

## Performance target

The initial target remains 20–30 FPS on the Redmi Note 13 4G. This is a goal,
not a measured result. Resolution, rendering quality, camera workload and
thermal throttling must be tuned on the physical device before making any
performance claim.

## Rules for future changes

- Do not reintroduce Monado as a required dependency.
- Do not call the current minimal implementation a complete or conformant
  OpenXR runtime.
- Keep the working Sandbox and MediaPipe paths intact.
- Separate service discovery, active-runtime selection, instance creation,
  session creation and frame rendering in diagnostics.
- Prefer measurable milestones: native tests, APK build, emulator smoke test,
  then a clearly identified physical-device test.
