# Virtual Controllers

## Goal

QuestPhoneXR uses one device-independent controller contract so phone-camera hand tracking and Bluetooth gamepads (including Joy-Con when Android exposes them as gamepads) can drive the phone-native VR runtime and supported game integrations.

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
must align the phone camera with the phone-VR play space and estimate a stable transform before
using these values to swing a virtual saber or place objects in the room.

The mapper keeps unknown left/right labels unassigned instead of guessing. The phone front-camera
pipeline mirrors the input image, so the tracking provider corrects the handedness label and local
horizontal axis when it creates each TrackedHand.

## Joy-Con / Bluetooth gamepads

AndroidGamepadInputAdapter captures Android KeyEvent and MotionEvent data for devices that report
SOURCE_GAMEPAD or SOURCE_JOYSTICK. Device IDs can be assigned to the left or right hand with
assignDevice(deviceId, hand). The adapter preserves raw key codes because host-side button
mappings differ between devices, OS versions, and pairing modes.

The current stage does not yet include an in-app calibration screen or persistent assignments. It
also does not claim that every Android build exposes a paired Joy-Con as two independent gamepads;
this must be verified on the actual phone and Android version.

## Per-game adapter boundary

GameInputAdapter converts VirtualControllerFrame into a stable GameActionFrame.
GameAdapterRegistry returns an adapter only when a provider explicitly registers a game ID.
Game-specific adapters can then translate common controls to that game's expected actions.

This does not inject input into arbitrary third-party APKs. A game must be ported into the
QuestPhoneXR runtime, expose a supported plugin/mod interface, or include a game-specific
integration. Quest-only games may need their Meta-specific input and runtime calls replaced.

## Optional Quest experiment

The phone-to-Quest UDP sender and Unity receiver sample remain under integrations/bbvr for
experiments with a separate Quest device. They are not required for the phone-only mode and are
not a universal controller driver.
