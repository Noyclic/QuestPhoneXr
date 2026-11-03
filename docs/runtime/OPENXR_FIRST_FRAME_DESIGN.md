# QuestPhoneXR OpenXR Runtime: first-frame milestone

## Goal
Turn the existing Monado/OpenXR capability checks into an isolated, real OpenXR GLES session that submits one valid stereo projection frame. This is the first runtime milestone, not yet a Quest APK compatibility layer or a persistent game runtime.

## Current evidence
- The Redmi Note 13 4G reports Vulkan 1.1 / Adreno 610 and GLES 3.2.
- The installed Monado Out-of-Process APK exposes its runtime library and services.
- A real Android bind to org.freedesktop.monado.ipc.MonadoService returned Binder descriptor org.freedesktop.monado.ipc.IMonado.
- The current native GLES probe creates an instance, system, session and swapchain, then destroys them without beginning a session or submitting a frame.
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
- Invoke the probe only on a dedicated GLES 3 GL thread while the Activity is alive; this provides a current EGL context and a valid Activity for Monado's Surface handoff.
- The sustained probe uses a GLES 3 texture-array swapchain with one layer per stereo view, a LOCAL reference space, the runtime's located eye poses/FOVs, and a minimal clear-only render.
- The probe processes session-state events, waits for READY, begins one session, then submits 30 consecutive stereo frames. Every rendered frame performs xrWaitFrame, xrBeginFrame, xrLocateViews, acquire/wait/release of the swapchain image, and xrEndFrame with a projection layer. The loop is bounded by 8 seconds and 90 frame attempts; swapchain image waits are bounded to 250 ms.
- Always tear down in reverse order and report the exact failing stage and XrResult.
- This milestone is a hardware validation probe. It does not yet provide a persistent renderer, camera passthrough, hand tracking inside OpenXR, controller actions, or arbitrary Quest APK execution.

## Success criteria
A device report says SUSTAINED_FRAMES_SUBMITTED_GLES only after one xrBeginSession and 30 complete consecutive stereo frame cycles with successful xrEndFrame calls. The legacy FIRST_FRAME_SUBMITTED_GLES status remains recognized for older APKs. A created swapchain or a single successful frame alone is not sustained-loop success.