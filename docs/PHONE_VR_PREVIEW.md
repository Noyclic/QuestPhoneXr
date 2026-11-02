# Phone VR preview for VR Shinecon

## What this stage implements

- A native Android OpenGL ES 2.0 diagnostic scene.
- Side-by-side left/right eye viewports.
- A small fixed eye separation for a first stereo-depth check.
- Phone orientation from TYPE_GAME_ROTATION_VECTOR, falling back to TYPE_ROTATION_VECTOR.
- Immersive landscape mode and keep-screen-on while the preview is active.

## What this stage does not implement

- Viewer-specific lens distortion correction.
- A QR/official Cardboard viewer profile.
- Per-viewer IPD and lens alignment calibration.
- 6DoF positional tracking or camera/SLAM fusion.
- An OpenXR runtime, OpenXR session, or Quest APK loader.
- Game execution or game compatibility beyond the separate library metadata.

This is a diagnostic stereo scene to verify that both eyes receive distinct views and that phone rotation changes the camera orientation. It is not yet a comfortable or calibrated final VR viewer. Shinecon models differ, so lens parameters must be calibrated after identifying the exact headset model.
