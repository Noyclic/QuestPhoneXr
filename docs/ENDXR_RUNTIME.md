# EndXR Runtime — implementation status

## Direction

QuestPhoneXR includes an app-owned Android OpenXR runtime named **EndXR**. Monado is not a required dependency in the active architecture or build path.

EndXR is an early, minimal runtime implementation. It is not yet a conformant, general-purpose Quest runtime, and compatibility with third-party games has not been demonstrated on a physical phone.

## What exists now

- Native C++ runtime and frame-lifecycle code.
- A loader-facing native library, `libendxr_openxr.so`, built separately from the app JNI library.
- OpenXR loader negotiation through `xrNegotiateLoaderRuntimeInterface`. EndXR currently advertises the OpenXR 1.0 API line (`runtimeApiVersion=1.0.0`); applications requesting 1.1 are deliberately rejected until that core API surface is implemented. The external diagnostic client is compiled with Khronos 1.1.65 headers but requests 1.0.0 explicitly.
- Android runtime service metadata for `org.khronos.openxr.OpenXRRuntimeService`, including the library filename and OpenXR major version.
- An OpenXR entry-point table covering loader bootstrap, instance, system, GLES session, stereo views, swapchain, frame lifecycle, spaces, projection-layer composition and the core Action / Input API command family.
- Action-set/action creation and destruction, per-instance path interning, suggested interaction-profile bindings, session attachment, `xrSyncActions`, boolean/float/vector/pose state queries, action spaces and localized source-name queries. These APIs are covered by native host tests.
- A read-only Android ContentProvider used to expose input snapshots across the app-process boundary. EndXR starts a per-instance native polling worker after instance creation; Binder queries run on that worker at a bounded cadence, while `xrLocateViews`, `xrLocateSpace` and `xrSyncActions` consume cached state without synchronously querying Android IPC. The worker is stopped and joined before the runtime releases its global Activity reference. The default read permission remains signature-protected. QuestPhoneXR can issue a user-approved, read-only URI grant for the exact `controller_state` path to one selected installed launcher app; the approval is tied to that package's signing-certificate fingerprint and can be revoked. The paired diagnostic APK continues to use the signature permission directly. The runtime-owned worker polls the provider independently of the app's frame and action calls.
- A user-started camera foreground service that can keep CameraX and MediaPipe tracking active while Sandbox is paused and another app is in the foreground, provided the user starts it while QuestPhoneXR is visible and grants camera permission. Android displays a persistent notification while the camera remains active. The service uses `START_STICKY` so Android can recreate it after reclaiming the process, and it restores the last selected camera settings; an explicit user stop remains non-sticky.
- MediaPipe pinch/grip values and approximate left/right palm poses. Estimated poses are exposed through `POSE_INPUT` / `xrLocateSpace` with `POSITION_VALID` and `ORIENTATION_VALID`, deliberately without `TRACKED` flags.
- Phone head orientation from the rotation-vector sensor. This is orientation-only; phone translation is not measured.
- Haptic entry points validate their inputs but return `XR_ERROR_FEATURE_UNSUPPORTED` until a device haptic transport exists.
- In-process Sandbox diagnostics have exercised 30 consecutive stereo projection frames and read back the expected left/right colors on the Sandbox EGL surface.
- A separate Android client APK can discover the exported EndXR service and runtime library path.
- The external client probe uses the official Khronos Android OpenXR loader and records loader initialization, runtime extension enumeration, `xrCreateInstance`, HMD system discovery, stereo view configuration and—if a runtime is available—an independent GLES session, swapchain and frame lifecycle.
- Runtime readiness reporting distinguishes partial Action API coverage from live controller input, 6DoF position tracking and OpenXR hand tracking.

The Sandbox result is scoped to the Sandbox EGL context. It does not establish that an unrelated game process has created an instance, session or frame.

## Android runtime selection is a separate stage

EndXR's exported runtime service and native library can be discovered by a separate Android package. That is not the same as selecting EndXR as the active runtime.

