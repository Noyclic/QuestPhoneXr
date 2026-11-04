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
6. The instance is destroyed. This probe does not create a session, bind GLES,
   submit a projection layer, or render a frame.

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

If instance creation returns `XR_ERROR_RUNTIME_UNAVAILABLE`, inspect the
RuntimeBroker report. Service visibility alone does not select an active
runtime. Android obtains the active runtime from either the official
installable Khronos RuntimeBroker content provider or a system RuntimeBroker
provided by the device vendor. QuestPhoneXR only advertises its runtime service
and queries the documented providers; it does not impersonate or ship a
replacement for those brokers.

A successful `xrCreateInstance` is a major interoperability checkpoint, but
it is not proof of game compatibility. A real application may still require
OpenXR Actions, controller poses, haptics, extensions, a graphics session,
swapchain images, frame submission, tracking, or Meta/Oculus-specific services.

## CI and physical-device test

The GitHub Actions workflow installs QuestPhoneXR and the separately signed
debug client APK on an Android 15 emulator. It saves the service report,
RuntimeBroker report, Khronos loader report, OpenXR Sandbox report, screenshots
and instrumentation results in the `QuestPhoneXR-CI-diagnostics` artifact.

The emulator currently has no Android RuntimeBroker provider, so the
`xrCreateInstance` result may correctly report that no runtime is selected.
The CI assertion is that the official loader initializes and returns a
diagnostic result without crashing; it does not force an unavailable runtime
to appear.

For a phone test, install both artifacts:

- `QuestPhoneXR-debug-apk`
- `QuestPhoneXR-external-client-probe-apk`

Open **EndXR External Client Probe** and copy or photograph the complete
report. The report must be interpreted alongside the broker state. A phone
that has no installable or system RuntimeBroker cannot prove runtime selection
just because Android can see EndXR's exported service.
