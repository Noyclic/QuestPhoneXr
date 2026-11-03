# EndXR Runtime — implementation plan

## Direction
QuestPhoneXR is beginning an app-owned runtime implementation named **EndXR**.
Monado is no longer a required architectural dependency for the new runtime
path. Existing Monado diagnostics remain available as a legacy comparison tool
until EndXR can replace the required functionality.

## Important distinction
The first implementation is an **in-process runtime core**, not yet a
system-installable OpenXR runtime. It does not currently implement the Khronos
runtime negotiation ABI, Android Runtime Broker registration, OpenXR entry
points, a compositor, or Quest/Meta Android service compatibility. Therefore
it cannot yet be selected by arbitrary Quest APKs.

This staged approach avoids pretending that a small lifecycle class is already
a complete OpenXR runtime.

## Milestones
1. **Core lifecycle and frame clock** — app-owned state machine, validated
   render configuration, session lifecycle, monotonic predicted frame timing.
2. **Phone display backend** — GLES rendering surface, stereo targets and
   measured frame pacing on the target Redmi device.
3. **Tracking backend** — connect existing MediaPipe hand tracking and
   controller input to EndXR spaces/actions.
4. **OpenXR runtime ABI** — implement and test the Khronos runtime negotiation
   interface and required OpenXR instance/system/session/swapchain calls.
5. **Android runtime packaging** — package/register EndXR as a separate
   Android runtime service after the ABI and lifecycle are real.
6. **Quest APK compatibility** — identify and implement required Meta/Oculus
   Android APIs and services. This is separate from OpenXR itself and will be
   tested app by app.

## Current code
endxr_runtime_core.hpp/.cpp implement milestone 1's native state machine and
frame clock. It has no Monado, Binder, or Khronos Loader dependency. It is
compiled into the existing native library for now; later it should move into
a dedicated EndXR runtime module/package.

## Non-goals for the first milestone
- Claiming Quest APK compatibility before the OpenXR ABI exists.
- Removing Monado diagnostics before EndXR has a working replacement.
- Claiming 20–30 FPS until measured on the Redmi Note 13 4G.
