# QuestPhoneXR PC tracking bridge

This folder contains the first PC-side receiver for phone-originated XR tracking telemetry.

## What works

- Android sends the latest phone orientation matrix and MediaPipe hand landmarks over UDP.
- Up to two hands are sent with side, pinch strength/state, 21 normalized image landmarks and
  21 hand-local world landmarks (when MediaPipe provides them).
- The Unity receiver exposes the most recent valid packet through
  `QuestPhoneXRTrackingReceiver.TryGetFreshPacket`.
- Packets are sent at up to 30 Hz. Old packets are not retransmitted; the receiver should stop
  using input when packets have been absent for more than 250 ms.

## What this does not do yet

- It does not transmit the camera image or a rendered game video stream.
- It does not create calibrated room-space hand positions: image landmarks are camera-relative and
  MediaPipe world landmarks are hand-local.
- It is not a SteamVR driver and does not inject OpenXR actions into arbitrary games.
- It does not replace the existing controller snapshot protocol on UDP 39470.

This bridge is a transport foundation for later PC streaming and OpenXR integration.

## Setup

1. Add `QuestPhoneXRTrackingReceiver.cs` to the Unity PC project.
2. Add the component to one persistent GameObject in the scene.
3. Allow inbound UDP on port `39471` in the PC firewall for the private/local network.
4. Connect the phone and PC to the same local Wi-Fi network.
5. In QuestPhoneXR sandbox, choose **Подключить ПК · телеметрия рук и головы** and enter the
   PC's local IPv4 address.
6. Read tracking data from `QuestPhoneXRTrackingReceiver.TryGetFreshPacket`.

The phone uses UDP port `39471`. The existing virtual-controller/game adapter still uses UDP
port `39470`.

## Packet contract (UTF-8 JSON, schema version 1)

- `protocol`: `QuestPhoneXR`
- `type`: `tracking`
- `sessionId`: unique sender-session identifier, refreshed after app restart
- `sequence`: increasing packet number
- `timestampMs`: Android monotonic time in milliseconds; do not compare it directly with PC time
- `head.tracked` and `head.rotationMatrix`: row-major 3×3 sensor orientation matrix, or `null`
- `hands[]`: up to two hand records, with `side`, `pinchStrength`, `pinchPressed`,
  `imageLandmarks[]` and `worldLandmarks[]`
- Each landmark is an `{x,y,z}` object. Image coordinates are normalized to the camera image;
  world landmarks are MediaPipe hand-local coordinates in meters.

## Network safety

Use this only on a trusted local network. UDP packets are not encrypted or authenticated. Do not
expose port `39471` to the public internet. The PC receiver validates the protocol version and
bounds packet size and freshness, but it does not authenticate the sender.
