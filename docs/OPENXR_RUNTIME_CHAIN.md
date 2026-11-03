# OpenXR runtime chain

## What exists now

QuestPhoneXR has the Khronos Android OpenXR loader dependency and a conservative discovery/preflight layer.

The discovery layer:
- chooses the loader ABI corresponding to the current app process;
- queries the Khronos system and installable Runtime Broker authorities;
- reads the active runtime package, native library directory and shared-library filename;
- checks whether the reported runtime library file exists;
- never calls `dlopen` and never claims the runtime is initialized.

The APK preflight distinguishes plain OpenXR markers from Quest/Meta-specific markers. It requires an installed package and a discovered active runtime before handing an OpenXR candidate to Android's package launcher.

The native diagnostics now cover two levels:

- The library/status probe initializes the Khronos Android loader, enumerates active-runtime extensions, requests `XR_KHR_android_create_instance`, calls `xrCreateInstance`, requests the HMD form factor with `xrGetSystem`, reads system properties and destroys the diagnostic instance.
- When the Sandbox starts, a second probe runs on its GL thread. It requests `XR_KHR_opengl_es_enable`, queries the Runtime's GLES version range, creates a temporary GLES 3 EGL pbuffer/context, calls `xrCreateSession`, enumerates primary-stereo view configuration and swapchain formats, creates a color swapchain, and enumerates its GLES images. The temporary session and swapchain are then destroyed, and the Sandbox EGL context is restored.

The second probe is a smoke test, not the final renderer. It does not begin the session, acquire/wait/release swapchain images, call `xrWaitFrame`/`xrBeginFrame`/`xrEndFrame`, or submit projection layers to the compositor. A successful swapchain test proves resource setup only, not that an APK can display frames.

## Android loader contract

Android OpenXR applications use the Khronos loader to find the active runtime through a ContentProvider. The installable Runtime Broker is a separate Khronos-maintained application; QuestPhoneXR must not impersonate it or publish its reserved authority.

An installable runtime package is identified through an exported service with the action `org.khronos.openxr.OpenXRRuntimeService` and metadata:
- `org.khronos.openxr.OpenXRRuntime.SoFilename`
- `org.khronos.openxr.OpenXRRuntime.MajorVersion`

The runtime package must also support loading its native library from another app process as required by the broker contract. The official broker performs user selection of the active runtime.

## Runtime implementation still required

The next native milestone is a real Android OpenXR runtime, not another loader wrapper. It must provide:
1. Loader/runtime interface negotiation (`xrNegotiateLoaderRuntimeInterface`).
2. OpenXR instance, system, session and extension implementations.
3. A compositor and swapchain import/submission path.
4. Frame timing and prediction.
5. Head pose tracking and reference spaces.
6. Action/input profiles and controller/hand input.
7. Android runtime service metadata and broker selection integration.
8. Device-specific graphics capability checks and recovery diagnostics.

Monado is the current open-source implementation to evaluate. Its Android documentation describes phone IMU-based 3DoF / Cardboard support, which is a plausible first target for phone viewers. Its compositor has GPU-driver requirements; the Vulkan path documents external-memory and external-semaphore FD extensions, while the OpenGL path has its own memory-object requirements. These must be checked on the target phone before committing to a backend.

Monado Android is not automatically a universal Quest runtime. Meta/Oculus APIs and proprietary extensions remain a separate compatibility project. The runtime must advertise only extensions it actually implements.

## Planned development sequence

1. Runtime discovery and APK preflight — implemented as the current foundation.
2. Native graphics capability probe on the target device.
3. Begin the graphics-bound session and implement a minimal wait/begin/acquire/wait/release/end frame loop with projection-layer submission.
4. Integrate a phone-viewer compositor and IMU-based 3DoF head tracking.
5. Add controller/action input and connect the existing QuestPhoneXR virtual-controller model.
6. Run a known OpenXR test APK and capture loader/runtime logs.
7. Only after a stable generic OpenXR path, investigate Meta/Quest API adaptation.

No arbitrary APK should be marked compatible based only on its engine or the presence of `libopenxr_loader.so`.
