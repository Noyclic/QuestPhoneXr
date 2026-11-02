# VR Shinecon phone-viewer calibration

The phone stereo preview provides a basic calibration profile saved locally on the device.

## Available controls

- **IPD:** adjusts the virtual eye separation from 50 to 75 mm. The default is 64 mm.
- **Field of view:** adjusts the preview projection from 70 to 100 degrees. The default is 86 degrees.
- **Swap eyes:** reverses the left/right preview images for viewer setups that need it.
- **Recenter:** takes the current phone orientation as the neutral forward direction.

The values are starting controls, not a measurement of the user's anatomy or the exact optics of a specific Shinecon revision. Calibration should be performed while the phone is mounted in the viewer and the user is looking straight ahead.

## Known limitations

This stage is still a diagnostic 3DoF phone-VR preview. It does not yet implement lens-distortion correction, chromatic-aberration correction, positional 6DoF tracking, or per-headset optical profiles. Changing FOV does not correct optical distortion. The preview does not establish performance or comfort on a physical device until tested on that device.

The current profile is stored locally using Android SharedPreferences. No profile is uploaded or shared.
