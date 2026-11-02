# Baldi's Basics Classic VR — QuestPhoneXR test integration

This is the first game-specific proof of concept for the universal QuestPhoneXR input bridge.
It targets the open-source **Baldi's Basics Classic VR** project by hanabira-mitsu:
https://github.com/hanabira-mitsu/bbvr

This is not the separate Baldi Tag app and is not claimed to be compatible with every Baldi-themed
Quest game. Confirm that this is the game you intend to test before installing a modified build.

## What is included

- `QuestPhoneXRInputReceiver.cs`: Unity UDP receiver for QuestPhoneXR snapshots (port 39470).
- `BaldiQuestPhoneXRAdapter.cs`: initial shim for left-stick movement, right-stick turning,
  run, and a configurable use-item edge.

The receiver is a reusable transport component; the Baldi shim is intentionally game-specific.
No upstream game files are changed by this repository.

## Setup

1. Import both C# files into the Unity project for Baldi's Basics Classic VR.
2. Add `QuestPhoneXRInputReceiver` and `BaldiQuestPhoneXRAdapter` to one persistent GameObject.
3. Ensure the Android manifest merged into the Quest APK contains
   `<uses-permission android:name="android.permission.INTERNET" />`.
4. Put the phone and Quest on the same local Wi-Fi network. Avoid guest Wi-Fi that isolates clients.
5. Find the Quest's local IPv4 address in its Wi-Fi settings and enter it in the QuestPhoneXR phone app.
6. Pair Joy-Con with the phone. The phone app currently forwards Android key codes as reported by
   the phone; the key-code mapping must be configured for the actual Joy-Con/Android pairing mode.

## First input patch in PlayerScript.cs

In `PlayerScript.PlayerMove()`, replace the reads:

```csharp
Input.GetAxis("Forward")
Input.GetAxis("Strafe")
Input.GetButton("Run")
```

with:

```csharp
BaldiQuestPhoneXRAdapter.Connected
    ? BaldiQuestPhoneXRAdapter.Forward : Input.GetAxis("Forward")
BaldiQuestPhoneXRAdapter.Connected
    ? BaldiQuestPhoneXRAdapter.Strafe : Input.GetAxis("Strafe")
(BaldiQuestPhoneXRAdapter.Connected
    ? BaldiQuestPhoneXRAdapter.Run : Input.GetButton("Run"))
```

In `MouseMove()`, use `BaldiQuestPhoneXRAdapter.Turn` instead of
`Input.GetAxis("XRI_Right_Primary2DAxis_Horizontal")` while the bridge is connected.

This first pass deliberately covers locomotion and smooth turning only. Item use, ray interaction,
hand/controller transforms, and full OpenXR action replacement require the game's scene-level
wiring and camera-to-play-space calibration. The camera landmarks are image-relative, not absolute
room-space poses; do not feed them directly into a Unity Transform as meters.

## Network and safety notes

- UDP port: 39470.
- The phone sends compact state snapshots at about 30 Hz. A dropped packet is replaced by a newer
  state; this avoids input lag from retransmitting stale controller state.
- The receiver treats packets older than 250 ms as disconnected and resets movement.
- Use only on a trusted local network. This prototype has no authentication or encryption.
- This is an integration sample, not a universal system-level controller driver. Other games need
  their own input adapter or a supported OpenXR/runtime integration.
- Preserve the original game project's license and attribution. Check its license before publishing
  or redistributing a modified APK; this repository does not claim ownership of Baldi's assets/code.
