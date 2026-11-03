# Device compatibility diagnostics

QuestPhoneXR performs a lightweight, one-shot capability read from Android. It does not run a benchmark, stress the GPU, change settings, or block the application.

## Levels

- **Suitable by basic signals**: the current sensor and resource checks look reasonable.
- **Suitable with limitations**: one or more capabilities are missing, weaker, or not reported.
- **Not recommended for heavy XR**: memory or CPU signals suggest that a demanding workload may be unstable or slow.
- **Phone-VR tracking unavailable**: the current app path did not find Android's game rotation-vector or rotation-vector sensor.

The last state applies to the current phone-VR head-tracking path, not to the whole application. APK import, the library, and other available non-head-tracked features remain accessible. Devices are not rejected based on the result.

## What is checked

- Android game rotation vector / rotation vector, gyroscope, and accelerometer presence.
- OpenGL ES version reported by Android.
- Vulkan API version and hardware level reported through Android package features.
- Total and currently available RAM, application memory class, and Android low-RAM classification.
- Logical processor count, process bitness, and supported ABIs.

The rotation-vector sensor is checked separately from the physical gyroscope because the current VR preview subscribes to TYPE_GAME_ROTATION_VECTOR and falls back to TYPE_ROTATION_VECTOR. A device may expose a rotation-vector sensor without exposing a separate gyroscope; that is reported as a limitation rather than an automatic failure.

## What is not yet verified

The Android feature list is not a GPU benchmark and does not enumerate every Vulkan device extension. Android's Vulkan 1.1+ hardware-version declaration also signals support for the Android hardware-buffer external-memory extension and SYNC_FD semaphore/fence handles. That does **not** establish support for every FD extension or synchronization path required by a particular compositor, such as VK_KHR_external_memory_fd and VK_KHR_external_semaphore_fd; those still need native Vulkan enumeration. The screen also does not verify Vulkan driver stability, thermal performance, sustained frame rate, OpenXR instance creation, compositor submission, or game compatibility.

A positive Android feature report is only a hardware preflight. QuestPhoneXR now also runs an optional, one-shot native Vulkan probe when the user opens the detailed compatibility report. It creates a Vulkan instance, enumerates physical devices and device extensions, records the physical-device API/driver identifiers, and queries import/export support for selected external buffer, semaphore and fence handle types when Vulkan 1.1 core queries are available.

The native probe does not create a rendering device, allocate/import actual shared resources, submit frames, benchmark the GPU, or test a compositor. A positive extension/handle query is not proof that a real XR compositor can use the path. The probe is optional: if the native library or Vulkan loader is unavailable, the rest of the application remains usable and the diagnostic reports the failure explicitly.

A real OpenXR runtime test on the target phone is still required before claiming that the phone can run a particular XR workload.
