# EndXR runtime diagnostics

## Sandbox diagnostics

Every new Sandbox GLES surface generation runs the bundled OpenXR self-test once. The test runs on the Sandbox GL thread, saves its report to app-private storage at `files/diagnostics/endxr-openxr-latest.log` and to app external-files storage at `Android/data/com.noyclic.questphonexr/files/ci-diagnostics/endxr-openxr-latest.log`.

The Sandbox has two separate buttons in the lower-left corner:

- **Диагностика EndXR** copies the complete OpenXR diagnostic report to the clipboard. It remains visible when the settings panel is hidden. If the test has not finished yet, the app shows **«Диагностика ещё не запускалась»**.
- **Проверить OpenXR ввод** opens a small report showing the current left/right mapped input values. This checks the app's in-process JNI path only; it does not prove that a third-party game can read input.

There is also a small **OpenXR probe** status label above the diagnostics button. The full Sandbox status panel reports GLES surface state, EndXR core state, frame counters, phone sensor state, camera state, hand tracking rate and connected controllers.

The self-test creates a deterministic red/blue stereo pattern, submits a two-view projection layer through the bundled runtime and reads back pixels from the Sandbox EGL surface. The current diagnostic schema checks 30 consecutive frames. This verifies the in-process graphics path on that EGL context; it does not prove active Android RuntimeBroker selection, third-party game rendering, physical headset presentation or Quest APK compatibility.

## Interpreting the separate checkpoints

Do not combine these stages into one pass/fail result:

1. **Sandbox self-test** — the bundled runtime and EGL path work inside QuestPhoneXR.
2. **Service discovery** — another package can see EndXR's exported Android runtime service and its metadata/library.
3. **Input IPC permission** — the separate diagnostic client has the required signature-level permission and can read the provider snapshot.
4. **Official loader initialization** — the Khronos Android loader initializes in the separate client package.
5. **Active-runtime selection / instance** — the Android RuntimeBroker selects EndXR and `xrCreateInstance` succeeds.
6. **External GLES session** — the separate process creates an HMD system/session, swapchain and frame lifecycle.
7. **Game compatibility** — an actual title renders and accepts useful input. This has not yet been demonstrated on the Redmi Note 13 4G.

A service can be discoverable while no active runtime is selected. The preflight mirrors Khronos Loader precedence: it checks the installable RuntimeBroker first and checks the system Broker only when the installable Broker has no active-runtime rows. It also checks the ABI selected for the installed game instead of assuming the NextVRera process ABI. The predicted package/library candidate is still only a metadata and file-existence check; it does not prove that the loader can `dlopen` the library. A successful `xrCreateInstance` still does not prove that an actual game will render or receive controls.

## Protected input IPC

The read-only provider exposes controller values, phone head orientation and approximate MediaPipe palm-pose values at:

`content://com.noyclic.questphonexr.endxrinput/controller_state`

It now requires the signature permission `com.noyclic.questphonexr.permission.READ_INPUT_SNAPSHOT`. The paired `runtime-client-probe` APK can read it because the main APK and probe APK are built and signed in the same CI run. Install both artifacts from the **same Actions run**; do not mix debug APKs from different runs.

Third-party game APKs do not receive blanket access. The game library's **Запустить через NextVRera + EndXR** path asks for explicit consent, grants read-only access to the exact controller URI for the selected installed package, and binds the stored approval to its signing certificate. The regular Android launch button does not grant IPC access. Do not remove the signature permission to make a test game work.

A successful provider query does not prove a live camera producer. The
external probe now reports controller, head-sensor and hand-pose sample ages
plus input publication state:
- LIVE_HANDS — one or both camera hand poses are fresh.
- LIVE_CONTROLLERS — fresh controller rows are active.
- PUBLISHING_NO_HANDS — the producer is refreshing samples, but no hand pose is currently detected.
- HEAD_SENSOR_ONLY — phone orientation is fresh without camera hand/controller data.
- STALE — all input sources have expired; start **Фоновое отслеживание рук для Quest-игр** in Sandbox and leave its foreground notification running before switching to the external client.
- NO_ACTIVE_INPUT — no active source is currently visible to the external package.

The 300 ms sample lease is deliberate: old controller values are neutralized
instead of being replayed as if they were live input. Sample ages use Android
elapsed realtime, so they can be compared between processes on the same device.

