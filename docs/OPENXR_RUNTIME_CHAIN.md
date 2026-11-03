# OpenXR runtime chain

## Current state

QuestPhoneXR uses the Khronos Android OpenXR Loader 1.1.65 and an installable Monado out-of-process runtime. The app is still an experimental phone-XR host, not a complete Quest/Meta runtime.

The active-runtime discovery layer:
- selects the loader ABI matching the current app process;
- queries the system and installable Khronos Runtime Broker authorities;
- reads the selected runtime package, native library directory and shared-library filename;
- checks whether the reported library file exists;
- does not itself load the runtime or create an OpenXR instance.

The Android manifest already declares the Khronos OpenXR permissions and package/provider/service queries needed by this discovery path. Do not add duplicate declarations without a new concrete requirement.

## What the Monado diagnostics actually test

The user-triggered diagnostic runs in the dedicated Android process :xr_diagnostics. Its report performs these checks in order:

1. Runtime Broker discovery and ABI/library-path inspection.
2. An independent dlopen + xrNegotiateLoaderRuntimeInterface probe against the candidate shared library. It checks that the library can be opened, negotiation succeeds, versions are compatible, and xrGetInstanceProcAddr can return the xrCreateInstance function pointer. It does not call xrInitializeLoaderKHR, reproduce the Khronos manifest-selection path, invoke runtime xrCreateInstance, or create a session.
3. Monado APK/service metadata inspection and a Binder handshake. BOUND means Android bound the declared service and the returned Binder descriptor matched org.freedesktop.monado.ipc.IMonado. It does not prove that the client-side connect() transaction succeeded, that passAppSurface(Surface) supplied a valid Surface, or that Monado's native runtime mainloop started.
4. A real Khronos Loader smoke sequence in the same :xr_diagnostics process: xrInitializeLoaderKHR, xrEnumerateInstanceExtensionProperties, then xrCreateInstance and (only if instance creation succeeds) xrGetSystem.

The historical IMonado == null / passAppSurface(Surface) crash is still a relevant risk to investigate, but the current Binder handshake does not reproduce that RPC path, and the old crash is not proof that it is the present cause of the instance-creation failure.

## Interpreting the current xrCreateInstance: -51 result

The 2026-11-03 device report showed:
- the Broker selected org.freedesktop.monado.openxr_runtime.out_of_process for arm64-v8a;
- the candidate shared library existed;
- the independent direct library probe completed negotiation successfully;
- the Binder descriptor handshake returned BOUND;
- the Khronos Loader smoke sequence returned success from xrInitializeLoaderKHR and xrEnumerateInstanceExtensionProperties, listed 64 extensions, and then returned -51 from xrCreateInstance.

The key distinction is that the last two calls are made sequentially by the same native probe in the same process. In the pinned Khronos Loader 1.1.65 implementation, a non-layer-specific extension enumeration calls RuntimeInterface::LoadRuntime; a successfully loaded RuntimeInterface is retained. A later xrCreateInstance reuses that interface and the Loader's runtime create path obtains xrCreateInstance through the runtime's xrGetInstanceProcAddr and forwards the call. This is documented by the tagged Loader design and implementation:
https://registry.khronos.org/OpenXR/specs/1.1/loader.html

Consequently, in this exact same-process sequence, a successful 64-extension enumeration is strong evidence that Khronos Loader already found, loaded, negotiated and queried the selected runtime. The subsequent -51 should not automatically be diagnosed as a new failure to discover the runtime manifest or library. Because this probe enables zero API layers, the leading explanation is that Monado's xrCreateInstance path returned XR_ERROR_RUNTIME_UNAVAILABLE. The precise internal reason still needs Monado/Loader logs or instrumentation at the Loader-to-runtime call boundary; the current report does not reveal which Monado initialization step returned it.

The xrGetSystem: -6 entry following a failed xrCreateInstance is not a second independent failure: there is no valid XrInstance to pass to xrGetSystem.

This diagnosis applies only when extension enumeration and instance creation came from the same build and the same process sequence shown above. The independent dlopen test by itself does not prove what the Khronos Loader selected.

## Process separation matters

The APK declares:
- MonadoDiagnosticsActivity in :xr_diagnostics;
- OpenXrRuntimeProbeActivity in :xr_runtime_probe.

These are separate Android processes. Each has its own native globals and Khronos Loader state. The successful extension enumeration from the diagnostics process is not a proof that the persistent GLES activity has already created its own instance. The persistent session has its own Loader initialization and its own extension-enumeration / instance-creation sequence.

The persistent GLES path proceeds to xrGetSystem, GLES graphics requirements, EGL binding, xrCreateSession, swapchain creation and frame submission only after xrCreateInstance succeeds. The current failure report does not show that those later stages have been reached on the Redmi.

## Monado Android IPC and Surface path

Monado's Android IPC design uses the IMonado Binder service, an IPC connection setup that passes a socket descriptor, and a Surface hand-off. The runtime's native mainloop is tied to receiving a valid Surface; merely binding the service is not equivalent to completing that lifecycle. See the upstream Monado IPC design and Android API documentation:
- https://monado.freedesktop.org/monado-ipc-design-and-implementation.html
- https://monado.freedesktop.org/class_monado_1_1_monado_impl.html
- https://monado.freedesktop.org/class_monado_1_1_monado_view.html

This makes the service connection, connect(), Surface acquisition/attachment, passAppSurface(), Activity lifetime and native mainloop state important next diagnostic targets. However, the current evidence does not yet prove which of these is failing, or that the historical null-Binder crash is recurring.

## CI and reproducibility

The Android CI workflow builds the app, runs JVM unit tests and validates the APK payload. Its optional emulator smoke test uses an API 30 x86_64 emulator without the Monado runtime installed, so it cannot validate this Redmi's Monado integration or Quest APK compatibility.

The Monado workflow builds the out-of-process APK from upstream main and records the exact upstream source revision in the MONADO_COMMIT.txt artifact. The source reference in runtime/monado-source.txt is main, not a pinned commit. To map the device's reported version string 7670e0b to exact source, compare it with the build artifact's recorded commit; the version string alone has not yet been independently verified as that exact commit.

No current CI result is used as evidence that runtime creation works on the physical device.

## Next technical steps

1. Capture Loader and Monado logs for the same :xr_diagnostics process while reproducing the failing xrCreateInstance call.
2. Instrument the Loader-to-runtime call boundary, or build a carefully isolated direct-runtime experiment, to confirm the exact source and internal reason for -51.
3. Trace Monado's Android connect() and Surface hand-off lifecycle, including whether the Activity/Surface is ready when the runtime needs it.
4. Once xrCreateInstance succeeds, validate xrGetSystem, GLES graphics binding, xrCreateSession, swapchain import and sustained frame submission.
5. Only after generic OpenXR works reliably, evaluate the separate compatibility work required for Meta/Quest-specific APIs and APKs.

A successful OpenXR instance or Monado APK installation alone does not mean arbitrary Quest APKs are compatible. Quest/Meta proprietary APIs and extensions remain a separate engineering task.
