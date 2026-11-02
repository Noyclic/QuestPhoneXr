# Phone VR sandbox

The VR sandbox is the first interactive scene for testing QuestPhoneXR's phone-based stereo viewer, camera hand tracking, and Android controller input.

## Scene

- Two colored virtual hand/controller cursors.
- Two cubes, one ball, and one target object at startup.
- A floor plane with gravity, a small bounce, and friction.
- A target that awards a point when a movable object reaches it; scored objects respawn at their starting positions.
- Buttons to spawn more cubes, assign controllers, recenter the phone, and reset the scene.

## Camera hand controls

1. Allow camera permission when the sandbox opens.
2. The app uses the rear camera when available; it falls back to the front camera if needed.
3. Keep your hands visible to the camera's field of view.
4. Move a hand to move its colored cursor.
5. Pinch the thumb and index finger to grab the nearest object. Release the pinch to drop it; moving your hand while holding gives the object a simple throw velocity.

The MediaPipe tracker reports left/right hand labels, 21 landmarks, and a filtered pinch state with hysteresis. The sandbox maps the wrist's normalized image position to a 2D reach plane and applies a small low-pass filter to reduce cursor jitter.

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
- Camera hand positions are mapped to a 2D reach plane; depth is currently a fixed scene layer. This is not 6DoF tracking.
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
