#include "gvxrs_core.h"

#include <cstddef>
#include <cstdint>

static_assert(sizeof(GvxrsControllerInput) == 24);
static_assert(sizeof(GvxrsQuaternion) == 16);
static_assert(sizeof(GvxrsPoseInput) == 32);
static_assert(sizeof(GvxrsCoreStatus) == 8);
static_assert(sizeof(GvxrsCoreFrameTiming) == 32);
static_assert(sizeof(GvxrsCoreFrameResult) == 40);
static_assert(sizeof(GvxrsCoreSnapshot) == 72);
static_assert(offsetof(GvxrsCoreSnapshot, frames_started) == 24);
static_assert(offsetof(GvxrsCoreSnapshot, last_error_code) == 64);

namespace {

bool is_ok(GvxrsCoreStatus status) {
    return status.ok == 1 && status.error_code == GVXRS_CORE_ERROR_NONE;
}

int fail_and_destroy(uint64_t handle, int error) {
    (void)gvxrs_runtime_core_destroy(handle);
    return error;
}

}  // namespace

// This symbol is exported only from a CI-generated smoke-test shared object.
// It is not part of the Android app or the production OpenXR runtime.
extern "C" __attribute__((visibility("default"))) int gvxrs_android_link_smoke() {
    const uint64_t handle = gvxrs_runtime_core_create();
    if (handle == 0) {
        return 1;
    }

    if (!is_ok(gvxrs_runtime_core_initialize(handle, 640, 720, 30))) {
        return fail_and_destroy(handle, 2);
    }
    if (!is_ok(gvxrs_runtime_core_create_session(handle))) {
        return fail_and_destroy(handle, 3);
    }
    if (!is_ok(gvxrs_runtime_core_begin_session(handle))) {
        return fail_and_destroy(handle, 4);
    }

    const GvxrsCoreFrameResult frame =
        gvxrs_runtime_core_wait_frame(handle, INT64_C(1000000000));
    if (!is_ok(frame.status) || frame.timing.frame_index != 1 ||
        frame.timing.predicted_display_period_ns != INT64_C(33333333)) {
        return fail_and_destroy(handle, 5);
    }

    if (!is_ok(gvxrs_runtime_core_end_frame(handle, frame.timing, 1))) {
        return fail_and_destroy(handle, 6);
    }

    const GvxrsCoreSnapshot snapshot = gvxrs_runtime_core_snapshot(handle);
    if (!is_ok(snapshot.status) || snapshot.state != 3 ||
        snapshot.frames_started != 1 || snapshot.submitted_frames != 1 ||
        snapshot.dropped_frames != 0 || snapshot.last_error_code != 0) {
        return fail_and_destroy(handle, 7);
    }

    if (!is_ok(gvxrs_runtime_core_request_stop(handle))) {
        return fail_and_destroy(handle, 8);
    }
    if (!is_ok(gvxrs_runtime_core_destroy_session(handle))) {
        return fail_and_destroy(handle, 9);
    }
    if (!is_ok(gvxrs_runtime_core_shutdown(handle))) {
        return fail_and_destroy(handle, 10);
    }
    if (!is_ok(gvxrs_runtime_core_destroy(handle))) {
        return 11;
    }

    return 0;
}
