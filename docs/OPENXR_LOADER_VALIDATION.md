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
provided by the device vendor. The Khronos installable Broker is a separate
working-group application. It must not be bundled as a private copy inside
QuestPhoneXR or replaced with a provider claiming its authority.

For a phone / plug-in-viewer setup:

1. Install the official [OpenXR Runtime Broker from Google Play](https://play.google.com/store/apps/details?id=org.khronos.openxr.runtime_broker).
2. Return to QuestPhoneXR Sandbox and tap **Выбор OpenXR Runtime**.
3. In the Broker's runtime list, select **EndXR OpenXR Runtime**. If more than one runtime is installed, the user selection determines which runtime becomes active.
4. Return to Sandbox. QuestPhoneXR automatically reruns its in-process loader diagnostic and refreshes the Broker/provider report.
5. Launch **EndXR External Client Probe** separately to verify that the official Khronos loader in a different APK can create an instance and proceed through system/session/swapchain/frame calls.

If the Broker is already installed, the Sandbox button opens its settings screen. If it is missing, the button opens the official Play Store listing and can copy the listing URL. A device without Google Play may require the official Khronos-signed APK from a supported market/source; do not use random repackaged Broker APKs.

The Android 15 CI emulator used by this repository does not include an installable or system RuntimeBroker. Its expected `XR_ERROR_RUNTIME_UNAVAILABLE` is therefore a broker-selection limitation, not proof that the EndXR library failed to load. Physical-device selection and independent client loading remain separate acceptance checks.

A successful `xrCreateInstance` is a major interoperability checkpoint, but
it is not proof of game compatibility. The probe now goes further and attempts
an independent GLES session, swapchain and one frame lifecycle. It does not
submit a projection layer or present a rendered image to a headset. EndXR's
Action API is still partial: the paired same-signed diagnostic client can read
a snapshot in its separate process, but ordinary third-party game APKs are
blocked by the signature permission until a user-controlled package-specific
authorization flow exists. The current hand poses are estimates, not tracked
controller poses; haptics, 6DoF tracking and Meta/Oculus-specific services
remain incomplete. A real game may also require Vulkan, additional extensions
or vendor APIs.

## CI and physical-device test

The GitHub Actions workflow installs QuestPhoneXR and the separately signed
debug client APK on an Android 15 emulator. It saves the service report,
RuntimeBroker report, Khronos loader report, OpenXR Sandbox report, screenshots
and instrumentation results in the `QuestPhoneXR-CI-diagnostics` artifact.

The external client probe also queries QuestPhoneXR's read-only input snapshot
provider from its separate package. The provider enforces a signature-level
read permission, so install the main APK and probe APK from the **same CI
workflow run**; mixing debug artifacts from different runs can fail the
signature check. A passing query confirms the protected IPC endpoint is
accessible to the paired diagnostic package; it does not prove that input
continues to be published after Sandbox pauses. See [IPC security](IPC_SECURITY.md).

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
