# GVXRS Mobile hand-tracking pipeline

This document describes the Android camera-to-hand-input path and its current safeguards.

## Camera privacy and lifecycle

- The front camera preference is stored separately and defaults to **off**.
- If front-camera use is not explicitly enabled, the tracker selects the rear camera. It does not silently fall back to the front camera when the rear camera is unavailable.
- CameraX analysis uses `STRATEGY_KEEP_ONLY_LATEST`; stale queued frames are not accumulated.
- MediaPipe runs in `LIVE_STREAM` mode with one submitted frame in flight. Camera buffers and model bitmaps are reused.
- The tracker prefers the MediaPipe GPU delegate when the pipeline can keep the delegate on its owner thread; it falls back to CPU if GPU initialization fails. The selected delegate and thread match are exposed in diagnostics.

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
- Low-speed landmark noise is damped with a lower idle cutoff (image: 1.8 Hz; world: 1.7 Hz); the cutoff rises moderately with movement speed (image beta: 0.25; world beta: 0.18). Velocity is measured from the previous raw sample, avoiding the extra lag caused by estimating speed from an already filtered pose. These are conservative software defaults and still need physical-device tuning.
- Sandbox uses MediaPipe's adaptive landmark filter as the single camera-pose smoothing stage; it no longer double-smooths hands at render rate. Camera-space mapping validates the rotation matrix once per hand instead of allocating temporary rotation-check lists for every landmark.
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

## Sandbox render-time hand presentation

The Sandbox keeps interaction input and rendered hand geometry on separate paths:

- Pinch/grip state and the interaction cursor use the newest MediaPipe observation immediately.
- The 21 displayed landmarks are interpolated between the previous and newest per-hand observation using callback-arrival uptime timestamps.
- Presentation delay adapts to the observed sample interval (55% of the interval, clamped to 18–48 ms). Forward prediction is limited to 26 ms and 35% of the sample interval.
- Interpolation is reset on tracking loss, calibration changes, or a hand identity change. Discrete gesture state is never interpolated.

This stage is intentionally limited to Sandbox rendering. It does not change the MediaPipe filter, the OpenXR input snapshot, or the hand-tracking service output. Physical-device latency and perceived smoothness still need to be checked on the Redmi.


## Redmi Note 13 performance work

The persistent service now keeps the selected CameraX capture size separate from the MediaPipe input bitmap. The source can remain at 640×480 while the model bitmap is capped at 480×360, preserving the camera detail needed for distant hands without feeding the full RGBA frame into the model.

The service prefers the MediaPipe GPU delegate by default and falls back to CPU if GPU delegate creation fails. Sandbox also exposes a persisted GPU-preferred / CPU-only choice so both paths can be compared on the same phone. Diagnostics report both the requested delegate and the delegate actually created; successful GPU initialization does not prove faster inference, so the Redmi must be measured on-device.

The RGBA conversion path copies tightly packed rows directly into the reusable source bitmap. Devices that return padded rows continue to use the safe row-by-row repack path.

The controller publication path scans the maximum two hand sides directly and computes grip from the four required fingertip landmarks without temporary per-frame filter/map/list collections. The EndXR pose encoder similarly computes palm centers with bounded indexed loops. This reduces allocation churn while preserving the existing left/right selection and invalid-landmark behavior. The hand-association manager also reuses its internal observation and assignment scratch buffers; published frame lists remain independent so consumers cannot observe coordinates being overwritten by a later camera result.

The persistent service accepts live inference-interval updates without restarting CameraX or recreating the model. The Sandbox adaptive governor can therefore adjust the running service while it is visible.

A new **Быстро · 1 рука** preset uses one-hand inference and no additional frame interval. It is an optional low-latency workload; two-hand presets remain available. Preset descriptions are workload guidance, not guaranteed FPS.

A separate **Турбо · 1 рука** preset keeps CameraX capture at 640×480 but scales the model bitmap to 320×240. This reduces model-input pixels to one quarter of the 640×480 source size while retaining the higher-resolution camera image for preprocessing. It is a speed/detail tradeoff, not a prediction of a fourfold inference speedup. The selected model dimensions are preserved when tracking moves into the foreground service.

Diagnostics now split preprocessing, approximate inference, and result-processing time. Compare these values alongside result FPS, dropped-busy frames, dropped-interval frames, and result age. If inference dominates, test GPU versus CPU and one-hand mode. If preprocessing dominates, inspect CameraX stride/format and source dimensions. Do not claim a performance gain until the same scene and lighting are measured on the physical Redmi Note 13 4G. The diagnostic callback snapshots are throttled to 4 Hz, and the optional frame-luma sample runs at 1 Hz; error events still force an immediate metrics publish. A 0 ms inference interval means no added software throttle, not a guaranteed 1000 FPS target; the adaptive governor only adds delay after measured overload.
