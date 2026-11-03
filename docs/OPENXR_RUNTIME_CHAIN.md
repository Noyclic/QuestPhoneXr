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

## Device and Monado diagnostics

The explicit compatibility action now runs on a worker thread and combines:
- Android-reported device, ABI, memory, sensor and API information.
- Native Vulkan instance/device enumeration, selected extensions and external-handle queries.
- A temporary 1x1 EGL pbuffer context, trying GLES 3 first and GLES 2 only as fallback. The context is destroyed before the probe returns.
- Monado out-of-process package visibility, installed version, `libopenxr_monado.so`, IPC service declaration, OpenXR Runtime service action and Runtime service metadata.
- Khronos Runtime Broker discovery of the currently selected runtime.

The Monado package inspection is metadata-only. It does not bind `MonadoService`, start the runtime, or attempt an OpenXR instance.

The Vulkan preflight reports either an Android Hardware Buffer candidate or an FD-sharing candidate. It no longer treats the desktop FD extension pair as universally mandatory on Android. These are only candidate paths: the probe does not yet create an AHardwareBuffer image, test exact image-format/usage combinations, import the image into Monado, or submit a compositor frame.

The OpenXR Loader smoke test remains deliberately disabled. The previously observed crash occurred while the loader was creating an OpenXR instance and the Monado Java client attempted to call `IMonado.passAppSurface(Surface)` while `IMonado` was null. Repeating that call would risk reproducing the crash. The next step is to diagnose and correct the Monado Android Binder lifecycle before re-enabling it.

The Sandbox remains independent: no OpenXR loader initialization, Monado IPC connection, or session creation occurs during Sandbox startup or inside the GLSurfaceView render callback.

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

Monado is the current open-source implementation to evaluate. Its Android documentation describes phone IMU-based 3DoF / Cardboard support, which is a plausible first target for phone viewers. The installed Monado APK and Android graphics-sharing path are now checked separately from Loader initialization.

Monado Android is not automatically a universal Quest runtime. Meta/Oculus APIs and proprietary extensions remain a separate compatibility project. The runtime must advertise only extensions it actually implements.

## Planned development sequence

1. Runtime discovery and APK preflight — implemented as the current foundation.
2. Device Vulkan / EGL-GLES / Monado package preflight — implemented; results must be collected on target hardware.
3. Diagnose and correct Monado Android Binder service lifecycle and Surface hand-off.
4. Run a controlled OpenXR loader/instance probe after the Binder path is safe.
5. Begin a graphics-bound session and implement a minimal wait/begin/acquire/wait/release/end frame loop with projection-layer submission.
6. Integrate a phone-viewer compositor and IMU-based 3DoF head tracking.
7. Add controller/action input and connect the existing QuestPhoneXR virtual-controller model.
8. Run a known OpenXR test APK and capture loader/runtime logs.
9. Only after a stable generic OpenXR path, investigate Meta/Quest API adaptation.

No arbitrary APK should be marked compatible based only on its engine or the presence of `libopenxr_loader.so`.
