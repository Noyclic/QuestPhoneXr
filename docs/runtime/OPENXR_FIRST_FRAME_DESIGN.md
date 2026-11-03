# QuestPhoneXR OpenXR Runtime: sustained-frame milestone

## Goal
Turn the existing Monado/OpenXR capability checks into an isolated, real OpenXR GLES session that submits a bounded sequence of stereo projection frames. This validates the runtime path but is not yet a persistent game runtime or a Quest APK compatibility layer.

## Current evidence
- The Redmi Note 13 4G reports Vulkan 1.1 / Adreno 610 and GLES 3.2.
- The installed Monado Out-of-Process APK exposes its runtime library and services.
- A real Android bind to org.freedesktop.monado.ipc.MonadoService returned Binder descriptor org.freedesktop.monado.ipc.IMonado.
- The native GLES probe creates an instance, system, session and stereo swapchain, waits for READY, begins the session, then submits 30 consecutive stereo frames through xrEndFrame and tears everything down.
- The probe binds OpenXR to the already-current EGL display, GLES context and the exact EGLConfig associated with the diagnostic GLSurfaceView. It no longer creates a separate pbuffer or temporary GLES context.
- SandboxActivity now requests a GLES 3 context. Its existing GLES20-based renderer remains in place and is still rendered to the normal GLSurfaceView; it has not yet been routed into the OpenXR swapchain.
- The earlier loader path crashed inside Monado's Java client while forwarding the app Surface. Binder reachability alone does not prove that Surface/IPC bootstrap works.

## Android Monado connection contract
The supported out-of-process path is owned by the Khronos OpenXR loader and Monado's Android client glue:
1. The application calls OpenXR from a worker thread, not the UI thread.
2. Monado's client binds MonadoService through AIDL.
3. When overlay mode is not active, Monado injects a Surface into the client Activity and forwards it with IMonado.passAppSurface.
4. The client creates a Unix socket pair and transfers one FD through IMonado.connect; the runtime server owns the transferred FD.
5. The native runtime then accepts the client IPC channel and compositor session setup can proceed.

QuestPhoneXR must not manually duplicate this private protocol in parallel with the Khronos loader. It must pass a live Activity context and run the loader/session bootstrap off the main thread.

## Design decisions
- Keep Monado enabled and use the official Khronos Android OpenXR loader.
- Run the risky native OpenXR probe in a dedicated Android process named :xr_runtime_probe so a native crash does not terminate the game library or normal Sandbox process.
- Invoke the diagnostic probe only on its dedicated GLES 3 GL thread while the Activity is alive; this provides a current EGL context and a valid Activity for Monado's Surface handoff. The diagnostic Activity still owns a different GLSurfaceView/context from SandboxActivity.
- The sustained probe uses a GLES 3 texture-array swapchain with one layer per stereo view, a LOCAL reference space, the runtime's located eye poses/FOVs, and a minimal clear-only render.
- The probe processes session-state events, waits for READY, begins one session, then submits 30 consecutive stereo frames. Every rendered frame performs xrWaitFrame, xrBeginFrame, xrLocateViews, acquire/wait/release of the swapchain image, and xrEndFrame with a projection layer. The loop is bounded by 8 seconds and 90 frame attempts; swapchain image waits are bounded to 250 ms.
- Always tear down in reverse order and report the exact failing stage and XrResult.
- The earlier 30-frame probe remains a bounded hardware-validation test. The newer persistent owner keeps an OpenXR session alive between GL callbacks, but still renders only a diagnostic clear into the swapchain. Neither path renders the Sandbox scene, provides camera passthrough, exposes hand tracking as OpenXR actions, adds controller actions, or executes arbitrary Quest APKs.

## Persistent-session implementation status

The first persistent-session slice is now implemented in the isolated `:xr_runtime_probe` process:

- `OpenXrPersistentGlesSession` owns the native OpenXR instance, session, LOCAL space, stereo swapchain and swapchain image list between GL callbacks.
- `nativeCreateSession`, `nativeRenderFrame`, `nativePauseSession`, `nativeResumeSession` and `nativeDestroySession` form the native lifecycle boundary.
- A render callback submits at most one stereo projection frame. It does not create or destroy the OpenXR instance/session/swapchain for each frame.
- The loop processes READY and STOPPING events, begins only after READY, and pauses by requesting session exit before the Activity releases its GL thread.
- The diagnostic screen displays a submitted-frame counter and stops issuing native frame calls after a stage failure.
- Each successful `xrLocateViews` sample records orientation and position validity separately, then separately counts the `ORIENTATION_TRACKED` and `POSITION_TRACKED` flags. A pose may be valid but not actively tracked; that distinction is especially useful for a phone runtime where 3DoF orientation may be available without positional tracking.
- The persistent report includes the current OpenXR session state, latest view-state flags, pose sample counts, valid/tracked sample counts and maximum observed head-position/rotation deltas from the first valid sample. Teardown snapshots these metrics before native resources are reset.
- The report can be copied directly from the diagnostic screen with the one-tap copy button, so on-device test results can be shared as text.
- EGL context identity is checked on every render callback. If the context is recreated, the Activity destroys stale session resources and creates a new session on the new GL context.
- The process remains isolated because Monado's Android Surface/IPC path previously caused a native-side crash. Binder reachability is not proof that Surface handoff is safe.

This is a **persistent runtime validation screen**, not yet the persistent Sandbox renderer. It currently clears the two swapchain eye layers; the existing Sandbox still renders to its own phone-view surface and does not submit its scene through this session. The Sandbox Activity therefore remains protected from native runtime failures.

## Next milestone: migrate the proven owner into Sandbox

1. Verify the persistent screen on the Redmi: reach READY, submit multiple frames over time, pause/resume, stop and restart without losing the runtime.
2. Only after device verification, move the native session owner into the Sandbox process and bind it to the SandboxActivity's own active GLES 3 context.
3. Preserve the phone-view Sandbox renderer as a fallback until the in-Sandbox session and compositor path are device-verified.
4. Route the existing scene into both eyes only after session lifecycle and compositor submission are stable; a clear-only diagnostic frame is not completed Sandbox integration.
5. Later, add camera passthrough, MediaPipe hand actions and controller actions; a persistent OpenXR session alone does not provide these.


## Success criteria
A device report says SUSTAINED_FRAMES_SUBMITTED_GLES only after one xrBeginSession and 30 complete consecutive stereo frame cycles with successful xrEndFrame calls. The legacy FIRST_FRAME_SUBMITTED_GLES status remains recognized for older APKs. A created swapchain or a single successful frame alone is not sustained-loop success.