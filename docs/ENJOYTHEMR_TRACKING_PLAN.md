# EnJoyTheMR-inspired hand tracking plan

## Reference examined

Reference repository: https://github.com/Zhes-20/etmr-repo

The public EnJoyTheMR API documents left/right hand tracking state, 3D hand-point positions and rotations, 21 MediaPipe landmark indices (0–20), pinch strength, grabbing state, gesture labels, and smooth attachment of scene objects to hand anchors.

The published SDK is a Unity package and its documented API is exposed through EnJoyTheMR's Unity/Lua runtime. The public documentation does not establish that its internal camera-tracking model is available as a standalone Android/Kotlin library. QuestPhoneXR must not claim to embed EnJoyTheMR's private tracker or copy SDK internals.

## QuestPhoneXR approach

1. Landmark provider: MediaPipe phone-camera tracking is the current baseline. A provider abstraction is a planned next step, so another technically compatible tracker can replace it without changing sandbox interaction code.
2. Normalized hand data: preserve all 21 points, image-relative coordinates, timestamps, and add confidence/handedness when the provider exposes them.
3. Pinch interaction: normalize thumb/index distance by palm width instead of using a fixed pixel distance; smooth the signal and use separate press/release thresholds.
4. Hand identity: use provider handedness labels when available; do not permanently identify hands only by list order.
5. Gesture layer: derive pinch/grab/point/OK above the provider so the sandbox is independent of the vision engine.
6. Quest-native provider: prefer runtime/OpenXR hand tracking on a headset when available; phone-camera tracking remains a separate input source.
7. Performance: keep inference asynchronous, discard stale frames, avoid unbounded queues, and measure latency, tracking FPS, dropped frames, and thermal behaviour on-device.

## Current implementation change

PinchEstimator now adds palm-width-normalized thumb/index distance, a continuous 0–1 pinch-strength signal, temporal smoothing, and separate pinch-on/pinch-off thresholds.

This is an initial interaction layer, not proof that tracking is already better than EnJoyTheMR. The next validation must compare both systems under the same lighting, camera distance, hand speed, occlusion, and one-/two-hand scenarios. Thresholds are starting values and must be tuned from device tests.


## MediaPipe optimization pass

The current camera path remains CameraX RGBA_8888 + MediaPipe BitmapImageBuilder. The Android
Hand Landmarker guide documents BitmapImageBuilder as the Android input path used by its
live-stream example; this implementation does not switch to an unverified raw-YUV path.

The accepted-frame pipeline now:
1. Keeps the bounded one-frame-in-flight gate and CameraX KEEP_ONLY_LATEST backpressure.
2. Reuses the packed pixel buffer, row scratch array, camera bitmap, model bitmap and Canvas while
   frame dimensions remain stable.
3. Copies padded RGBA rows into a reusable direct ByteBuffer instead of allocating a full-frame
   ByteArray for every accepted frame.
4. Rotates, mirrors, scales and letterboxes directly into the persistent model bitmap; no separate
   rotated bitmap or per-frame Matrix/RectF is created.
5. Keeps confidence thresholds at 0.5 and CPU inference as the stable default. Delegate changes
   are not forced without measurements on the target phone.

The sandbox now offers three persisted MediaPipe workload presets:
- Economy: one hand, 320×240, 100 ms minimum interval.
- Balanced: two hands, 480×360, 66 ms minimum interval.
- Responsive: two hands, 640×480, 50 ms minimum interval.

Manually selected camera dimensions still override preset dimensions. Adaptive performance can
increase the inference interval when measured rendering/tracking load remains high; it does not
disable tracking or silently change the user's selected resolution.

Telemetry records received/submitted/completed frames, drops caused by the busy gate or minimum
interval, preprocessing time, and total pipeline latency. These are diagnostics, not proof of
higher FPS until measured on-device. Compare presets in the same lighting, camera distance,
orientation and hand-motion sequence before choosing a default.
