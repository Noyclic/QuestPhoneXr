# GVXRS Legacy — Gear VR compatibility design

## Current status

GVXRS Legacy (historical code name EndGVR) is a separate Gear VR compatibility
track. It must not be implemented by changing the GVXRS Mobile OpenXR runtime.

The repository now has two first-stage pieces:

- The Kotlin APK evidence classifier distinguishes explicit Samsung Gear VR
  markers from generic legacy Oculus Mobile SDK evidence. This is static
  triage only; it does not prove that an APK can run.
- The native C++ VrModeCoordinator models the conditions for entering and
  leaving a legacy mobile-VR mode. Host C++ CI tests cover lifecycle ordering,
  activity pause/resume, surface loss/recreation, stop during an in-flight
  enter, backend loss and fail-closed error recovery.

The coordinator is intentionally not linked into the Android APK and is not
called through JNI yet. It does not call VrApi, EGL or Android. It is a tested
native foundation, not a working Gear VR runtime.

## Why lifecycle is the first native step

Legacy mobile VR is more than drawing two camera views. The old VrApi lifecycle
is coupled to Android activity and window-surface state:

- VrApi documentation requires entering VR mode after initialization while
  the application is resumed and has a valid Android window surface.
- The application must leave VR mode as it is paused.
- The EGL context and surface used for VR entry/frame submission have
  thread-specific requirements. The future adapter must execute the returned
  commands on the correct GL thread rather than calling them from an arbitrary
  lifecycle callback.
- VrApi is an interface between the application and the VR device/runtime. It
  supplies tracking/timing and accepts rendered eye textures; it is not itself
  the OpenGL ES graphics API.

The old SDK generations do not all package the same way. Some Gear VR projects
included libvrapi.so and the supporting Java libraries in the application,
while later Oculus Mobile SDK documentation describes a VrApi implementation
delivered by the SystemActivities APK and SystemUtils support. Therefore
GVXRS Legacy must inspect a real target APK and identify its exact SDK/API
generation before choosing which symbols or Android services to implement.

Meta ended support for VrApi and the Mobile SDK on August 31, 2022. Existing
applications may still use those APIs, but this is a legacy compatibility
exercise rather than a supported path for new Meta applications.

## Native component boundary

### APK evidence layer

The existing Kotlin analyzer gathers manifest references, permissions,
components, native library names and ABI metadata. It keeps explicit Samsung
Gear VR evidence separate from generic libvrapi.so evidence.

### C++ lifecycle coordinator

The coordinator accepts serialized lifecycle events and returns one of three
commands: no action, enter VR mode, or leave VR mode.

It only requests entry when all four conditions are true:

1. The selected legacy backend reports that it is initialized and ready.
2. The Android activity is resumed.
3. The Android window surface is valid.
4. The user/session explicitly requested VR mode.

Pause, surface destruction or a stop request causes an active session to leave.
If enter/leave is already in flight, new lifecycle events are remembered and
reconciled after the operation completes. A failed transition does not trigger
an automatic retry. A failed leave is marked as possibly still active so the
adapter cannot silently start another session on top of an unknown backend
state.

The coordinator is single-thread-owned. Its future Android adapter must
serialize lifecycle events and execute enter/leave commands on the thread and
graphics context required by the concrete backend.

### Future compatibility backend

The C++ coordinator is not the compatibility ABI itself. The future backend
must be derived from the APK's actual imported/exported native symbols,
manifest dependencies and SDK generation. Do not invent VrApi exports or copy
proprietary runtime binaries into the project.

A separate native library target should be considered only after one real
classic APK has been audited. It must not be linked into libendxr_openxr.so or
change the GVXRS Mobile RuntimeBroker service.

## Next implementation gates

1. Select one legally obtained classic Gear VR APK as a test fixture. Record
   its SHA-256, package name, version, min/target SDK and native ABIs.
2. Inspect its APK contents and ELF dynamic sections. Record the exact
   VrApi-related libraries, undefined imports, required exported symbols and
   any SystemUtils/Activity service references.
3. Decide whether the target app bundles libvrapi or expects a system-delivered
   implementation. Do not assume those SDK generations are interchangeable.
4. Implement only the API surface required by that title in a separate C++
   compatibility target, with explicit unsupported results for unknown calls.
5. Add the Android lifecycle/JNI adapter and route its commands through the
   C++ coordinator.
6. Add an EGL/GLES stereo presentation path and verify resource ownership,
   thread rules, pause/resume, surface recreation and frame timing.
7. Test the selected title end-to-end, then add further games one at a time.

## Explicit non-goals of this milestone

- No Gear VR Services or SystemActivities replacement.
- No VrApi binary/API shim yet.
- No legacy compositor, distortion/timewarp or swapchain implementation.
- No controller or headset-dock emulation.
- No APK repackaging, signature bypass or automatic execution of imported APKs.
- No claim that a Gear VR-marked APK will run on the Redmi or any other phone.
- No modification to GVXRS Mobile OpenXR, its loader negotiation, service
  metadata, EGL/GLES path, JNI input bridge or Sandbox.

## References

- [Meta: VrApi Fundamentals (deprecated)](https://developers.meta.com/horizon/documentation/native/android/mobile-vrapi/)
- [Meta: VrApi, LibOVR and OpenXR](https://developers.meta.com/vr/documentation/unreal/os-openxr-vrapi/)
- [Meta: Native Mobile SDK source overview](https://developers.meta.com/vr/documentation/native/android/mobile-native-source-overview/)
- [Meta: Mobile Native SDK migration archive](https://developers.meta.com/vr/documentation/native/android/mobile-native-migration-archive/)
- [Samsung GearVRf releases and SDK packaging notes](https://github.com/Samsung/GearVRf/releases)
