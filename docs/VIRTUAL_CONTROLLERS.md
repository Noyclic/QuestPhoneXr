# Virtual Controllers

## Goal

QuestPhoneXR uses one device-independent controller contract so phone-camera hand tracking and
Bluetooth gamepads (including Joy-Con when Android exposes them as gamepads) can drive the same
sandbox and future game adapters.

## Current data path

Phone camera -> CameraX -> MediaPipe -> HandTrackingFrame
                                     -> VirtualControllerMapper -> VirtualControllerFrame
Android gamepad events -> AndroidGamepadInputAdapter ----------------^

The mapper produces one left and one right controller state. A tracked hand contributes a
camera-relative pose, pinch trigger strength, and an estimated closed-hand grip. A gamepad assigned
to that side contributes stick axes, trigger axes, and its raw Android key codes.

## Important coordinate limitation

MediaPipe image landmarks are normalized image coordinates. World landmarks describe hand-local
3D geometry in meters, not the hand's absolute position in the room. Therefore HandPoseSample is
intentionally camera-relative and is not yet an OpenXR XrSpace pose. A later calibration stage
must align the phone camera with the headset play space and estimate a stable transform before
using these values to swing a virtual saber or place objects in the room.

The mapper keeps unknown left/right labels unassigned instead of guessing. The phone front-camera
pipeline mirrors the input image, so the tracking provider corrects the handedness label and local
horizontal axis when it creates each TrackedHand.

## Joy-Con / Bluetooth gamepads

AndroidGamepadInputAdapter captures Android KeyEvent and MotionEvent data for devices that report
SOURCE_GAMEPAD or SOURCE_JOYSTICK. Device IDs can be assigned to the left or right hand with
assignDevice(deviceId, hand). The adapter preserves raw key codes because host-side button mappings
differ between devices, OS versions, and pairing modes.

The current stage does not yet include an in-app calibration screen or persistent assignments. It
also does not claim that every Android build exposes a paired Joy-Con as two independent gamepads;
this must be verified on the actual phone / Quest OS version.

## Integration boundary

VirtualControllerFrame is the contract for our sandbox and for an adapter embedded into a game.
It is not a system-wide Quest controller driver. Ordinary OpenXR applications define and read their
own action sets; a separate QuestPhoneXR app cannot automatically inject this state into arbitrary
third-party APKs. To adapt a game such as Open Saber or Baldi Quest, we will need its source code,
a supported mod/plugin interface, or a per-game APK integration that reads this contract.

## Next implementation stages

1. Add a calibration UI for camera-to-play-space alignment and per-device Joy-Con assignment.
2. Transport controller frames from the phone to a Quest-side receiver with timestamps and sequence
   numbers, then measure end-to-end latency.
3. Add an OpenXR input adapter for QuestPhoneXR itself.
4. Adapt one open-source game as a proof of concept; only then decide how much reusable integration
   can be shared with other games.


## Phone-to-Quest UDP transport

The first network transport is implemented in `ControllerFrameUdpSender`. The phone app can send
the latest controller snapshot to a Quest IPv4 address on the same Wi-Fi network. It sends versioned
JSON state packets over UDP port 39470 at approximately 30 Hz. Sequence numbers and timestamps are
included; the Quest receiver should discard stale packets rather than replaying them.

The Quest-side Unity sample lives in `integrations/bbvr`. This is a game-side integration sample,
not a global virtual-controller driver. The sample currently exposes movement, smooth-turn and
run state; full hand pose mapping requires camera-to-play-space calibration.
