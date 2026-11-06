# EndVR — PC-to-phone streaming architecture

## Status and scope

EndVR is the planned PC-to-Android VR streaming system in QuestPhoneXR. It is separate from EndXR, which is the Android OpenXR runtime. EndVR must not depend on EndXR being selected as the phone's active OpenXR runtime: the phone client must be able to receive and display a stream independently.

This document defines an incremental architecture. A generated-frame MJPEG preview now exists between the PC host and a separate Android client; real desktop/SteamVR capture, final low-latency media transport, tracking return and a usable HMD compositor do not exist yet.

## Product constraints

- Linux is a first-class host target, not a later compatibility port. Windows is also supported.
- The reference PC is an ASUS K513EA-class laptop with integrated graphics. The first useful target is a stable 20–30 FPS mode, not high-end PCVR.
- Android target is the Redmi Note 13 4G class device. Keep decode, tracking and display work bounded; thermal stability matters more than peak benchmark numbers.
- The phone camera is optional. Head orientation should work from phone sensors without enabling a camera. Hand tracking is an optional separate input source and starts only after an explicit user action.
- The system must expose honest capability states: unsupported encoder, unavailable capture backend, stale tracking, decoder overload and network congestion must be visible instead of silently pretending the headset is working.
- EndXR and EndVR share data models where useful, but their lifecycle and responsibilities remain independent.

## Process and module boundaries

### PC host

The host is responsible for:
1. Capturing the selected desktop/VR eye surfaces through a platform backend.
2. Obtaining frames with presentation timestamps.
3. Encoding frames with a selected hardware or software encoder.
4. Sending video and timing metadata to the phone.
5. Receiving head pose and optional hand/controller telemetry.
6. Mapping tracking into the host XR application/SteamVR integration.
7. Reporting encoder, capture, network and frame-pacing metrics.

Keep platform-specific capture and encoder selection behind interfaces. Linux backends should be implemented and tested first-class (PipeWire/Wayland and an explicit X11 fallback); Windows should use a separate capture backend (for example Windows Graphics Capture). Do not make X11, NVENC, or any one vendor mandatory.

### Android client

The Android client is responsible for:
1. Pairing with a host on the trusted local network.
2. Receiving video and stream metadata.
3. Decoding with Android MediaCodec where supported.
4. Presenting decoded frames to a low-latency Surface/EGL path.
5. Sampling phone orientation and sending timestamped tracking snapshots.
6. Optionally publishing hand tracking only while the user has enabled it.
7. Showing connection, decode, frame age, dropped-frame and thermal diagnostics.

The client must not require the OpenXR RuntimeBroker path for the initial streaming preview. A later OpenXR bridge can expose the stream to compatible applications, but phone display/decoder validation comes first.

## Transport design

Use separate logical channels for video and control/tracking.

### Video channel

The first prototype should use an established real-time media transport rather than inventing a reliable video protocol. Evaluate WebRTC (RTP media, congestion control, feedback and DTLS-SRTP) as the preferred MVP candidate because it already addresses jitter, loss, bitrate adaptation and secure peer transport. Keep the media transport behind an interface so a future custom low-overhead transport can be evaluated without rewriting capture or decode.

Requirements:
- Prefer hardware H.264 encode/decode as the baseline compatibility path.
- Treat HEVC and AV1 as optional negotiated capabilities, never assumptions.
- Negotiate codec, profile, dimensions, refresh rate and maximum bitrate before streaming.
- Use low-latency encoder settings and short queues; discard obsolete frames rather than accumulating latency.
- Carry capture/presentation timestamps and measure glass-to-glass latency where possible.
- Adapt bitrate and, if necessary, resolution/frame rate when congestion or sustained thermal pressure appears.
- Do not send raw uncompressed frames over Wi-Fi.
- Do not equate a successful network connection with a working video path.

### Control and tracking channel

Tracking is latest-state data: new samples supersede old ones. UDP is acceptable for a trusted-LAN prototype, but the existing JSON UDP telemetry is diagnostic scaffolding, not a production security protocol.

Before exposing control on untrusted networks:
- Authenticate the paired host/client.
- Encrypt control and tracking packets.
- Bind packets to a session identifier and reject replayed/out-of-order sequence numbers.
- Include monotonic sample timestamps and expire stale samples.
- Validate all numeric values and packet sizes.
- Never expose listening ports to the public internet by default.

Video transport and tracking transport must be independently observable. A lost tracking packet must not stall video; a video congestion event must not block head-pose sampling.

## Coordinate and timing contract

Define one documented canonical space before implementing the SteamVR bridge:
- Right-handed coordinate system.
- Metres for positions and seconds for durations.
- Explicit axis convention and transform for Android sensor orientation, camera image coordinates and host XR space.
- Quaternion order and handedness stated in the protocol.
- Every tracking sample includes a monotonic timestamp and sequence number.
- Head orientation-only samples must not fabricate translation.
- MediaPipe camera-relative hand landmarks must not be labelled room-space 6DoF.

