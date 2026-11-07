#ifndef GVXRS_CORE_H
#define GVXRS_CORE_H

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Plain C-compatible values: no pointers, references, or Rust allocations. */
typedef struct GvxrsControllerInput {
    uint32_t active;
    float trigger;
    float grip;
    float stick_x;
    float stick_y;
    uint32_t buttons;
} GvxrsControllerInput;

typedef struct GvxrsQuaternion {
    float x;
    float y;
    float z;
    float w;
} GvxrsQuaternion;

typedef struct GvxrsVector3 {
    float x;
    float y;
    float z;
} GvxrsVector3;

typedef struct GvxrsPoseInput {
    uint32_t active;
    GvxrsQuaternion orientation;
    GvxrsVector3 position;
} GvxrsPoseInput;

typedef struct GvxrsHeadOrientation {
    uint32_t active;
    GvxrsQuaternion orientation;
} GvxrsHeadOrientation;

/*
 * Stateless input sanitizers ported from the current GVXRS Mobile C++
 * implementation. These functions do not publish state to the OpenXR runtime.
 */
GvxrsControllerInput gvxrs_sanitize_controller_input(
    uint32_t active,
    float trigger,
    float grip,
    float stick_x,
    float stick_y,
    uint32_t buttons
);

GvxrsPoseInput gvxrs_sanitize_hand_pose(
    uint32_t active,
    GvxrsVector3 position,
    GvxrsQuaternion orientation
);

GvxrsHeadOrientation gvxrs_sanitize_head_orientation(
    GvxrsQuaternion orientation
);


/* Rust lifecycle-core ABI. Handles are opaque integer tokens; zero is invalid. */
typedef struct GvxrsCoreStatus {
    uint32_t ok;         /* 1 = success, 0 = failure */
    uint32_t error_code; /* stable code from GvxrsCoreError */
} GvxrsCoreStatus;

typedef enum GvxrsCoreError {
    GVXRS_CORE_ERROR_NONE = 0,
    GVXRS_CORE_ERROR_INVALID_HANDLE = 1,
    GVXRS_CORE_ERROR_INITIALIZE_STATE = 2,
    GVXRS_CORE_ERROR_INVALID_DIMENSIONS = 3,
    GVXRS_CORE_ERROR_INVALID_FPS = 4,
    GVXRS_CORE_ERROR_CREATE_SESSION_STATE = 5,
    GVXRS_CORE_ERROR_BEGIN_SESSION_STATE = 6,
    GVXRS_CORE_ERROR_WAIT_FRAME_STATE = 7,
    GVXRS_CORE_ERROR_INVALID_TIMESTAMP = 8,
    GVXRS_CORE_ERROR_FRAME_ALREADY_ACTIVE = 9,
    GVXRS_CORE_ERROR_FRAME_INDEX_EXHAUSTED = 10,
    GVXRS_CORE_ERROR_PREDICTED_TIME_OVERFLOW = 11,
    GVXRS_CORE_ERROR_FRAME_DEADLINE_OVERFLOW = 12,
    GVXRS_CORE_ERROR_END_FRAME_STATE = 13,
    GVXRS_CORE_ERROR_FRAME_TOKEN_MISMATCH = 14,
    GVXRS_CORE_ERROR_FRAME_TIMING_MISMATCH = 15,
    GVXRS_CORE_ERROR_REQUEST_STOP_STATE = 16,
    GVXRS_CORE_ERROR_DESTROY_SESSION_STATE = 17,
    GVXRS_CORE_ERROR_MARK_LOST_STATE = 18,
    GVXRS_CORE_ERROR_UNKNOWN = 255
} GvxrsCoreError;

typedef struct GvxrsCoreFrameTiming {
    uint64_t frame_index;
    int64_t predicted_display_time_ns;
    int64_t predicted_display_period_ns;
    int64_t frame_deadline_ns;
} GvxrsCoreFrameTiming;

typedef struct GvxrsCoreFrameResult {
    GvxrsCoreStatus status;
    GvxrsCoreFrameTiming timing;
} GvxrsCoreFrameResult;

typedef struct GvxrsCoreSnapshot {
    GvxrsCoreStatus status;
    uint32_t state; /* Cold=0, Initialized=1, SessionReady=2, Running=3, Stopping=4, Lost=5 */
    uint32_t width;
    uint32_t height;
    uint32_t target_fps;
    uint64_t frames_started;
    uint64_t submitted_frames;
    uint64_t dropped_frames;
    uint64_t late_frames;
    int64_t last_lateness_ns;
    uint32_t last_error_code;
} GvxrsCoreSnapshot;

uint64_t gvxrs_runtime_core_create(void);
GvxrsCoreStatus gvxrs_runtime_core_destroy(uint64_t handle);
GvxrsCoreStatus gvxrs_runtime_core_initialize(
    uint64_t handle, uint32_t width, uint32_t height, uint32_t target_fps
);
GvxrsCoreStatus gvxrs_runtime_core_create_session(uint64_t handle);
GvxrsCoreStatus gvxrs_runtime_core_begin_session(uint64_t handle);
GvxrsCoreFrameResult gvxrs_runtime_core_wait_frame(uint64_t handle, int64_t monotonic_now_ns);
GvxrsCoreStatus gvxrs_runtime_core_end_frame(
    uint64_t handle, GvxrsCoreFrameTiming timing, uint32_t submitted
);
GvxrsCoreStatus gvxrs_runtime_core_request_stop(uint64_t handle);
GvxrsCoreStatus gvxrs_runtime_core_destroy_session(uint64_t handle);
GvxrsCoreStatus gvxrs_runtime_core_mark_lost(uint64_t handle);
GvxrsCoreStatus gvxrs_runtime_core_shutdown(uint64_t handle);
GvxrsCoreSnapshot gvxrs_runtime_core_snapshot(uint64_t handle);


#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* GVXRS_CORE_H */
