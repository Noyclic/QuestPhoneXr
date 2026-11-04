# Gear VR classics — research and compatibility plan

## Goal

Preserve practical access to classic Samsung Gear VR software inside QuestPhoneXR through a dedicated **Gear VR Ecosystem**. This is a compatibility and preservation effort, not a promise that every historical title can run on a modern phone.

The Gear VR catalogue must remain separate from modern Meta Quest APKs, ordinary Android / SBS VR apps, and OpenXR runtime diagnostics.

## Why this is technically worth investigating

Meta's 2019 notice says Gear VR development support was dropped from Oculus SDK Suite 1.42 and native Mobile SDK 1.27.0 onward, while existing titles using Mobile SDK 1.26.0 / SDK Suite 1.41 or earlier remained on the legacy path. Meta also documented that Gear VR technology contributed to the foundations of later Quest technology.

Sources:
- https://developers.meta.com/vr/blog/gear-vr-app-development-no-longer-supported-from-sdk-suite-142-and-future-versions/
- https://developers.meta.com/vr/blog/gear-vr-update-no-longer-accepting-new-apps-as-of-september-15-2020/
- https://developers.meta.com/vr/documentation/native/android/mobile-native-migration-archive/

This history gives us a real technical starting point: legacy APKs, manifests, native libraries, SDK fingerprints and runtime/service assumptions can be inspected. It does **not** prove that a Gear VR APK can run without its original runtime or that a modern Android phone can replace Samsung's supported Gear VR hardware.

## Compatibility is a ladder, not one yes/no flag

Every title must be assessed independently and assigned the highest evidence-backed stage:

1. **Identified** — title, package name and source/provenance recorded.
2. **APK inspected** — manifest, min/target SDK, requested features, activities, permissions, native ABIs and bundled libraries recorded.
3. **Legacy dependencies mapped** — Gear VR / Oculus Mobile SDK markers, service bindings, intents, signatures, device checks and controller assumptions documented.
4. **Launch path understood** — ordinary Android launch, legacy Oculus service requirement, missing proprietary runtime, or a plausible port path identified.
5. **Launch attempted** — install and launch result captured on a named device and Android version.
6. **Playable** — display/stereo mode, tracking, input, audio, frame pacing and stability tested in an actual session.
7. **Adapted/ported** — a lawful source-based port or compatibility adapter exists, with its changes and limitations documented.

Never promote a title to "playable" because it installs, appears in a catalogue, or starts a splash screen.

## First research pass: inspect before emulating

For each APK, record:
- package name, version, signing information and provenance;
- manifest metadata, required features, activities and intent filters;
- minSdk / targetSdk and Android compatibility warnings;
- available native ABIs (for example armeabi-v7a and arm64-v8a);
- native library names and identifiable SDK/runtime dependencies;
- Oculus / Gear VR service package references, permissions, content-provider or bound-service usage;
- device model checks, headset/controller assumptions and input API usage;
- whether the title is a 2D launcher, stereoscopic Android app, legacy VrApi app, or a title with an identifiable source/port path.

Do not modify or redistribute copyrighted APKs as part of the repository. The project should store metadata, test notes and links to legitimate sources; users provide their own legally obtained app files for local testing.

## Compatibility strategies to evaluate

Evaluate these in order, per title:

1. **Native launch** — only if the app genuinely does not require unavailable Gear VR runtime components.
2. **Compatibility shim** — only for documented, narrow Android-facing behaviors that can be implemented without copying proprietary code or bypassing platform security.
3. **Source port / rebuild** — preferred when source code is available and licensing permits it. Replace obsolete SDK integrations with a documented supported rendering/input path.
4. **Not currently supportable** — record the exact blocker instead of pretending a renamed APK or manifest edit fixes it.

A general-purpose replacement for the proprietary Gear VR runtime is a much larger research project. It must not be treated as a prerequisite for the first catalogue milestone.

## Device and performance constraints

The first target phone is the Redmi Note 13 4G on Android 15. Its modern Android version and hardware differ substantially from historical Gear VR-certified Samsung phones. Old apps may fail due to ABI, removed Android APIs, unsupported SDK behavior, missing services, signature checks or device checks. Performance must be measured; the assumption that old titles are lighter is a hypothesis, not a compatibility guarantee.

For each physical test, capture:
- phone model and Android/API version;
- QuestPhoneXR build and APK hash;
- install / launch / session outcome;
- stereo presentation and head-tracking outcome;
- input/controller outcome;
- average and minimum FPS when measurable;
- crash, logcat excerpt, thermal behavior and reproduction steps.

## Milestones

### GVR-0 — inventory and evidence
Create the separate catalogue model and APK inspection report format. No runtime changes.

### GVR-1 — first real title audit
Choose one legally obtained classic title. Inspect its APK and dependencies, then publish a compatibility report with no unsupported claims.

### GVR-2 — prove one launch path
Attempt the least invasive viable path on the Redmi. If native launch is impossible, document whether a source port is realistic.

### GVR-3 — separate Gear VR library window
Add a dedicated entry and catalogue UI. Keep its data, badges and reports separate from Quest APKs and Android/SBS apps.

### GVR-4 — title-specific adaptation
Only after GVR-1/GVR-2 provide evidence, implement a narrowly scoped adapter or source port for a title that has a credible path.

## Non-goals for the first milestone

- Claiming full Gear VR runtime replacement.
- Claiming universal compatibility with Gear VR or Oculus Go titles.
- Bundling proprietary Oculus/Samsung services, firmware or SDK binaries.
- Treating Quest OpenXR support as proof of legacy VrApi compatibility.
- Changing the existing OpenXR/IPC path before the Gear VR research identifies a concrete need.

## First acceptance criteria

The first milestone is complete when one real APK has a reproducible inspection report that identifies its SDK/runtime assumptions, ABI, launch requirements and a justified next step: native launch test, source port investigation, or documented blocker. A successful game launch is a later milestone, not a fabricated prerequisite for research progress.