Clock offset between phone and PC must be estimated during session setup; never subtract timestamps from unrelated clocks without an offset model. Use receive time only as a fallback and mark it as such.

## SteamVR / host XR integration

Do not begin by writing a full driver and a streamer at the same time. First validate the PC host and Android client as a standalone low-latency viewer using generated test frames. Then add a host XR integration behind a narrow adapter.

The adapter must:
- Publish head pose only with the tracking validity actually available.
- Publish hand/controller input only when a fresh sample exists.
- Report orientation-only tracking separately from position tracking.
- Avoid claiming 6DoF until a calibrated positional tracking source exists.
- Keep SteamVR/OpenVR-specific code out of the transport and Android client modules.

A SteamVR driver is a later milestone, not a prerequisite for validating video transport.

## Incremental implementation plan

### EV-0 — generated-frame phone preview (first slice implemented)
- The PC host emits a moving, stereo-labelled JPEG test pattern over MJPEG/HTTP.
- A standalone Android EndVR Client receives multipart frames, enforces frame-size/header limits, decodes JPEGs and displays the left/right halves side-by-side.
- The phone preview reports displayed FPS and the most recent JPEG decode duration, retries a lost connection and can explicitly stop the stream.
- Parser tests cover multiple parts, malformed/missing lengths, oversized frames and boundary handling.

The current EV-0 slice validates the PC-to-phone byte path and basic stereo display without SteamVR or EndXR RuntimeBroker. It is deliberately a development-only, unencrypted trusted-LAN preview. It does not yet freeze the production session/control schema, send tracking samples, report true capture-to-display frame age, or implement WebRTC/H.264. Those remain open before EV-1.

### EV-1 — LAN video proof
- Pair phone and host on the same Wi-Fi network.
- Negotiate H.264 and conservative dimensions/bitrate.
- Measure end-to-end latency, jitter, loss and sustained thermal behaviour.
- Test reconnect, host sleep, Wi-Fi change and explicit stop.

Exit criteria: repeatable 20–30 FPS mode on target hardware if the hardware supports it; report measured results rather than estimates.

### EV-2 — real host capture
- Implement Linux capture first with PipeWire/Wayland and X11 fallback.
- Add Windows capture backend independently.
- Add encoder capability probing and software fallback only when performance permits.

Exit criteria: desktop capture reaches the phone without queue growth or unbounded latency on both host OS families.

### EV-3 — tracking return path
- Send timestamped head orientation to the host.
- Add optional hand telemetry with explicit enable/disable.
- Validate coordinate transforms with visual axes and known rotations.
- Add stale-input neutralization and loss/recovery tests.

Exit criteria: host receives correctly oriented, fresh head samples; hand data is clearly marked approximate until calibrated.

### EV-4 — host XR integration
- Add a narrow SteamVR/OpenXR host adapter.
- Validate device discovery, pose validity, frame timing and application launch separately.
- Test Linux first and Windows separately.

Exit criteria: a test XR application uses the phone stream and receives valid tracking; compatibility is reported per application/runtime.

## Performance budget and diagnostics

Measure each stage independently:
- host capture wait and frame age;
- encoder queue depth and encode duration;
- network RTT, jitter, loss and bitrate;
- Android receive-to-decode queue age;
- decode duration and dropped frames;
- display/presentation timing;
- tracking sample age and send/receive rate;
- CPU/GPU load, battery temperature and thermal throttling.

A low frame rate with a growing queue is worse than a lower stable frame rate with bounded latency. Prefer dropping stale frames to preserving every frame.

The first diagnostic overlay should show: connection state, negotiated codec/resolution/bitrate, host FPS, phone decode FPS, frame age, network loss/jitter, tracking age, and thermal status.

## Security and privacy

- Pairing must be an explicit user action and show the host identity.
- Use authenticated encryption for production sessions.
- Bind control messages to the active session and reject stale/replayed input.
- Bind to the local network by default; no automatic public relay or port forwarding.
- Camera tracking is opt-in and visibly indicated. Front camera remains disabled unless the user explicitly selects it.
- Provide a one-action disconnect/stop control that closes sockets and clears published tracking state.

## Non-goals for the first prototype

- Universal Quest APK compatibility.
- Guaranteed 6DoF from a phone camera.
- A production-grade SteamVR driver before video decode is proven.
- Custom codec implementation.
- Cloud streaming or public-internet exposure.
- Claims of ALVR-level latency or compatibility without comparative measurements.

## Relationship to existing code

The existing `XrTrackingUdpSender` and Unity `QuestPhoneXRTrackingReceiver` are useful tracking-protocol experiments. They do not implement video transport, host capture, a decoder, pairing, encryption or a SteamVR driver. Preserve them as a diagnostic integration while designing the versioned production protocol separately.
