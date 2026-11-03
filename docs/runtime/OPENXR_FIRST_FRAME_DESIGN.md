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
- This remains a bounded hardware-validation probe. It does not yet keep an OpenXR session alive between frames/callbacks, render the Sandbox scene into the OpenXR swapchain, provide camera passthrough, expose hand tracking as OpenXR actions, add controller actions, or execute arbitrary Quest APKs.

## Next milestone: persistent Sandbox session
1. Refactor native initialization, per-frame submission and teardown into a persistent session object with explicit lifecycle states.
2. Create/destroy that object only on the SandboxActivity GLES 3 GL thread, using the SandboxActivity's own current EGL context and window surface.
3. Handle Android pause/resume, surface recreation, READY/STOPPING/EXITING and swapchain image ownership without blocking the UI thread.
4. Keep the existing phone-view Sandbox renderer as a fallback until the persistent OpenXR path is device-verified.
5. Route scene rendering into both eyes only after session lifecycle and compositor submission are stable; don't treat a diagnostic clear-only frame as completed Sandbox integration.

## Success criteria
A device report says SUSTAINED_FRAMES_SUBMITTED_GLES only after one xrBeginSession and 30 complete consecutive stereo frame cycles with successful xrEndFrame calls. The legacy FIRST_FRAME_SUBMITTED_GLES status remains recognized for older APKs. A created swapchain or a single successful frame alone is not sustained-loop success.