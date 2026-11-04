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
