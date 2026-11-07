#include "endxr_runtime_core.hpp"
#include "gvxrs_core.h"

#include <cstdint>
#include <cstdlib>
#include <iostream>
#include <string>

namespace {
void expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}

void expectBoth(bool cpp_ok, GvxrsCoreStatus rust_status, bool expected_ok,
                std::uint32_t expected_error, const char* message) {
    expect(cpp_ok == expected_ok, message);
    expect((rust_status.ok != 0u) == expected_ok, message);
    expect(rust_status.error_code == (expected_ok ? 0u : expected_error),
           "Rust C ABI returned the expected stable error code");
}

void compareSnapshot(const endxr::RuntimeCore& cpp, std::uint64_t rust_handle) {
    const auto left = cpp.snapshot();
    const auto right = gvxrs_runtime_core_snapshot(rust_handle);
    expect(right.status.ok == 1u, "Rust snapshot handle is valid");
    expect(static_cast<std::uint32_t>(left.state) == right.state,
           "C++ and Rust runtime states match");
    expect(left.config.width == right.width, "C++ and Rust widths match");
    expect(left.config.height == right.height, "C++ and Rust heights match");
    expect(left.config.target_fps == right.target_fps, "C++ and Rust target FPS match");
    expect(left.frames_started == right.frames_started, "frame-start counts match");
    expect(left.submitted_frames == right.submitted_frames, "submitted-frame counts match");
    expect(left.dropped_frames == right.dropped_frames, "dropped-frame counts match");
    expect(left.late_frames == right.late_frames, "late-frame counts match");
    expect(left.last_lateness_ns == right.last_lateness_ns, "frame lateness matches");
    expect(left.last_error.empty() == (right.last_error_code == 0u),
           "both implementations agree whether the last operation had an error");
}

GvxrsCoreFrameTiming toRustTiming(const endxr::FrameTiming& frame) {
    return GvxrsCoreFrameTiming{
        frame.frame_index,
        frame.predicted_display_time_ns,
        frame.predicted_display_period_ns,
        frame.frame_deadline_ns
    };
}

