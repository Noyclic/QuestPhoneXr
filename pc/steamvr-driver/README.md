# EndVR SteamVR Driver — EV-1 prototype

This is the first native OpenVR driver slice. It registers a virtual HMD named
**EndVR Phone HMD (prototype)** and publishes a stationary, valid pose so that
SteamVR can enumerate the device.

## Important limitations

- This is a driver bring-up prototype, not a usable wireless headset yet.
- Pose is currently stationary; phone IMU/network tracking is not connected.
- The display reports a provisional 1920x1080 stereo target with identity
  distortion. Real per-eye optics, lens calibration and phone panel geometry
  are not configured.
- The separate EndVR Android Client can display the PC host's generated MJPEG
  test pattern, but this driver does not send SteamVR eye frames to that client.
- Do not install this beside another active HMD and expect it to become the
  primary headset. SteamVR may choose another device.

## Build

The CMake project fetches Valve's OpenVR source at configure time.

```bash
cmake -S pc/steamvr-driver -B build/endvr-driver -DCMAKE_BUILD_TYPE=Release
cmake --build build/endvr-driver --config Release
```

The output is `driver_endvr.dll` on Windows or `driver_endvr.so` on Linux.
SteamVR driver packaging/registration is not automated yet. Use SteamVR's
`vrpathreg adddriver` only after a platform build and manual review.

## Next

1. Add a local pose-ingest endpoint and stale-pose timeout.
2. Connect the Android client to the PC host and publish phone orientation.
3. Add the wireless final-frame path after validating the HMD registration.
4. Add Linux and Windows install/uninstall packaging with safe rollback.
