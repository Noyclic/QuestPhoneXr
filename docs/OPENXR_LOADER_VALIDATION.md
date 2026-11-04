# External OpenXR Loader validation

## What this module checks

`runtime-client-probe` is a separate Android application package
(`com.noyclic.xrclientprobe`). It is not loaded inside the QuestPhoneXR process.
It first checks Android service discovery and then calls the official Khronos
Android OpenXR Loader from that independent process.

The probe records these stages:

1. `xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", ...)`
   resolves the Android loader initialization entry point.
2. `xrInitializeLoaderKHR` receives the current JavaVM and Activity/Context.
3. The loader attempts to enumerate runtime extension properties.
4. The loader attempts `xrCreateInstance` with
   `XR_KHR_android_create_instance`.
5. If an instance is created, the probe requests an HMD system, reads system
   properties, and checks for `XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO`.
6. If the runtime advertises `XR_KHR_opengl_es_enable`, the probe creates an
   independent EGL ES 3 pbuffer context, queries graphics requirements, and
   attempts `xrCreateSession` / `xrBeginSession`.
7. It creates a small GLES swapchain, enumerates its images, attempts
   acquire/wait/release, then runs one `xrWaitFrame` / `xrBeginFrame` /
   `xrEndFrame` cycle with zero composition layers.
8. It tears down the swapchain, session, EGL context and instance.

The native loader is supplied by the Khronos
`org.khronos.openxr:openxr_loader_for_android` AAR. The probe does not contain
a local imitation of the loader or RuntimeBroker.

## Reading the result

- `xrInitializeLoaderKHR call=XR_SUCCESS` means the official Android loader
  initialized in the separate client package.
- `xrCreateInstance=XR_SUCCESS` means the loader found and loaded an active
  runtime and successfully negotiated an OpenXR instance.
- `xrGetSystem(HMD)=XR_SUCCESS` means that runtime exposed an HMD system to
  this client process.
- `primary_stereo_supported=YES` means the runtime advertised the primary
  stereo view configuration.
- `external_xrCreateSession=XR_SUCCESS` means the runtime accepted a real
  EGL-bound session from the separate client process.
- Successful `external_xrCreateSwapchain`, image acquire/wait/release and
  frame calls verify progressively more of the independent GLES lifecycle.
  The probe submits no projection layer and does not prove headset presentation.

If instance creation returns `XR_ERROR_RUNTIME_UNAVAILABLE`, inspect the
RuntimeBroker report. Service visibility alone does not select an active
runtime. Android obtains the active runtime from either the official
installable Khronos RuntimeBroker content provider or a system RuntimeBroker
provided by the device vendor. QuestPhoneXR only advertises its runtime service
and queries the documented providers; it does not impersonate or ship a
replacement for those brokers.

A successful `xrCreateInstance` is a major interoperability checkpoint, but
it is not proof of game compatibility. The probe now goes further and attempts
an independent GLES session, swapchain and one frame lifecycle. It does not
submit a projection layer or present a rendered image to a headset. EndXR's
Action API is still partial: Sandbox input is currently visible only inside
the QuestPhoneXR process, while external-game controller transport, controller
poses, haptics, 6DoF tracking and Meta/Oculus-specific services remain
incomplete. A real game may also require Vulkan, additional extensions or
vendor APIs.

## CI and physical-device test

The GitHub Actions workflow installs QuestPhoneXR and the separately signed
debug client APK on an Android 15 emulator. It saves the service report,
RuntimeBroker report, Khronos loader report, OpenXR Sandbox report, screenshots
and instrumentation results in the `QuestPhoneXR-CI-diagnostics` artifact.

The external client probe also queries QuestPhoneXR's read-only input snapshot
provider from its separate package. This confirms that the IPC endpoint is
visible across the Android application boundary; it does not prove that input
continues to be published after Sandbox pauses.

The emulator currently has no Android RuntimeBroker provider, so the
`xrCreateInstance` result may correctly report that no runtime is selected.
The CI assertion is that the official loader initializes and returns a
diagnostic result without crashing; it does not force an unavailable runtime
to appear.

For a phone test, install both artifacts, then use the Khronos Runtime Broker
to select **EndXR OpenXR Runtime** as the active runtime before launching the
probe. The loader does not select a runtime itself; the broker owns that
choice. If Monado or another runtime is selected, the probe will test that
runtime instead of EndXR.

Install both artifacts:

- `QuestPhoneXR-debug-apk`
- `QuestPhoneXR-external-client-probe-apk`

Open **EndXR External Client Probe** and copy or photograph the complete
report. After selection, the key checkpoints are `xrCreateInstance`,
`external_xrCreateSession`, swapchain image operations and
`external_xrEndFrame`. The report must be interpreted alongside the broker state. A phone
that has no installable or system RuntimeBroker cannot prove runtime selection
just because Android can see EndXR's exported service.
