# GVXRS Mobile hand-tracking pipeline

This document describes the Android camera-to-hand-input path and its current safeguards.

## Camera privacy and lifecycle

- The front camera preference is stored separately and defaults to **off**.
- If front-camera use is not explicitly enabled, the tracker selects the rear camera. It does not silently fall back to the front camera when the rear camera is unavailable.
- CameraX analysis uses `STRATEGY_KEEP_ONLY_LATEST`; stale queued frames are not accumulated.
- MediaPipe runs in `LIVE_STREAM` mode with one submitted frame in flight. Camera buffers and model bitmaps are reused.
- The current MediaPipe delegate is CPU. This is a compatibility baseline, not a claim that CPU is fastest on every phone.

## Per-frame processing

1. CameraX RGBA frame is copied into a reusable bitmap.
2. The image is rotated, optionally mirrored for the explicitly selected front camera, scaled and letterboxed to the model input.
3. MediaPipe returns image landmarks, world landmarks and handedness metadata.
4. `HandTrackManager` associates each result with a persistent track before filtering.
5. Each track independently filters image landmarks and world landmarks, then estimates pinch from the filtered image landmarks.
6. The current visible detections are published to Sandbox / GVXRS input. Missing detections are not emitted as stale visible hands.

## Identity and recovery

- Association uses a predicted palm center (wrist plus the four palm MCP landmarks) and MediaPipe handedness confidence.
- For two detections, both complete track assignments are compared instead of letting an early greedy match consume the wrong track.
- Each track owns its One Euro filter state and pinch estimator. Reordering MediaPipe's result list therefore does not transfer gesture state between hands.
- A dropout can retain identity for up to 350 ms. The last pose is held for at most 80 ms to cover an isolated missed result, then hidden until the detector reacquires it.
- Pinch is released after 120 ms without a detection. Motion filters and velocity are reset after a longer gap so a returning hand does not slide from an obsolete pose.
- Track IDs are diagnostic identity tokens only; they are not hardware IDs or persistent identities across application restarts.

## Filtering and pinch

- Image landmarks and world landmarks use independent One Euro filters.
- Low-speed landmark noise is damped; the cutoff rises with movement speed. Velocity is measured from the previous raw sample, avoiding the extra lag caused by estimating speed from an already filtered pose.
- Sandbox applies only a light secondary pose stabilizer; camera-space mapping no longer allocates temporary rotation-check lists for every landmark.
- The filter uses MediaPipe callback timestamps, not an assumed fixed frame rate. Large or invalid timestamp gaps reset filter state.
- Pinch distance is normalized by palm width so the gesture is less sensitive to how close the hand is to the camera.
- Pinch uses separate activation and release thresholds (hysteresis) and time-aware smoothing. Missing or invalid key landmarks clear the gesture rather than leaving a stuck pinch.

## Device tuning and limits

The existing workload presets still control camera dimensions, hand count and minimum inference interval. They are workload choices, not FPS promises. The current target for Redmi Note 13 4G remains a measured 20–30 display FPS aspiration, not a verified result.

This is monocular camera tracking. MediaPipe world landmarks are hand-local estimates, and apparent palm width supplies relative depth. It is not room-scale optical tracking or a guaranteed metric 6DoF controller pose.

## Tests

The JVM suite covers:
- adaptive smoothing versus fast motion;
- MediaPipe result reordering and hand crossing;
- per-hand pinch-state ownership;
- short dropouts, the 80 ms pose hold, long-gap pinch release and filter reset;
- stale-track expiration, invalid landmarks and non-monotonic timestamps.

Physical-device checks are still required for latency, occlusion behavior, low-light recovery, sustained temperature and battery draw.
