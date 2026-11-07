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

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* GVXRS_CORE_H */