The Android emulator smoke test also queries the provider as the untrusted `adb shell` UID and requires Android to deny access. The CI artifact includes `diagnostics/ipc-access-control.txt` and the raw denial output in `diagnostics/ipc-unauthorized-shell.txt`.

## CI artifact export

Android instrumentation tests export the diagnostic report and UI screenshots through `MediaStore.Downloads` into `Downloads/QuestPhoneXR-CI/` before the emulator test task finishes. The emulator smoke script pulls these files into the `QuestPhoneXR-CI-diagnostics` Actions artifact. It includes:

- `diagnostics/endxr-openxr-latest.log` — full Sandbox OpenXR report.
- `diagnostics/runtime-broker-probe.txt` — installable/system broker discovery.
- `diagnostics/external-client-probe.txt` — separate-package service and input IPC report.
- `diagnostics/openxr-loader-client-probe.txt` — official Khronos loader report.
- `diagnostics/ipc-access-control.txt` — negative authorization check for an untrusted shell client.
- `screenshots/mode-selection.png` — launcher mode selector.
- `screenshots/game-library.png` — VR game library.
- `screenshots/sandbox-diagnostics-button.png` — visible diagnostics control.
- `screenshots/runtime-readiness.png` — runtime capability screen.
- `screenshots/emulator-final.png` — final emulator screen capture.

The workflow treats missing diagnostic reports or required UI screenshot exports as a CI failure when the instrumentation tests otherwise pass.

## NextVRera-managed game launch

For an installed APK that the static analyser classifies as an OpenXR candidate, the library provides **Запустить через NextVRera + EndXR**. This is an orchestration/preflight path, not an alternate OpenXR loader:

1. NextVRera checks which runtime the Khronos Loader is likely to obtain from the Broker for that game's ABI.
2. If EndXR is not the likely candidate, NextVRera opens the official installable RuntimeBroker settings and rechecks the selection when the user returns.
3. The user explicitly approves the exact read-only input URI for that package; the grant is tied to the package signing certificate.
4. NextVRera asks for camera permission if needed, starts the saved foreground tracking configuration, and waits for a fresh MediaPipe frame or reports that tracking did not become ready.
5. Only then does it request Android to launch the installed game. The diagnostic records the selected Broker candidate, target ABI, IPC grant and camera state.

This path improves launch preparation and prevents an obvious wrong-runtime launch, but it cannot force a runtime choice inside Android or make a proprietary Meta/Oculus-only application use OpenXR. Android still launches the game as a separate package/process. The game must actually use the Khronos Android OpenXR Loader and the extensions/graphics backend EndXR supports. A Broker candidate prediction is not proof of a successful `dlopen`, `xrCreateInstance`, or game frame.

## Physical-device validation on the Redmi Note 13 4G

1. Open the latest successful **Android CI** run and download both `QuestPhoneXR-debug-apk` and `QuestPhoneXR-external-client-probe-apk` from that same run.
2. Install both APKs on the Redmi. The matching signing certificate is required for the probe's signature permission.
3. Open QuestPhoneXR, enter **Sandbox**, and wait for the **OpenXR probe** status to appear. Tap **Диагностика EndXR** to copy the full in-process report. The button is in the lower-left corner; it stays visible when settings are hidden.
4. To test camera input publication, enable **Фоновое отслеживание рук для Quest-игр** in the Sandbox settings, grant camera permission, and leave the persistent notification running while switching to another app.
5. If the phone has a compatible Khronos or vendor Android RuntimeBroker, use it to select **EndXR OpenXR Runtime**. If no RuntimeBroker provider exists, record that fact; service visibility alone cannot prove runtime selection.
6. Open **EndXR External Client Probe** and copy the entire report. The important stages are signature permission / provider query, `xrInitializeLoaderKHR`, `xrCreateInstance`, `xrGetSystem(HMD)`, `external_xrCreateSession`, swapchain image operations and `external_xrEndFrame`.
7. Send both reports and note whether camera tracking stayed active in the background, whether the phone became hot, and any visible errors.

The probe is a diagnostic client, not a real game. Its successful session/frame test is a stronger interoperability checkpoint than service discovery, but it still submits no projection layer to a headset and does not establish Quest game compatibility. A phone without an installable or system RuntimeBroker cannot prove active-runtime selection through the broker route.

See [External OpenXR Loader validation](OPENXR_LOADER_VALIDATION.md), [IPC security](IPC_SECURITY.md) and [EndXR runtime status](ENDXR_RUNTIME.md) for details.
