# EndXR Tracking Providers

## Shared input contract

Every hand tracker terminates at `HandTrackingFrame`. Providers do not write OpenXR actions,
controller rows, gestures, or joints directly. The EndXR tracking input bridge selects one fresh
provider frame and sends it through the existing shared encoder:

```text
MediaPipe / future LiteRT adapter / future GVXRS Dock adapter
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
| LiteRT custom hand-landmark model | PLANNED | Adapter slot only; no independent model is integrated yet |
| GVXRS Dock Station | PLANNED | Data packet contract exists; pairing, live transport, camera and fusion are not connected |

An auxiliary OpenCV motion detector may be useful later for camera/motion assistance, but it is not
treated as a hand-landmark provider until it can emit the shared ordered hand-landmark contract.

## Candidate technologies and correct roles

- **MediaPipe Hand Landmarker** remains the current working 21-landmark hand provider. Its task outputs image landmarks, hand-local world landmarks and handedness.
  <https://developers.google.com/edge/mediapipe/solutions/vision/hand_landmarker>
- **LiteRT 2.x + a custom hand model** is the planned second full hand provider. The model can remain in `.tflite` format; new Android inference work should target the `CompiledModel` API, which is Google's recommended modern runtime for CPU/GPU/NPU acceleration. We will benchmark each accelerator and fall back explicitly; device support is not assumed from the chipset name.
  <https://developers.google.com/edge/litert/android>
- **First LiteRT model candidate — RTMPose-Hand-LiteRT.** It produces the 21 standard wrist/finger keypoints and is distributed under Apache-2.0. The published Android results report about 4 ms GPU inference on Pixel 8a for a 256×256 FP16 model (~28 MB), but the same page says the CPU XNNPACK path declined that graph. Its preprocessing is top-down: a centered single-hand crop is expected. Therefore EndXR still needs a reliable ROI/crop stage, and neither the Pixel timing nor GPU support can be assumed on Redmi Note 13 / Adreno 610.
  <https://huggingface.co/litert-community/RTMPose-Hand-LiteRT>
- **Small quantized model candidate — STMicro hand_landmarks.** The model card describes an INT8 TFLite model with RGB input and 21 keypoints under Apache-2.0. Its card gives a simplified `(x,y,confidence)` output description, but ST's `hand_spe` prediction implementation uses four output tensors (raw landmarks, normalized landmarks, hand side and hand-presence probability) and a model-specific postprocessor. Treat the actual model file's tensor names, shapes, quantization parameters and coordinate frame as authoritative; do not decode it as a flat 63-float tensor until that signature is verified. The published ~20.75 ms result is from an STM32N6 NPU, not an Android phone. It is a candidate to inspect and benchmark, not an established fast Android option.
  <https://huggingface.co/STMicroelectronics/hand_landmarks>
- **ML Kit Pose Detection** is an auxiliary body/wrist source, not a direct replacement for articulated hand tracking. Its 33-point body skeleton includes hand points, but it does not provide the 21 finger-joint landmarks expected by the hand-frame contract. The API is currently beta and can change.
  <https://developers.google.com/ml-kit/vision/pose-detection>
- **OpenCV** can assist with motion, foreground/ROI detection and reacquisition, but it is not a hand-landmark model by itself.
- **GVXRS Dock Station** can provide a second camera viewpoint. It still needs time-synchronized packets, camera calibration and a tested fusion model before its coordinates can be merged with the primary phone.

Android's NNAPI was deprecated in Android 15. New accelerator work should therefore not depend on NNAPI as the long-term path; LiteRT's current Android guidance centers on `CompiledModel`.
<https://developer.android.com/ndk/guides/neuralnetworks/migration-guide>

## LiteRT output decoding

**Primary candidate: RTMPose-Hand-LiteRT.** Its published contract is:
- Input: `[1,3,256,256]`, RGB, NCHW, ImageNet mean/std normalization.
- Outputs: `simcc_x[1,21,512]` and `simcc_y[1,21,512]`; each keypoint coordinate is `argmax(bin) / 2`.
- It predicts one centered hand; it does not expose a hand-presence score or handedness output.
- The model card reports 28 MB FP16 and about 4 ms GPU inference on Pixel 8a. It also reports that CPU XNNPACK declined the graph. Those results do not predict performance or GPU compatibility on Redmi Note 13 / Adreno 610.
- The model is Apache-2.0.

EndXR now has a reusable square ROI crop, a caller-buffer RGB preprocessor, an RTMPose ImageNet-normalized NCHW path, and a SimCC decoder. The decoder requires an upstream ROI/presence confidence before publishing a frame and labels handedness as UNKNOWN; it does not mistake a confident argmax for proof that a hand is present. This makes the model a top-down landmark refiner, not a standalone full-frame hand detector.

**Secondary candidate: ST `hand_spe`.** ST's reference postprocessor handles four outputs (hand type, normalized landmarks, presence probability, image-space landmarks), while the model card summarizes the output as a single `[1,Kx3]` tensor. These descriptions conflict, and the actual model file metadata has not yet been verified. The separate ST decoder in EndXR is only an adapter contract for the four-output reference pipeline; do not bind it to a downloaded model until its actual tensor names, shapes, dtypes and quantization are inspected.

The model runners are not active yet. No model binary is bundled, no LiteRT dependency has been added, and no device inference has been claimed. The next step is to validate the selected model artifact and its exact tensor signature, then add a background-thread `CompiledModel` runner with a GPU capability probe and a CPU/MediaPipe fallback. LiteRT resources and tensor buffers must be created once and reused rather than rebuilt for every camera frame.

## Selection rules

- AUTO considers only READY or EXPERIMENTAL providers that have submitted a fresh frame.
- AUTO scores measured frame quality (55%), source pipeline latency against a 200 ms budget
  (25%), sustained frame cadence against a 30 FPS target (15%), and a small configured
  provider preference (5%). Cadence is measured from receiver-side uptime using a rolling
  30-interval window, so a single fast inference does not outweigh a source that delivers
  fewer usable frames per second. A provider without enough accepted samples starts with a
  neutral cadence score until its rate can be measured.
- Each provider tracks accepted/rejected observations, most recent frame age, latest and average
  pipeline latency, rolling average frame interval, estimated FPS, latest quality score, and its
  most recent rejection reason. These values are exported in EndXR diagnostics for device-by-device comparisons.
- MediaPipe currently reports a neutral 0.5 quality baseline because its detection confidence is
  not yet carried into the shared frame. This is not a measured accuracy score. Future adapters
  should provide calibrated per-frame confidence where their model exposes it.
- A provider must emit finite ordered landmarks and non-negative pipeline timing. Incomplete
  landmark sets, invalid quality values, duplicate/out-of-order timestamps, and frames exceeding
  the provider's declared hand limit are rejected and counted.
- MANUAL accepts only an available provider and never silently substitutes another tracker.
- Frames older than 300 ms are not exposed. Providers use receiver-side uptime for freshness
  and frame-cadence measurement; sender timestamps remain source-local and must not be compared
  across devices without clock synchronization.
- Out-of-order and duplicate timestamps, invalid quality values, and frames exceeding the
  provider's declared hand limit are rejected.
- When a provider becomes unavailable its cached frame is discarded.

## Next integration work

1. Add device capability / benchmark probes and provider-specific model loading.
2. Add reusable square ROI crop geometry from a detector's 21 landmarks, then verify a selected
   top-down model's exact input/output tensor signature and coordinate mapping.
3. Implement the LiteRT adapter only after selecting and validating an actual model.
3. Connect GVXRS Dock pairing, authenticated transport and calibrated landmark conversion.
4. Add a settings surface for AUTO / MANUAL selection and live provider health diagnostics.
5. Add a device capability probe and benchmark runner for camera, CPU/GPU delegate, memory,
   thermals, sustained latency, and tracking-loss rate.
6. Compare thermal load, battery use, occlusion recovery and false-loss behavior on real phones.

Model acceptance must be based on the target device, not a benchmark from another SoC. For each
candidate, record cold-start and warm inference latency (p50/p95), end-to-end camera-to-landmark
latency, frame-loss rate, confidence stability during occlusion, RAM, thermal drift and battery
cost. Test CPU and each supported accelerator separately. Do not make the new adapter active by
default until its quality and sustained performance beat or complement the MediaPipe baseline.

No alternative tracker is claimed to be operational until its adapter is registered as READY and
tested on physical hardware.
