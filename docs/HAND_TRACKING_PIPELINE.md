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
5. Each track independently filters image landmarks and world landmarks for presentation, while pinch is estimated from the newest raw image landmarks with its own time-aware smoothing. Gesture input is not delayed by the render mesh filter.
6. The current visible detections are published to Sandbox / GVXRS input. Missing detections are not emitted as stale visible hands.

## Identity and recovery

- Association uses a predicted palm center (wrist plus the four palm MCP landmarks), MediaPipe handedness confidence, and a weak bounded palm-width continuity cue. Palm scale is not a hard identity constraint because apparent size legitimately changes as a hand moves toward or away from the camera.
- For two detections, both complete track assignments are compared instead of letting an early greedy match consume the wrong track.
- Each track owns its One Euro filter state and pinch estimator. Reordering MediaPipe's result list therefore does not transfer gesture state between hands.
- A dropout can retain identity for up to 350 ms. The last pose is held for at most 80 ms to cover an isolated missed result, then hidden until the detector reacquires it. During that short visible hold, image landmarks receive a small, non-accumulating translation prediction from recent palm-center velocity. Its integrated prediction horizon is capped at 50 ms and each axis is limited to 0.08 normalized units; hand-local world landmarks and discrete pinch state are not predicted.
- Pinch is released after 120 ms without a detection. Motion filters and velocity are reset after a longer gap so a returning hand does not slide from an obsolete pose.
- Track IDs are diagnostic identity tokens only; they are not hardware IDs or persistent identities across application restarts.

## Filtering and pinch

- Image landmarks and world landmarks use independent One Euro filters.
- Low-speed landmark noise is damped with a lower idle cutoff (image: 1.8 Hz; world: 1.7 Hz); the cutoff rises moderately with movement speed (image beta: 0.25; world beta: 0.18). The visible thumb/index tips use a separate faster One Euro response (3.2 Hz minimum cutoff, beta 0.65) while palm and knuckle landmarks keep the quieter default filter. Velocity is measured from the previous raw sample, avoiding the extra lag caused by estimating speed from an already filtered pose. These are conservative software defaults and still need physical-device tuning.
- Sandbox uses MediaPipe's adaptive landmark filter as the single camera-pose smoothing stage; it no longer double-smooths hands at render rate. Camera-space mapping validates the rotation matrix once per hand instead of allocating temporary rotation-check lists for every landmark.
- The filter uses MediaPipe callback timestamps, not an assumed fixed frame rate. Large or invalid timestamp gaps reset filter state.
- The visible image-space filter applies a bounded XY movement budget before derivative estimation (18 normalized image units/s for palm/joint landmarks and 28 for thumb/index tips). It only constrains extreme per-sample jumps; default generic filters and the metre-based world-landmark filter keep the original unrestricted behavior. The cap is intended to contain isolated landmark teleports without imposing a low-speed limit on ordinary hand motion.
- Pinch distance is normalized by palm width so the gesture is less sensitive to how close the hand is to the camera.
- Pinch uses the newest raw thumb/index/palm landmarks rather than the already-filtered render mesh, avoiding two serial smoothing stages on fingertip motion. When MediaPipe supplies plausible world landmarks, thumb/index distance and palm width are measured in 3D; edge-on 2D projection otherwise makes the apparent palm width too small. Missing or implausible world geometry safely falls back to normalized image-space distance. Its own time-aware smoother uses separate activation and release thresholds (0.62 / 0.40) to respond to deliberate partial closure without treating a relaxed open hand as a pinch. A high-confidence near-closed pinch uses a faster attack alpha (0.82) so a clear gesture can activate on one observation; partial closures retain the normal multi-sample smoothing. Missing, non-finite or overflowed key landmarks clear the gesture rather than leaving a stuck pinch.
- The gesture hot path validates the four required landmarks directly without creating a temporary list.
- The latest normalized thumb/index distance ratio and whether it came from world or image landmarks are carried with each tracked hand. Sandbox diagnostics show strength, normalized gap, measurement source and ON/OFF state so real-device threshold tuning can distinguish landmark quality from downstream input mapping.
- While pinching, an isolated noisy open-hand estimate does not immediately release a grab. Release requires 24 ms of continuously low pinch strength; a renewed pinch cancels the pending release. Missing/non-finite required landmarks still clear the signal immediately.

## Device tuning and limits

The existing workload presets still control camera dimensions, hand count and minimum inference interval. They are workload choices, not FPS promises. The current target for Redmi Note 13 4G remains a measured 20–30 display FPS aspiration, not a verified result.

This is monocular camera tracking. MediaPipe world landmarks are hand-local estimates, and apparent palm width supplies relative depth. It is not room-scale optical tracking or a guaranteed metric 6DoF controller pose.

## Tests

The JVM suite covers:
- adaptive smoothing versus fast motion;
- MediaPipe result reordering and hand crossing;
- per-hand pinch-state ownership;
- short dropouts, the 80 ms pose hold, long-gap pinch release and filter reset;
- stale-track expiration, invalid landmarks and non-monotonic timestamps;
- extreme image-space landmark jumps, the larger fingertip motion budget, short-gap bounded pose prediction, and palm-scale-assisted association when handedness is unknown.

Physical-device checks are still required for latency, occlusion behavior, low-light recovery, sustained temperature and battery draw.

## Sandbox render-time hand presentation

The Sandbox keeps interaction input and rendered hand geometry on separate paths:

- Pinch/grip state and the interaction cursor use the newest MediaPipe observation immediately; pinch has its own short time-aware smoothing and does not wait for the visual One Euro filter.
- The 21 displayed landmarks are interpolated between the previous and newest per-hand observation using callback-arrival uptime timestamps.
- Presentation delay adapts to the observed sample interval and callback jitter (clamped to 18–64 ms). Forward prediction is limited to 26 ms and 35% of the sample interval.
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

## Adaptive presentation timing

Sandbox render interpolation now estimates both the recent MediaPipe callback interval and its
short-term absolute interval jitter. Stable cadence uses a 30% interval presentation delay
(clamped to 18–64 ms); irregular callback spacing adds a bounded jitter margin. The estimator
resets when a hand disappears, changes identity, or the sample gap is too large. Forward
prediction remains capped at 26 ms / 35% of the sample interval. This changes rendered landmark
timing only: pinch/grip and the interaction cursor continue to use the newest observation.


## Occlusion and unstable-landmark hardening

The latest tracking increment adds three bounded safeguards:

1. Extreme image-landmark jumps are limited before entering the One Euro derivative estimator. Thumb and index tips have a larger movement budget than the palm so a legitimate fast pinch remains more responsive. This is not a general speed cap on the world-space pose or the controller input.
2. During the existing 80 ms visibility hold, a missed hand may continue along its recent image-space direction for a very short, decelerating interval. Prediction is based on the last real pose on every render (it never accumulates), stops after a 50 ms integration horizon, and is capped to 0.08 normalized units per axis. It does not synthesize world-space motion or hold a pinch after the manager's existing loss-release deadline.
3. When handedness is unavailable or weak, assignment uses palm width as a small, bounded continuity cue alongside predicted center distance. This can help distinguish two similarly positioned hands with different apparent sizes, but it deliberately does not override a strong handedness signal or assume palm size is constant.

These safeguards are regression-tested in the JVM suite. They still need physical Redmi testing with fast movement, partial occlusion, hands crossing, and near/far depth changes; no measured latency or FPS improvement is claimed.
