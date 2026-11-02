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
