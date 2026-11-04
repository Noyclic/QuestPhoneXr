# EndXR runtime diagnostics

Every Sandbox GLES surface generation runs the bundled OpenXR probe once. The report is saved in app-private storage at `files/diagnostics/endxr-openxr-latest.log` and in app external-files storage at `Android/data/com.noyclic.questphonexr/files/ci-diagnostics/endxr-openxr-latest.log`.

The Sandbox includes an always-visible **Диагностика EndXR** button in the bottom-left corner. It remains available when the settings panel is hidden and copies the complete report to the clipboard.

On failure, inspect `failure_stage`, `failure_api_call`, `failure_result_name`, `failure_result_hex`, and any cleanup details. EGL and GLES information includes current surface/config/context, vendor/version/extensions, GPU renderer, GL/GLSL version, texture limits and the last GLES error captured during texture allocation. Schema 4 submits 30 consecutive frames through one running OpenXR session, records how many of the three swapchain images were used, and records the RGBA pixel values read back from the left and right halves of the Sandbox EGL surface.

The probe calls the bundled EndXR runtime directly on the Sandbox GL thread. It renders a deterministic red/blue stereo test pattern into a GLES swapchain image, submits a two-view `XrCompositionLayerProjection`, and verifies that the runtime compositor copied each eye into the matching half of the active EGL window backbuffer. This proves repeated in-process projection-layer composition and frame lifecycle on the tested graphics context; it does not prove Android RuntimeBroker discovery, third-party game process startup, OpenXR Actions/controller interaction, physical headset presentation, or full Quest APK compatibility.

## CI artifact export

Android instrumentation tests export the diagnostic report and UI screenshots through `MediaStore.Downloads` into `Downloads/QuestPhoneXR-CI/` before the emulator test task finishes. The shared Downloads copy survives the test runner uninstalling both APK packages. The emulator smoke script pulls those files into the `QuestPhoneXR-CI-diagnostics` Actions artifact:

- `diagnostics/endxr-openxr-latest.log` — full schema 4 diagnostic report.
- `screenshots/mode-selection.png` — launcher mode selector.
- `screenshots/game-library.png` — VR game library.
- `screenshots/sandbox-diagnostics-button.png` — visible diagnostics control.
- `screenshots/emulator-final.png` — final emulator screen capture.

The smoke script treats missing report or UI screenshot exports as a CI failure when the instrumentation tests otherwise pass. This prevents a green run from silently omitting the diagnostic evidence.


## Current third-party game boundary

The game library shows the current runtime capability inventory:

- Verified in-process: loader negotiation, Android/GLES session setup, stereo views, swapchain lifecycle and projection-layer composition.
- Partial: head orientation only; the system reports `positionTracking=false`.
- Not implemented: OpenXR Action/Input functions (`xrCreateActionSet`, `xrCreateAction`, `xrSyncActions`, `xrGetActionState*`), controller poses/buttons/haptics, and `XR_EXT_hand_tracking`.
- Not verified: Android RuntimeBroker discovery and loading from a separate third-party application process.

An APK can contain OpenXR or Meta/Oculus markers and still be unplayable on the phone. Apps expecting joystick, trigger, grip, tracked controller poses or hand joints are not ready until those APIs are implemented and tested from an external client process.

The first game targets are kept distinct by build type. Open Saber has a separate phone-oriented Android Mobile Port page that advertises VR/SBS; this is the first build to inspect on the Redmi. Granny and Baldi entries point to Quest-specific builds and retain the Meta/Quest compatibility caveat.


## Android RuntimeBroker discovery probe

The diagnostics dialog now performs a read-only check of the Android OpenXR runtime service, its exported flag, the Khronos metadata keys, and the actual `libendxr_openxr.so` file under the service package's native library directory. It also checks whether the installable and system RuntimeBroker content providers are visible and whether either provider reports an active runtime for the device ABI.

The instrumentation suite saves this as `diagnostics/runtime-broker-probe.txt`. A passing service-discovery check is still not an external-game test: the separate client process must independently select/load EndXR, create its own OpenXR instance/session and submit a frame before third-party RuntimeBroker compatibility can be marked verified.
