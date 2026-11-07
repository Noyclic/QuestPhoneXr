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

The gvxrs-core crate mirrors only platform-independent logic that is currently outside the loader-facing OpenXR engine:

- the app-side lifecycle/frame-clock helper in `endxr_runtime_core.cpp` (this helper is called through JNI; it is not the OpenXR Loader runtime itself);
- pure controller-axis sanitization and hand/head quaternion validation currently performed by input setter functions at the bottom of `endxr_openxr_runtime.cpp`.

The crate also exposes a narrow C ABI through `gvxrs-core/include/gvxrs_core.h`. It uses integer handles and plain by-value C structs; no Rust pointers, strings, vectors or references cross the boundary.

The tracking module is a shadow port only. The production OpenXR runtime continues to execute its original C++ implementation.

The lifecycle module covers:
- runtime state transitions;
- configuration validation;
- frame index and predicted display timing;
- frame deadline and late-frame accounting;
- stop, loss, recovery and shutdown behavior.

This is a shadow implementation. Neither module is linked into the Android app yet, and neither replaces the C++ implementation. Host-side C++ tests exercise the ABI, while CI also cross-compiles a separate Android `arm64-v8a` static archive and links it into a test-only shared library with the Android NDK C++ linker. The link smoke target checks C ABI struct layout and exported symbols under `--no-undefined`. It is not linked into an APK or called from JNI. The smoke entry point is compile/link validated only; CI does not execute that Android shared library in an emulator or on a phone.

## Stage 2 — differential verification and Android cross-build

- Run the original C++ tests and Rust tests in CI.
- Build the Rust static library for the CI host and test its C ABI from C++.
- Compare the existing C++ lifecycle helper and the Rust lifecycle helper with identical operations, including frame deadlines, invalid transitions, timestamp overflow, loss and recovery.
- Test tracking sanitization through the same C ABI intended for future native integration.
- Build the dependency-free crate for `aarch64-linux-android` using Android NDK 27.2.12479018 and API level 29, matching the app's current minimum SDK.
- Inspect the resulting archive for AArch64 ELF objects and required exported C ABI symbols.
- Link the archive into a CI-only Android ARM64 shared library using the NDK C++ linker and `--no-undefined`; check C ABI struct sizes/offsets and the exported smoke-test entry point.
- Add deterministic parity vectors that feed identical inputs into both implementations and compare state, errors, timing and counters.
- Exercise invalid transitions, timestamp overflow, frame token mismatch, loss/recovery and repeated start/stop cycles.
- Keep C++ as the production path until parity is demonstrated.

The cross-build and test-only shared-library link prove Android ARM64 compilation, static linking and compile-time C ABI layout checks. CI does not execute the generated Android shared library. These checks do not prove APK packaging, Java/Kotlin JNI invocation, runtime behavior on the phone, or physical-device compatibility.

## Stage 3 — opt-in JNI adapter

Only after Stage 2:
- Link the verified Android arm64-v8a archive into a separate test-only native target.
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

Every migration step is isolated in its own commit. If a Rust build, test, JNI call or OpenXR probe regresses, disable the Rust adapter and return to the unchanged C++ implementation. Never remove the reference implementation in the same change that first enables it.

## Current shadow-port inventory

| Rust module | Existing C++ behavior mirrored | Runtime integration |
| --- | --- | --- |
| `src/lib.rs` | App-side lifecycle/frame-clock helper from `endxr_runtime_core.cpp` | Not linked into Android |
| `src/tracking.rs` | Controller axis clamping; hand-pose validation/quaternion normalization; head quaternion normalization | Not linked into Android |
| `src/core_ffi.rs` | Integer-handle C ABI for lifecycle operations | Host parity tests and Android ARM64 test-only shared-library link smoke |
| `include/gvxrs_core.h` | C declarations and fixed-width POD structs | Not included by production CMake |

The CI host builds the Rust static library, calls its C ABI from C++, and runs a differential harness against the original C++ lifecycle helper. A separate C++ test covers the tracking sanitizer ABI. CI now also cross-compiles an Android arm64-v8a archive, inspects its ELF objects and exported symbols, and links it into a separate test-only shared library with the NDK C++ linker and `--no-undefined`. These checks do not call the private input setters inside the OpenXR shared library, package Rust into the app APK, invoke the Rust path through JNI, run the smoke function on a device, or prove faster execution.

## Explicit non-migration

The following remains C++ and is not linked against the Rust static library:
- `endxr_openxr_runtime.cpp` and its OpenXR function table;
- loader negotiation and the exported `xrNegotiateLoaderRuntimeInterface` symbol;
- EGL/GLES context and swapchain ownership;
- OpenXR handles, action/session registries and RuntimeBroker metadata;
- the Android JNI bridge.

Only a later PR, after parity CI passes, may add an opt-in Android arm64-v8a adapter. It must keep the original C++ path selectable and rerun the same in-process Sandbox probe plus the external Khronos-loader tests.
