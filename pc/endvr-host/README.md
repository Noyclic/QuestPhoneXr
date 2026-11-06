# EndVR PC Host — EV-0

This is a PC-only implementation slice. It does not modify Android runtime, IPC, camera, MediaPipe, OpenXR input or tracking code.

## Implemented
- Python 3.10+ host for Linux and Windows.
- Generated moving JPEG test pattern with distinct left/right eye colors.
- MJPEG-over-HTTP preview at `/stream.mjpg`.
- `/health` and `/api/v1/status` JSON diagnostics.
- Connected-client and generated-frame counters.
- Unit tests for JPEG framing, metrics and invalid configuration.

This is a smoke-test transport, **not** the final VR stream. It does not implement desktop capture, WebRTC, hardware encoding, SteamVR, or prove 20–30 FPS on the target laptop. WebRTC remains the candidate for the actual media path.

## Run

Install Python 3.10+ and run from this directory:

```bash
python -m venv .venv
# Linux: source .venv/bin/activate
# Windows PowerShell: .venv\\Scripts\\Activate.ps1
python -m pip install --upgrade pip
python -m pip install -e .
python -m unittest discover -s tests -v
endvr-host
```

Open http://127.0.0.1:8765/ on the PC. For a phone on the same trusted Wi-Fi, run `endvr-host --bind 0.0.0.0` and open http://PC-LAN-IP:8765/ on the phone. This prototype uses unencrypted HTTP: trusted private LAN only, no port forwarding, and stop it after testing.

Optional low-load mode: `endvr-host --bind 0.0.0.0 --width 640 --height 360 --fps 30`.

## Next PC milestones
1. Define a capture-source interface and timestamped frames.
2. Linux capture: PipeWire/Wayland, with explicit X11 fallback.
3. Windows Graphics Capture backend behind the same interface.
4. Replace MJPEG preview with negotiated low-latency WebRTC (H.264 baseline first).
5. Measure capture-to-display latency and queue age before SteamVR integration.
