# GVXRS Dock Station — secondary-phone tracking design

## Goal

Use a second Android phone as an optional tracking camera for the primary phone that acts as the VR viewer. The first use case is **hand-tracking assistance**: reduce dropouts when the headset phone loses a hand near the edge of its camera view or during a brief occlusion. Body tracking is a later, separate capability.

The secondary phone is not a second renderer and does not run the OpenXR runtime. It is a camera/landmark producer. The headset phone remains authoritative for the game, OpenXR, input mapping and final pose fusion.

## Device roles

### Headset phone (initially Redmi Note 13 4G)

- Runs NextVRera, GVXRS Mobile/OpenXR and the game-facing input path.
- Runs the existing CameraX + MediaPipe hand tracker as the primary source.
- Owns camera calibration, track association, confidence arbitration and final OpenXR samples.
- Continues to work normally when the Dock Station is disconnected.

### Dock Station phone (initially the low-end Helio G37 device)

- Runs a dedicated Dock Station mode inside NextVRera.
- Uses one camera and a deliberately economical inference profile.
- Sends landmark packets and frame metadata only by default; it does not stream camera video.
- Can be paused or disconnected without interrupting the primary tracker.
- Must be started explicitly by the user. Camera use must follow Android's camera permission and foreground-service rules.

## Physical mounting

Mount the secondary phone rigidly above or slightly offset from the headset phone, with both cameras facing the hand interaction area. Do not assume that simply pointing both cameras in exactly the same direction will solve occlusion. Their fields of view should overlap enough to match the same hand, while the secondary view should be offset/angled enough to observe some areas hidden from the primary camera.

A loose mount invalidates calibration. The Dock Station screen should show a mount/calibration warning after either phone is moved.

## Data contract v1

`DockTrackingPacket` is the first implementation milestone. It is a versioned latest-state contract, not a network transport or camera service.

Each packet contains:

- Protocol version, paired-session ID and monotonically increasing sequence number.
- Sender-side monotonic capture timestamp.
- Upright image dimensions, camera sensor rotation and mirror metadata.
- Zero to two hand observations.
- A station-local track ID, handedness and confidence for each hand.
- Exactly 21 MediaPipe-order landmarks per hand.

The validator rejects unsupported versions, malformed dimensions, invalid rotations, duplicate track IDs, malformed landmark counts, non-finite/outlier coordinates and invalid confidence. The sequence guard rejects stale/out-of-order packets and packets from another paired session. `DockTrackingPacketCodec` serializes the contract into a bounded, big-endian binary payload and rejects malformed framing, oversized payloads, truncation, unknown enum values and trailing bytes. It is a wire codec, not encryption or authentication.

**Clock rule:** `captureTimestampNanos` belongs to the sender's monotonic clock. It must not be directly subtracted from the Redmi's monotonic clock. The future connection handshake must estimate clock offset/drift; until then, receiver arrival time is used for local freshness decisions and the sender sequence is used only for ordering.

The packet contract intentionally does not include image pixels, audio, or a claim of room-space 6DoF. MediaPipe world landmarks are hand-local, not calibrated room-space coordinates.

## Calibration and fusion

Do not merge raw normalized coordinates from two cameras. Their origins, intrinsics, field of view, rotation and mirror state differ.

The planned calibration flow is:

1. Read both cameras' intrinsic calibration where the device exposes it.
2. Capture a shared calibration target from multiple positions to estimate the rigid transform between camera coordinate systems.
3. Store a calibration profile keyed to the two paired devices and camera IDs.
4. Require recalibration after a camera ID, mount, orientation or device pairing change.
5. Display calibration residual/error and refuse stereo fusion when the solution is poor.

The planned fusion rules are conservative:

1. The primary Redmi tracker remains authoritative while its observation is fresh and confident.
2. Match secondary observations using calibrated geometry, temporal continuity and handedness confidence; never rely on MediaPipe result-list order.
3. Use stereo triangulation only when the same hand is visible in both cameras and calibration passes quality gates.
4. If only the secondary camera sees a hand, treat it as a secondary-camera observation. Transform it only when its depth/pose estimate is sufficiently constrained; otherwise keep the last valid fused pose briefly and fade confidence rather than inventing a precise position.
5. Never average two observations that may belong to different hands.
6. Apply a short freshness timeout and release pinch/grip state on loss. Do not let a stale secondary packet hold a button indefinitely.
7. Keep estimated poses separate from genuinely tracked poses. Do not set OpenXR `TRACKED` flags merely because a second camera is connected.

The existing single-camera tracker and its standalone mode must remain functional throughout this work.

## Transport and pairing requirements (not implemented yet)

- Pair through an explicit user action, preferably a QR code or short-lived one-time code.
- Authenticate the paired device; a session ID in the packet is an ordering label, not authentication.
- Keep traffic on the local network. Do not expose an unauthenticated listener to the public internet.
- Prefer a small bounded latest-state channel. Do not queue old landmark frames; a newer frame should replace an obsolete one.
- Encrypt the connection and bind it to the paired device/session.
- Include heartbeat, disconnect reason, protocol negotiation and reconnect behavior.
- Never require continuous raw video transmission for normal operation.

A transport choice (for example, a paired encrypted stream with latest-frame replacement) will be made when the Android connection layer is implemented. The packet model must remain independent of that choice. `DockLatestPacketBuffer` now implements the receiver-side one-slot policy: every accepted packet replaces the previous sample, reads return it only inside a configurable freshness window (250 ms by default), and expiry clears it so stale pinch/grip state cannot persist. Receiver-local elapsed-realtime is used for freshness; sender monotonic timestamps are never compared directly to the receiver clock.

## Low-end G37 profile

Start with a measurement-first profile, not a performance promise:

- One camera, low resolution, initially around 320x240.
- Begin around 10 inference updates per second; benchmark before raising the rate.
- Allow one-hand mode as the lowest-load option.
- No body-pose model running concurrently in the first hand-assist milestone.
- Report inference time, packet rate, dropped frames, temperature/thermal status where available, and end-to-end receive age.

The receiving Redmi should also show per-source freshness and confidence so a slower station cannot silently make tracking worse.

## Delivery stages

1. **Packet contract, codec and latest-state buffer:** implemented in `tracking/dock/`; JVM tests cover validation, binary round trips, malformed framing, replay rejection, packet replacement and freshness expiry. No camera/network side effects yet.
2. **Pairing and transport:** discovery, explicit pairing, authentication, encrypted local connection, reconnect handling and bounded latest-state delivery over a real socket.
3. **Dock Station camera mode:** role selection, camera permission, economical inference profile and diagnostics.
4. **Calibration UI:** camera IDs, intrinsics/extrinsics, saved profile and calibration quality gates.
5. **Hand fusion:** source association, confidence arbitration, short dropout handoff and stale-input neutralization.
6. **Physical test:** measure hand-loss rate, latency, thermal behavior and tracking stability with the Redmi + actual G37 phone.
7. **Optional body tracking:** only after hand-assist is stable; body pose must not consume the G37's hand-inference budget by default.

## Current implementation boundary

The packet data classes, structural validator, per-session sequence guard, bounded binary codec and one-slot freshness buffer are implemented with JVM unit tests. Pairing, encrypted transport, camera streaming, calibration, fusion and a Dock Station UI are **not implemented yet**. The current change does not alter the MediaPipe path or OpenXR output.
