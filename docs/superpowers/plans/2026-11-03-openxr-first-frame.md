# OpenXR First Frame Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Safely prove that Monado can receive a QuestPhoneXR Activity Surface, create a GLES OpenXR session, and accept one submitted stereo projection frame.

**Architecture:** Keep Khronos Loader and Monado's own Android IPC client as the only connection implementation. Run a one-shot GLES probe in a dedicated Android process and GL thread, parse its stage/result report in Kotlin, and expose it from the game library.

**Tech Stack:** Kotlin, Android Activity/GLSurfaceView, OpenXR 1.1 Android loader, C++17 JNI, OpenGL ES 3, JUnit 4.

**Spec:** docs/runtime/OPENXR_FIRST_FRAME_DESIGN.md

## Global Constraints
- Keep Monado enabled; do not replace its AIDL/socket-pair protocol with a parallel implementation.
- Never call Monado blockingConnect/OpenXR initialization on the Android UI thread.
- The probe Activity must run in :xr_runtime_probe so native failures do not terminate the main app process.
- Keep the live Activity available during the Monado Surface handoff.
- Do not claim runtime success unless xrEndFrame successfully submits a two-view projection layer.
- Keep the existing Sandbox and hand-tracking paths unchanged in this milestone.

## Review Focus
- UI-thread invocation: the probe must only run on its GL worker thread.
- Surface lifecycle loss: report a precise failure and never dereference a missing Activity/Surface in QuestPhoneXR code.
- Runtime never reaches READY: bounded wait and explicit timeout result; no infinite wait.
- Stereo array texture mismatch: allocate two array layers and attach the correct layer for each eye.
- Partial failures: release acquired swapchain images and destroy OpenXR/EGL resources in reverse order.

---

### Task 1: Structured OpenXR probe result

**Files:**
- Create: app/src/main/java/com/noyclic/questphonexr/runtime/OpenXrProbeReport.kt
- Create: app/src/test/java/com/noyclic/questphonexr/runtime/OpenXrProbeReportTest.kt
- Modify: app/src/main/java/com/noyclic/questphonexr/runtime/OpenXrGlesSessionProbe.kt

**Interfaces:**
- Produces OpenXrProbeReport.parse(json: String): OpenXrProbeReport with status, optional xrResult, and message; toDisplayText() formats the report.
- Status FIRST_FRAME_SUBMITTED_GLES is the only success state for this milestone.

- [ ] Write tests for success parsing, failing XrResult parsing, and malformed JSON.
- [ ] Run the focused JUnit tests and observe the expected RED result.
- [ ] Implement the parser and report formatter.
- [ ] Run focused tests and the complete unit-test suite.
- [ ] Commit the structured report.

### Task 2: Real one-frame GLES OpenXR submission

**Files:**
- Modify: app/src/main/cpp/openxr_loader_probe.cpp

**Interfaces:**
- Keep the JNI entry point OpenXrGlesSessionProbe.nativeProbeSession(Activity): String.
- Native result must include a precise stage status and XrResult.

- [ ] Extend the current smoke probe to create a two-layer stereo swapchain.
- [ ] Poll session events with a bounded deadline and begin only after READY.
- [ ] Create a LOCAL reference space, wait/begin a frame, locate both views, acquire/wait the image, clear both array layers, release the image, and submit a projection layer with xrEndFrame.
- [ ] Request session exit and perform best-effort reverse-order cleanup on every path.
- [ ] Build the Android debug APK and inspect native compiler output.
- [ ] Commit the native first-frame implementation.

### Task 3: Isolated Android runtime probe screen

**Files:**
- Create: app/src/main/java/com/noyclic/questphonexr/runtime/OpenXrRuntimeProbeActivity.kt
- Modify: app/src/main/AndroidManifest.xml
- Modify: app/src/main/java/com/noyclic/questphonexr/catalog/GameLibraryActivity.kt

**Interfaces:**
- Launch the probe screen from the OpenXR Runtime panel.
- Run OpenXrGlesSessionProbe.inspectReport(Activity) only from the GLSurfaceView GL thread.
- The Activity is declared with android:process=:xr_runtime_probe and is not exported.

- [ ] Add a one-shot GL-thread execution guard and visible stage/result output.
- [ ] Add the isolated Activity manifest entry.
- [ ] Add a game-library button to launch the probe.
- [ ] Build the APK and verify the Activity/process manifest metadata.
- [ ] Commit the isolated probe screen.

### Task 4: Verification and handoff

**Files:**
- Modify: docs/runtime/OPENXR_FIRST_FRAME_DESIGN.md

- [ ] Run the full Android APK workflow and confirm the exact head SHA completed successfully.
- [ ] Verify the latest workflow artifact exists.
- [ ] Document that hardware execution still requires the user to launch the probe on the Redmi and share the resulting stage report.