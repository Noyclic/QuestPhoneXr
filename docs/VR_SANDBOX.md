# Phone VR sandbox

The VR sandbox is a first interactive scene for testing QuestPhoneXR's phone-based stereo viewer and Android controller path.

## Scene

- Two colored virtual controller cursors.
- Three movable objects: two cubes and one ball.
- One simple target object.
- A floor plane with gravity, a small bounce, and friction.
- A button to spawn more cubes and a reset button.

## Controls

1. Pair Joy-Con or another Android-compatible gamepad in Android settings.
2. Open the sandbox and tap **Контроллеры**.
3. Assign one physical controller to the left virtual hand and the other to the right virtual hand.
4. Move the assigned controller's left stick to move its cursor.
5. Hold a trigger or shoulder button to grab the nearest object in reach. Release to drop it; moving the cursor while holding gives the object a simple throw velocity.
6. Use **Перецентрировать** to reset the neutral phone orientation.

Analog trigger axes and common digital L1/R1/L2/R2 key codes are recognized. Exact Joy-Con mappings depend on the Android firmware and must be checked on the phone.

## Current scope and limitations

- Stereo rendering uses the saved VR Shinecon viewer profile and phone rotation sensor (3DoF).
- Controller cursors currently use a 2D reach plane driven by sticks. This is not 6DoF controller tracking.
- Camera hand tracking and pinch-to-grab are not yet connected to this sandbox scene.
- Physics is intentionally light: gravity, floor bounce, friction, grab/release, and a capped throw speed. Objects do not yet collide with one another or with the target.
- This sandbox is a test scene, not a playable port of Baldi, Granny, Open Saber, or QuestCraft.

The scene is rendered through OpenGL ES 2.0 and uses the configured preview frame-rate limit. Actual performance still needs a physical-device test on the target phone.
