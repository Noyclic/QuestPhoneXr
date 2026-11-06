# EndVR Android Client — EV-0 phone preview

This is a separate Android app for validating the first PC-to-phone video path. It does not depend on EndXR or Android RuntimeBroker.

## What it does

- Connects to the EndVR PC host over a trusted local Wi-Fi network.
- Receives the current synthetic MJPEG test pattern over HTTP.
- Decodes JPEG frames on Android and presents the left and right halves side-by-side for a phone VR viewer.
- Shows displayed FPS and the latest JPEG decode duration.
- Retries a dropped host connection and provides an explicit Stop action.

## What it does not do yet

- Capture the Windows or Linux desktop or a SteamVR eye surface.
- Use WebRTC, H.264, hardware video decoding or adaptive bitrate.
- Send phone head tracking or hand/controller input back to the PC.
- Register as a SteamVR/OpenXR HMD or launch a game.
- Apply Shinecon lens distortion/IPD calibration.

This is an EV-0 transport/decoder smoke test, not a finished wireless headset.

## Build and install

Build the APK with:

```bash
gradle --no-daemon :endvr-client:testDebugUnitTest
gradle --no-daemon :endvr-client:assembleDebug
```

APK: `endvr-client/build/outputs/apk/debug/endvr-client-debug.apk`.

## Try it on the phone

1. Connect the PC and phone to the same trusted Wi-Fi network. Do not use a guest network with client isolation.
2. On the PC, install/run the host from `pc/endvr-host`:

   ```bash
   python -m pip install -e .
   endvr-host --bind 0.0.0.0 --width 640 --height 360 --fps 30
   ```

3. Find the PC's local IPv4 address (for example, `192.168.1.20`). Allow inbound TCP port 8765 in the private-network firewall if prompted.
4. Install and open EndVR Client. Enter `http://192.168.1.20:8765/stream.mjpg`, replacing the example address with the PC's actual LAN IP, then press Connect.
5. The left half should show the blue eye panel and the right half the red eye panel, with a moving yellow marker. Tap the preview to hide/show diagnostics; press Stop or Android Back to disconnect.

The prototype uses unencrypted HTTP and has no pairing/authentication. Keep it on a trusted private LAN, do not port-forward the port, and stop the host after testing.
