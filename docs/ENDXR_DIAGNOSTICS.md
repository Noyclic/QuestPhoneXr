# EndXR runtime diagnostics

Every Sandbox GLES surface generation runs the bundled OpenXR probe once. The report is saved in app-private storage at `files/diagnostics/endxr-openxr-latest.log` and in app external-files storage at `Android/data/com.noyclic.questphonexr/files/ci-diagnostics/endxr-openxr-latest.log`.

The Sandbox includes a **Скопировать полную диагностику EndXR** button that copies the complete report to the clipboard.

On failure, inspect `failure_stage`, `failure_api_call`, `failure_result_name`, `failure_result_hex`, and any cleanup details. EGL and GLES information includes current surface/config/context, vendor/version/extensions, GPU renderer, GL/GLSL version, texture limits and the last GLES error captured during texture allocation. The report also includes Android device/API, app version, surface generation, sensor/camera/hand-tracking/controller state and EndXR Core frame counters.

The probe calls the bundled EndXR runtime directly on the Sandbox GL thread. It does not prove Android RuntimeBroker discovery, third-party game process startup, OpenXR Actions/controller interaction, or projection-layer composition. It submits an empty frame and checks teardown; a pass is not full Quest APK compatibility.