The Khronos Android loader obtains active-runtime information from an Android RuntimeBroker content provider or a vendor system broker. The CI emulator currently has neither provider installed, so the external-loader test can correctly report `XR_ERROR_RUNTIME_UNAVAILABLE`.

The emulator test confirms that the official loader initializes and returns a diagnostic result without crashing. It does not prove that EndXR is selected or that `xrCreateInstance` succeeds.

See [OPENXR_LOADER_VALIDATION.md](OPENXR_LOADER_VALIDATION.md) for the probe stages and how to interpret the report.

## Important limitations

EndXR does not currently provide:

- Verified active-runtime selection through RuntimeBroker on the target physical phone.
- Verified third-party game GLES session, swapchain and rendered-frame interoperability on the Redmi Note 13 4G.
- 6DoF positional tracking.
- The `XR_EXT_hand_tracking` runtime extension. MediaPipe palm grip estimates are available through `POSE_INPUT`, but they are not joint-level OpenXR hand tracking.
- Haptic output transport.
- Global Android gamepad capture while another application owns focus, or system-wide input injection into unrelated apps.
- Meta/Oculus Android runtime services required by many Quest-only APKs.
- Universal Quest APK compatibility.

A successful `xrCreateInstance` will still not mean that a particular game is playable. Each title must be checked for required runtime extensions, input APIs, native ABI, graphics backend and vendor services.

## Milestones

1. **Native lifecycle and phone GLES scene** — implemented and covered by CI tests.
2. **Minimal OpenXR runtime bootstrap and projection** — implemented and exercised in-process on the Sandbox surface; emulator diagnostics cover 30 consecutive projection frames.
3. **Android service discovery** — exported service and library visibility from a separate package are covered by emulator instrumentation.
4. **Official Khronos loader integration** — the separate-package probe initializes the official loader in CI and records the result. Active-runtime selection must be confirmed on a device with a usable RuntimeBroker.
5. **OpenXR Action API surface** — initial core action, path, attach, sync and state-query entry points are implemented and host-tested.
6. **Sandbox input bridge** — left/right MediaPipe pinch/grip samples and estimated palm grip poses are mapped to OpenXR input paths. Estimated poses are not marked `TRACKED`.
7. **Cross-process input IPC** — snapshot provider and native runtime polling are implemented. Signature-level default read access is enforced; one-package, read-only URI grants include explicit confirmation, signer binding, startup reconciliation and revocation. External-game query and continuous physical-device input still need testing.
8. **6DoF tracking and OpenXR hand tracking** — not implemented.
9. **Third-party session/frame interoperability** — not yet verified on the target phone.
10. **Game-by-game APK support** — not yet verified.

## Performance target

The initial target is 20–30 FPS on the Redmi Note 13 4G. This is a goal, not a measured result. Resolution, rendering quality, camera workload and thermal throttling must be evaluated on the physical device before making a performance claim.

## Rules for future changes

- Do not reintroduce Monado as a required dependency.
- Do not call the current minimal implementation a complete or conformant OpenXR runtime.
- Do not describe estimated palm poses as true tracked 6DoF controllers.
- Do not treat provider visibility as proof of continuous external-game input.
- Do not treat service discovery as proof of active-runtime selection.
- Keep the working Sandbox and MediaPipe paths intact.
- Report service discovery, RuntimeBroker selection, instance creation, session creation, swapchain operations and frame rendering as separate checkpoints.
- Prefer measurable milestones: native tests, APK build, emulator smoke test, then a clearly identified physical-device test.


## Camera lens privacy default

All QuestPhoneXR hand-tracking entry points share `HandCameraPreference`. The rear camera is the default, including Sandbox, diagnostics and the persistent EndXR foreground service. The front camera is selected only after the user enables **Разрешить фронтальную камеру** in Sandbox settings. The choice is stored in app preferences and reused by service restarts; it is never inferred from camera availability. If the requested front lens is unavailable, the app stays on the rear lens. If the rear lens is unavailable, camera startup fails rather than silently enabling the front camera.
