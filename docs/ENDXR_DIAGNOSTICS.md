# EndXR runtime diagnostics

Every Sandbox GLES surface generation runs the bundled OpenXR probe once. The report is saved in app-private storage at `files/diagnostics/endxr-openxr-latest.log` and in app external-files storage at `Android/data/com.noyclic.questphonexr/files/ci-diagnostics/endxr-openxr-latest.log`.

The Sandbox includes a **Скопировать полную диагностику EndXR** button that copies the complete report to the clipboard.

On failure, inspect `failure_stage`, `failure_api_call`, `failure_result_name`, `failure_result_hex`, and any cleanup details. EGL and GLES information includes current surface/config/context, vendor/version/extensions, GPU renderer, GL/GLSL version, texture limits and the last GLES error captured during texture allocation. Schema 3 also records the RGBA pixel values read back from the left and right halves of the Sandbox EGL surface.

The probe calls the bundled EndXR runtime directly on the Sandbox GL thread. It renders a deterministic red/blue stereo test pattern into a GLES swapchain image, submits a two-view `XrCompositionLayerProjection`, and verifies that the runtime compositor copied each eye into the matching half of the active EGL window backbuffer. This proves in-process projection-layer composition on the tested graphics context; it does not prove Android RuntimeBroker discovery, third-party game process startup, OpenXR Actions/controller interaction, physical headset presentation, or full Quest APK compatibility.
