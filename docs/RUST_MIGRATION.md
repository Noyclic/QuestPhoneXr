# GVXRS Rust migration — safety plan

## Non-negotiable compatibility boundary

The loader-facing OpenXR runtime is protected during the first migration stages.

The following files and contracts are frozen until a separate, reviewed migration stage:
- app/src/main/cpp/endxr_openxr_runtime.cpp
- app/src/main/cpp/endxr_openxr_runtime.hpp
- app/src/main/cpp/third_party/openxr/
- Android OpenXR runtime service metadata and manifest declarations
- the exported library name libendxr_openxr.so
- xrNegotiateLoaderRuntimeInterface and the OpenXR procedure table
- EGL/GLES session, swapchain, frame submission and Android RuntimeBroker behavior

No Rust code is allowed to replace or wrap OpenXR entry points in the initial stage.

## Stage 0 — baseline and isolation

- Keep the migration on feat/gvxrs-rust-core-migration, based on the current NextVRera/GVXRS development branch.
- Keep main and the existing feature branch unchanged.
- Preserve the current C++ implementation as the active implementation.
- Record the existing native C++ tests as the behavioral reference.

## Stage 1 — Rust parity core (current)

The gvxrs-core crate mirrors only the platform-independent lifecycle/frame-clock logic from endxr_runtime_core.cpp:
- runtime state transitions;
- configuration validation;
- frame index and predicted display timing;
- frame deadline and late-frame accounting;
- stop, loss, recovery and shutdown behavior.

This is a shadow implementation. It is not yet linked into the Android app and does not replace the C++ implementation. The Rust test suite must pass before any adapter is introduced.

## Stage 2 — differential verification

- Run the original C++ tests and Rust tests in CI.
- Add deterministic parity vectors that feed identical inputs into both implementations and compare state, errors, timing and counters.
- Exercise invalid transitions, timestamp overflow, frame token mismatch, loss/recovery and repeated start/stop cycles.
- Keep C++ as the production path until parity is demonstrated.

## Stage 3 — opt-in JNI adapter

Only after Stage 2:
- Build Rust as an Android arm64-v8a static library.
- Keep the existing JNI function names and Java/Kotlin signatures unchanged.
- Add a narrow C ABI adapter around the Rust core.
- Make the Rust-backed implementation opt-in for a test build first.
- Keep the original C++ implementation available as an immediate fallback.

## Stage 4 — runtime integration decision

The Rust lifecycle core may be enabled by default only after:
- native C++ reference tests pass;
- Rust unit and differential tests pass;
- Android arm64-v8a APK builds and packages both native libraries;
- the in-process OpenXR Sandbox probe passes on the same build;
- the external Khronos-loader diagnostic remains unchanged;
- a physical-device test confirms session/frame behavior.

The loader-facing OpenXR runtime remains C/C++ unless a later independent proposal proves that moving selected internals is safe and necessary. Any such proposal must preserve the OpenXR C ABI exactly and must be tested separately.

## Performance policy

Rust is not assumed to be faster by default. Benchmark the same workloads, compiler optimization level and device. Measure CPU time, allocations, memory use, frame-time variance and thermal behavior. Do not trade away frame pacing or compatibility for a theoretical language-level advantage.

## Rollback

Every migration step is isolated in its own commit. If a Rust build, test, JNI call or OpenXR probe regresses, disable the Rust adapter and return to the unchanged C++ implementation. Never remove the reference implementation in the same change that first enables Rust.
