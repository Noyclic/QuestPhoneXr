# EndXR Runtime — implementation status

## Direction

QuestPhoneXR is moving to an app-owned runtime implementation named **EndXR**.
Monado has been removed from the active architecture and build path. EndXR is
being developed as its own runtime rather than as a wrapper around Monado.

## What exists now

- A native C++ lifecycle and frame-clock core in
  `app/src/main/cpp/endxr_runtime_core.hpp/.cpp`.
- Validated render dimensions and target frame rate.
- Runtime states for initialization, session readiness, running, stopping and
  loss/recovery.
- A monotonic predicted-frame clock with one-active-frame-at-a-time checks.
- A Kotlin/JNI bridge that calls the native EndXR core from the Sandbox GL thread.
- Frame-clock begin/end calls around successfully completed phone-side GLES
  scene draws, with safe fallback to the existing renderer if native startup fails.
- Surface pause/resume and recreation handling for the internal runtime core.
- Host-side C++ tests and Kotlin snapshot-mapping tests, all run in CI.

The EndXR core is compiled into `libquestphonexr_native.so`. Its frame counter
currently records successful phone-side GLES draws. It does **not** submit to
an XR swapchain, drive a compositor, or prove OpenXR frame submission. The
Vulkan probe in the same library is only a driver-capability diagnostic.

## Important distinction

EndXR is **not yet a system-installable OpenXR runtime**. It does not currently
implement the Khronos runtime negotiation ABI, OpenXR entry points, Android
runtime registration, stereo swapchain compositor, or Meta/Quest Android
service compatibility. The app therefore cannot yet execute arbitrary Quest
APKs through EndXR.

Building the QuestPhoneXR APK is a separate, much smaller task than making
Quest applications compatible. An APK build does not prove runtime support.

## Milestones

1. **Native lifecycle and frame clock** — implemented and covered by host tests.
2. **JNI bridge and GLES lifecycle** — initial bridge is connected to the
   Sandbox GL thread and wraps each completed phone-side scene draw in the
   native frame clock. This is an integration checkpoint, not an XR compositor.
3. **Phone display backend** — move beyond the current phone-side side-by-side
   view to explicit stereo render targets, then measure frame pacing on the
   Redmi Note 13 4G.
4. **Tracking and input** — connect MediaPipe hand poses, phone orientation and
   Joy-Con/controller input to EndXR tracking spaces and actions.
5. **OpenXR runtime ABI** — implement and test runtime negotiation plus the
   required instance, system, session, swapchain and frame entry points.
6. **Android runtime packaging** — package and register EndXR as an Android
   runtime only after the ABI and lifecycle are implemented.
7. **Quest APK compatibility** — identify and implement the Meta/Oculus
   Android APIs that individual Quest games require. This is separate from
   generic OpenXR and will be tested game by game.

## Performance target

The initial target remains 20–30 FPS on the Redmi Note 13 4G. This is a goal,
not a measured result. Resolution, rendering quality, camera workload and
thermal throttling must be tuned on the physical device before making any
performance claim.

## Rules for future changes

- Do not reintroduce Monado as a required dependency.
- Do not call the current lifecycle/frame-clock core a complete OpenXR runtime.
- Keep the working Sandbox and MediaPipe paths intact while integrating EndXR.
- Prefer measurable milestones: native tests, APK build, emulator smoke test,
  then a clearly identified physical-device test.
