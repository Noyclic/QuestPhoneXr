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
- AUTO scores measured frame quality (65%), source pipeline latency against a 200 ms budget
  (30%), and a small configured provider preference (5%). This makes measured performance
  more important than a hard-coded preference.
- Each provider tracks accepted/rejected observations, most recent frame age, latest and average
  pipeline latency, latest quality score, and its most recent rejection reason. These values are
  exported in EndXR diagnostics to support device-by-device comparisons.
- MediaPipe currently reports a neutral 0.5 quality baseline because its detection confidence is
  not yet carried into the shared frame. This is not a measured accuracy score. Future adapters
  should provide calibrated per-frame confidence where their model exposes it.
- A provider must emit finite ordered landmarks and non-negative pipeline timing. Incomplete
  landmark sets, invalid quality values, duplicate/out-of-order timestamps, and frames exceeding
  the provider's declared hand limit are rejected and counted.
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
4. Add a settings surface for AUTO / MANUAL selection and live provider health diagnostics.
5. Add a device capability probe and benchmark runner for camera, CPU/GPU delegate, memory,
   thermals, sustained latency, and tracking-loss rate.
6. Compare thermal load, battery use, occlusion recovery and false-loss behavior on real phones.

No alternative tracker is claimed to be operational until its adapter is registered as READY and
tested on physical hardware.
