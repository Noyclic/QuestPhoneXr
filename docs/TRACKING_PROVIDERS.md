# EndXR Tracking Providers

## Shared input contract

Every hand tracker terminates at `HandTrackingFrame`. Providers do not write OpenXR actions,
controller rows, gestures, or joints directly. The EndXR tracking input bridge selects one fresh
provider frame and sends it through the existing shared encoder:

```text
MediaPipe / future TFLite adapter / future GVXRS Dock adapter
                         |
                         v
                 HandTrackingFrame
                         |
                         v
              TrackingProviderManager
             (AUTO or MANUAL selection)
                         |
                         v
              EndXrHandInputEncoder
                         |
                         v
              EndXR input snapshot
```

This keeps app-facing input stable when the tracking implementation changes.

## Provider state

| Provider | Current state | Notes |
|---|---|---|
| MediaPipe | READY | Existing CameraX + Hand Landmarker path; remains the default |
| Custom TFLite model | PLANNED | Adapter slot only; no independent model is integrated yet |
| GVXRS Dock Station | PLANNED | Data packet contract exists; pairing, live transport, camera and fusion are not connected |

An auxiliary OpenCV motion detector may be useful later for camera/motion assistance, but it is not
treated as a hand-landmark provider until it can emit the shared ordered hand-landmark contract.

## Selection rules

- AUTO considers only READY or EXPERIMENTAL providers that have submitted a fresh frame.
- AUTO ranks candidates using the provider's configured priority and per-frame quality score.
  These values are policy inputs, not proof that one model is faster or more accurate on a device.
- MANUAL accepts only an available provider and never silently substitutes another tracker.
- Frames older than 300 ms are not exposed. Providers use receiver-side uptime for freshness;
  sender timestamps remain source-local and must not be compared across devices without clock
  synchronization.
- Out-of-order and duplicate timestamps, invalid quality values, and frames exceeding the
  provider's declared hand limit are rejected.
- When a provider becomes unavailable its cached frame is discarded.

## Next integration work

1. Add device capability / benchmark probes and provider-specific model loading.
2. Implement the TFLite adapter only after selecting and validating an actual model.
3. Connect GVXRS Dock pairing, authenticated transport and calibrated landmark conversion.
4. Add a settings surface for AUTO / MANUAL selection and per-provider diagnostics.
5. Compare latency, stability, thermal load, battery use and false-loss behavior on real phones.

No alternative tracker is claimed to be operational until its adapter is registered as READY and
tested on physical hardware.
