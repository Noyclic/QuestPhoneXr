# Phone VR sandbox

The VR sandbox is the first interactive scene for testing QuestPhoneXR's phone-based stereo viewer, camera hand tracking, and Android controller input.

## Scene

- Two colored low-poly 3D hands built from the live 21-point MediaPipe pose; virtual cursors remain as the gamepad/no-camera fallback.
- Two cubes, one ball, and one target object at startup.
- A floor plane with gravity, a small bounce, and friction.
- A target that awards a point when a movable object reaches it; scored objects respawn at their starting positions.
- Buttons to spawn more cubes, assign controllers, recenter the phone, and reset the scene.

## Camera hand controls

1. Allow camera permission when the sandbox opens.
2. The app uses the rear camera when available; it falls back to the front camera if needed.
3. Keep your hands visible to the camera's field of view.
4. Move a hand to move its colored cursor.
5. Move the index fingertip toward an object. Pinch thumb and index to grab at the midpoint between those fingertips; release to drop it. Moving the pinched point while holding gives the object a simple throw velocity.

The MediaPipe tracker reports left/right hand labels, 21 landmarks, and a filtered pinch state with hysteresis. The sandbox maps all 21 landmarks into the phone-VR reach plane, smooths the joint positions, and builds rounded palm, finger and fingertip geometry at render time. For interaction, an open hand points with the index fingertip; during a pinch, the grab point moves to the midpoint between thumb and index tips. Pinching highlights the thumb and index tips; when a full hand pose is unavailable, the colored cursor remains as a fallback.

## Joy-Con and gamepad controls

1. Pair Joy-Con or another Android-compatible gamepad in Android settings.
2. Open the sandbox and tap **Контроллеры**.
3. Assign one physical controller to the left virtual hand and the other to the right virtual hand.
4. Move the assigned controller's left stick to move its cursor when camera tracking is unavailable.
5. Hold an analog trigger or common digital L1/R1/L2/R2 button to grab; release to drop.
6. Try throwing a held object into the target to score a point.

Analog trigger axes and common digital button codes are recognized. Exact Joy-Con mappings depend on Android firmware and must be checked on the phone. Controller buttons can also trigger grabbing while the camera supplies the cursor position.

## Current scope and limitations

- Stereo rendering uses the saved VR Shinecon viewer profile and phone rotation sensor (3DoF).
- Camera hand positions are mapped to a 2D reach plane with a small relative-depth offset from MediaPipe's image-relative z values. This improves the 3D hand appearance but is not calibrated world depth or 6DoF tracking.
- The hand mesh is a lightweight procedural representation assembled from rounded segments and joints, not a scanned anatomical hand model; sustained rendering cost and tracking stability need to be checked on the target phone.
- Joy-Con sticks are a fallback cursor path, not a calibrated controller pose.
- Physics includes gravity, floor bounce, friction, grab/release, capped throw speed, and a simple target hit check. Objects do not yet collide with one another.
- This sandbox is a test scene, not a playable port of Baldi, Granny, Open Saber, or QuestCraft.

The scene is rendered through OpenGL ES 2.0 and uses the configured preview frame-rate limit. The HUD reports both the selected cap and measured render FPS (averaged over one-second windows). This measures sandbox rendering, not total camera inference or game performance. Actual performance, camera visibility through the VR viewer, and Joy-Con mapping still need a physical-device test on the target phone.


## Adaptive performance budget

QuestPhoneXR now reads a small hardware snapshot once when the sandbox opens: total RAM,
Android app memory class, available processor count, low-RAM-device flag, and the reported
OpenGL ES level. This is a conservative heuristic, not a benchmark and not a compatibility
certificate. It does not use a phone-model whitelist.

The selected preset limits the sandbox render rate and chooses a CameraX target resolution
and minimum MediaPipe inference interval:

| Preset | Render cap | Camera target | Hand tracking |
| --- | ---: | ---: | --- |
| Economy | 20 FPS | 320×240 | Up to 2 hands, throttled inference |
| Balanced | 30 FPS | 480×360 | Up to 2 hands, moderate inference |
| Capable | 45 FPS | 640×480 | Up to 2 hands, faster inference |

The user's viewer FPS setting is never raised by the automatic preset; it is only capped when
the device heuristic recommends a lower ceiling. CameraX target resolution is a request, so
the actual stream size can differ by camera implementation.

The profiler does not run continuous benchmarks or a polling service. MediaPipe still uses
the CPU delegate, a single analyzer executor, and keep-latest backpressure. The inference
interval closes skipped camera frames immediately, avoiding bitmap conversion and model work
for those frames. Device tiers are preliminary estimates; actual sustained FPS, thermals,
and game compatibility must be measured on each physical phone.


## Manual camera processing resolution

The sandbox has a manual camera-resolution control. Width and height can be set independently
(64–1920 px wide and 48–1440 px high), including unusual aspect ratios such as 192×88.
The choice is saved locally and can be reset to the automatic device profile.

CameraX's target resolution is a request and a device may supply a different sensor stream size.
QuestPhoneXR therefore converts the rotated camera frame to the exact selected MediaPipe input
dimensions using aspect-preserving letterboxing rather than stretching the hand. Landmark
coordinates are mapped back out of the letterboxed region before gesture calculations, so
pinch geometry continues to use the full source image. Very small values reduce model input
work but can make hand landmarks less stable; 192×88 is accepted as requested, not promised
to provide reliable tracking in every lighting condition.


## Live 3D hand pose

Each visible hand is rendered from the 21 MediaPipe landmarks. The palm is a flattened volume,
finger bones are rounded 3D segments, and joints are small volumes. The thumb and index fingertips
get a warm highlight while pinching. A low-pass pose filter reduces frame-to-frame landmark jitter;
when tracking is lost, the hand geometry disappears and the existing safe object-release behavior
is retained. MediaPipe z is only relative to the camera image, so depth is visual approximation,
not calibrated physical hand position.


## Hand reach calibration

The sandbox adds a separate saved hand-calibration profile. Horizontal and vertical offsets move
the camera interaction area, width and height scaling adjust reach, and relative-depth gain
controls how much MediaPipe image-relative z affects the rendered hand. Values are clamped to
conservative bounds and are applied to both hands. This is a visual / interaction calibration,
not physical room-scale calibration: the camera does not provide reliable metric world depth.


## Hand tracking performance telemetry

The sandbox HUD separately reports the rendered scene FPS and the observed MediaPipe callback
rate. It also shows end-to-end camera-frame processing latency (frame conversion/resizing plus
the asynchronous MediaPipe callback), the actual CameraX source dimensions, and the configured
MediaPipe input dimensions. Pipeline latency is not a model-only benchmark and callback rate
can be lower than the render rate. Use these values to compare camera settings on the physical
phone under the same lighting and hand movement.
