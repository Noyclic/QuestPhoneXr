# Android gamepad and Joy-Con setup

QuestPhoneXR reads Android gamepad and joystick events. A controller must appear to Android as a standard InputDevice; the app does not use a Nintendo-specific Bluetooth protocol.

## Assigning controllers

1. Pair the left and right Joy-Con in Android Bluetooth settings.
2. Open the tracking diagnostics screen from the game library.
3. Tap the controller discovery and assignment button.
4. Select each connected device and assign it to the left or right virtual hand.
5. Select a provisional game profile to see the resulting action values.

Only one physical device can be assigned to each virtual hand at a time. Reassigning a hand releases its previous device. Assignments are session-local and are not currently persisted.

## Input processing

The first stick uses Android X/Y axes. The second stick accepts RX/RY and Z/RZ layouts. Trigger values accept LTRIGGER/RTRIGGER and BRAKE/GAS aliases. Axis pairs receive a radial dead zone (14% by default) with the remaining range rescaled to full output.

Android controller names, axis layouts, and key codes vary by device and firmware. This is a generic input path, not proof that every Joy-Con revision has a complete or identical mapping. Confirm the live left/right stick and button values on the target phone before marking a controller profile as tested.

## Current limitations

- No Nintendo-specific Bluetooth handshake or firmware mode switching.
- Button prompts are not yet auto-generated from each device's reported layout.
- Assignments currently last for the current app session.
- The game action profiles are phone-port proposals. They do not inject controls into arbitrary Quest APKs.