void testLifecycleAndFrameClockParity() {
    endxr::RuntimeCore cpp;
    const std::uint64_t rust = gvxrs_runtime_core_create();
    expect(rust != 0u, "create independent Rust runtime core");

    std::string error;
    auto cpp_ok = cpp.initialize(endxr::RuntimeConfig{32, 720, 30}, &error);
    auto rust_status = gvxrs_runtime_core_initialize(rust, 32, 720, 30);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_INVALID_DIMENSIONS,
               "both implementations reject invalid dimensions");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.initialize(endxr::RuntimeConfig{1280, 720, 121}, &error);
    rust_status = gvxrs_runtime_core_initialize(rust, 1280, 720, 121);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_INVALID_FPS,
               "both implementations reject invalid frame rate");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.initialize(endxr::RuntimeConfig{1280, 720, 30}, &error);
    rust_status = gvxrs_runtime_core_initialize(rust, 1280, 720, 30);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations initialize");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.createSession(&error);
    rust_status = gvxrs_runtime_core_create_session(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations create a session");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.beginSession(&error);
    rust_status = gvxrs_runtime_core_begin_session(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations begin a session");
    compareSnapshot(cpp, rust);

    endxr::FrameTiming cpp_frame{};
    cpp_ok = cpp.waitFrame(1'000'000'000LL, &cpp_frame, &error);
    const auto rust_frame = gvxrs_runtime_core_wait_frame(rust, 1'000'000'000LL);
    expectBoth(cpp_ok, rust_frame.status, true, 0u, "both implementations start the first frame");
    expect(cpp_frame.frame_index == rust_frame.timing.frame_index,
           "first frame index matches");
    expect(cpp_frame.predicted_display_time_ns == rust_frame.timing.predicted_display_time_ns,
           "first predicted display time matches");
    expect(cpp_frame.predicted_display_period_ns == rust_frame.timing.predicted_display_period_ns,
           "first display period matches");
    expect(cpp_frame.frame_deadline_ns == rust_frame.timing.frame_deadline_ns,
           "first frame deadline matches");
    compareSnapshot(cpp, rust);

    endxr::FrameTiming ignored{};
    cpp_ok = cpp.waitFrame(1'000'000'001LL, &ignored, &error);
    rust_status = gvxrs_runtime_core_wait_frame(rust, 1'000'000'001LL).status;
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_FRAME_ALREADY_ACTIVE,
               "both implementations reject a second active frame");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.endFrame(cpp_frame, true, &error);
    rust_status = gvxrs_runtime_core_end_frame(rust, toRustTiming(cpp_frame), 1u);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations submit the first frame");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.waitFrame(1'000'000'001LL, &cpp_frame, &error);
    const auto second = gvxrs_runtime_core_wait_frame(rust, 1'000'000'001LL);
    expectBoth(cpp_ok, second.status, true, 0u, "both implementations start the second frame");
    expect(cpp_frame.frame_index == second.timing.frame_index,
           "second frame index matches");
    expect(cpp_frame.predicted_display_time_ns == second.timing.predicted_display_time_ns,
           "second predicted display time matches");
    expect(cpp_frame.frame_deadline_ns == second.timing.frame_deadline_ns,
           "second frame deadline matches");

    cpp_ok = cpp.endFrame(cpp_frame, false, &error);
    rust_status = gvxrs_runtime_core_end_frame(rust, second.timing, 0u);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations count a dropped frame");
    compareSnapshot(cpp, rust);

    expect(gvxrs_runtime_core_destroy(rust).ok == 1u,
           "release Rust lifecycle test handle");
}

void testErrorAndRecoveryParity() {
    endxr::RuntimeCore cpp;
    const std::uint64_t rust = gvxrs_runtime_core_create();
    expect(rust != 0u, "create second Rust runtime core");
    std::string error;
    endxr::FrameTiming cpp_frame{};

    auto cpp_ok = cpp.requestStop(&error);
    auto rust_status = gvxrs_runtime_core_request_stop(rust);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_REQUEST_STOP_STATE,
               "both implementations reject stop before a session");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.destroySession(&error);
    rust_status = gvxrs_runtime_core_destroy_session(rust);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_DESTROY_SESSION_STATE,
               "both implementations reject destroying a nonexistent session");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.markLost("Runtime lost.", &error);
    rust_status = gvxrs_runtime_core_mark_lost(rust);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_MARK_LOST_STATE,
               "both implementations reject loss from Cold state");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.createSession(&error);
    rust_status = gvxrs_runtime_core_create_session(rust);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_CREATE_SESSION_STATE,
               "both implementations reject creating a session before initialization");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.initialize(endxr::RuntimeConfig{}, &error);
    rust_status = gvxrs_runtime_core_initialize(rust, 800, 800, 30);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations initialize defaults");

    cpp_ok = cpp.markLost("Runtime lost.", &error);
    rust_status = gvxrs_runtime_core_mark_lost(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations enter Lost state");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.initialize(endxr::RuntimeConfig{1024, 1024, 20}, &error);
    rust_status = gvxrs_runtime_core_initialize(rust, 1024, 1024, 20);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations recover from Lost");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.createSession(&error);
    rust_status = gvxrs_runtime_core_create_session(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both recovered implementations create session");

    cpp_ok = cpp.beginSession(&error);
    rust_status = gvxrs_runtime_core_begin_session(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both recovered implementations begin session");

    cpp_ok = cpp.initialize(endxr::RuntimeConfig{}, &error);
    rust_status = gvxrs_runtime_core_initialize(rust, 800, 800, 30);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_INITIALIZE_STATE,
               "both implementations reject reinitialization while Running");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.waitFrame(INT64_MAX - 10, &cpp_frame, &error);
    auto rust_frame = gvxrs_runtime_core_wait_frame(rust, INT64_MAX - 10);
    expectBoth(cpp_ok, rust_frame.status, false, GVXRS_CORE_ERROR_FRAME_DEADLINE_OVERFLOW,
               "both implementations reject display-time overflow");
    compareSnapshot(cpp, rust);

    cpp_ok = cpp.waitFrame(1'000'000'000LL, &cpp_frame, &error);
    rust_frame = gvxrs_runtime_core_wait_frame(rust, 1'000'000'000LL);
    expectBoth(cpp_ok, rust_frame.status, true, 0u, "both implementations recover after invalid timestamp");

    auto mismatched = cpp_frame;
    ++mismatched.frame_index;
    cpp_ok = cpp.endFrame(mismatched, true, &error);
    auto bad_rust_timing = toRustTiming(cpp_frame);
    ++bad_rust_timing.frame_index;
    rust_status = gvxrs_runtime_core_end_frame(rust, bad_rust_timing, 1u);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_FRAME_TOKEN_MISMATCH,
               "both implementations reject mismatched frame token");

    mismatched = cpp_frame;
    ++mismatched.frame_deadline_ns;
    cpp_ok = cpp.endFrame(mismatched, true, &error);
    bad_rust_timing = toRustTiming(cpp_frame);
    ++bad_rust_timing.frame_deadline_ns;
    rust_status = gvxrs_runtime_core_end_frame(rust, bad_rust_timing, 1u);
    expectBoth(cpp_ok, rust_status, false, GVXRS_CORE_ERROR_FRAME_TIMING_MISMATCH,
               "both implementations reject mismatched frame timing");

    cpp_ok = cpp.endFrame(cpp_frame, true, &error);
    rust_status = gvxrs_runtime_core_end_frame(rust, toRustTiming(cpp_frame), 1u);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations still end the valid frame");

    cpp_ok = cpp.requestStop(&error);
    rust_status = gvxrs_runtime_core_request_stop(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations stop the session");

    cpp_ok = cpp.destroySession(&error);
    rust_status = gvxrs_runtime_core_destroy_session(rust);
    expectBoth(cpp_ok, rust_status, true, 0u, "both implementations destroy the stopped session");
    compareSnapshot(cpp, rust);

    cpp.shutdown();
    rust_status = gvxrs_runtime_core_shutdown(rust);
    expect(rust_status.ok == 1u, "Rust lifecycle shutdown succeeds");
    compareSnapshot(cpp, rust);

    expect(gvxrs_runtime_core_destroy(rust).ok == 1u,
           "release second Rust lifecycle test handle");
    expect(gvxrs_runtime_core_snapshot(rust).status.error_code ==
               GVXRS_CORE_ERROR_INVALID_HANDLE,
           "reject use of a destroyed Rust handle");
}
} // namespace

int main() {
    testLifecycleAndFrameClockParity();
    testErrorAndRecoveryParity();
    std::cout << "GVXRS C++/Rust lifecycle parity: all cases passed.\n";
    return 0;
}
