#include "endxr_runtime_core.hpp"

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

void testConfigurationValidation() {
    endxr::RuntimeCore runtime;
    std::string error;

    expect(
        !runtime.initialize(endxr::RuntimeConfig{32, 720, 30}, &error),
        "reject dimensions below the supported range"
    );
    expect(runtime.snapshot().state == endxr::RuntimeState::Cold,
           "invalid configuration must not leave Cold state");
    expect(!error.empty(), "invalid configuration should explain the failure");

    expect(
        !runtime.initialize(endxr::RuntimeConfig{1280, 720, 121}, &error),
        "reject frame rates above the supported range"
    );
    expect(
        runtime.initialize(endxr::RuntimeConfig{1280, 720, 30}, &error),
        "accept a valid runtime configuration"
    );
    expect(runtime.snapshot().state == endxr::RuntimeState::Initialized,
           "valid initialization enters Initialized state");
}

void testSessionLifecycleAndFrameClock() {
    endxr::RuntimeCore runtime;
    std::string error;
    endxr::FrameTiming first;
    endxr::FrameTiming second;

    expect(runtime.initialize(endxr::RuntimeConfig{1280, 720, 30}, &error),
           "initialize runtime");
    expect(runtime.createSession(&error), "create session");
    expect(runtime.beginSession(&error), "begin session");

    expect(runtime.waitFrame(1000000000LL, &first, &error),
           "wait for first frame");
    expect(first.frame_index == 1, "first frame index starts at one");
    expect(first.predicted_display_time_ns == 1000000000LL,
           "first predicted display time uses current monotonic time");
    expect(first.predicted_display_period_ns == 33333333LL,
           "30 FPS frame period is computed in nanoseconds");
    expect(first.frame_deadline_ns == 1033333333LL,
           "first frame deadline follows predicted display time");

    expect(!runtime.waitFrame(1000000001LL, &second, &error),
           "do not allow two active frames");
    expect(runtime.endFrame(first, true, &error),
           "end the first submitted frame");

    expect(runtime.waitFrame(1000000001LL, &second, &error),
           "wait for second frame");
    expect(second.frame_index == 2, "frame indices increase monotonically");
    expect(second.predicted_display_time_ns ==
               first.predicted_display_time_ns + first.predicted_display_period_ns,
           "predicted time advances by at least one frame period");
    expect(runtime.endFrame(second, true, &error),
           "end the second submitted frame");

    const auto snapshot = runtime.snapshot();
    expect(snapshot.state == endxr::RuntimeState::Running,
           "runtime remains Running after frame submission");
    expect(snapshot.frames_started == 2,
           "started frame count tracks accepted waitFrame calls");
    expect(snapshot.submitted_frames == 2,
           "submitted frame count tracks successful submissions");
    expect(snapshot.dropped_frames == 0,
           "submitted frames are not counted as dropped");
    expect(snapshot.late_frames == 0,
           "on-time frame starts do not increment late-frame telemetry");

    expect(runtime.requestStop(&error), "request session stop");
    expect(runtime.snapshot().state == endxr::RuntimeState::Stopping,
           "requestStop enters Stopping state");
    expect(runtime.destroySession(&error), "destroy stopped session");
    expect(runtime.snapshot().state == endxr::RuntimeState::Initialized,
           "destroySession returns to Initialized state");
}

void testFrameValidation() {
    endxr::RuntimeCore runtime;
    std::string error;
    endxr::FrameTiming frame;

    expect(runtime.initialize(endxr::RuntimeConfig{}, &error), "initialize defaults");
    expect(runtime.createSession(&error), "create default session");
    expect(runtime.beginSession(&error), "begin default session");

    expect(!runtime.waitFrame(0, &frame, &error),
           "reject non-positive monotonic timestamps");
    expect(!runtime.waitFrame(100, nullptr, &error),
           "reject null frame output");
    expect(runtime.waitFrame(1000000, &frame, &error),
           "start a valid frame");

    auto mismatched = frame;
    ++mismatched.frame_index;
    expect(!runtime.endFrame(mismatched, true, &error),
           "reject a frame index that does not match the active frame");
    expect(runtime.endFrame(frame, false, &error),
           "allow ending the correct frame without submission");
    expect(runtime.snapshot().submitted_frames == 0,
           "non-submitted frame does not increase the submitted count");
    expect(runtime.snapshot().dropped_frames == 1,
           "non-submitted frame increments the dropped frame counter");
}

void testLateFrameAndDeadlineTelemetry() {
    endxr::RuntimeCore runtime;
    std::string error;
    endxr::FrameTiming first;
    endxr::FrameTiming late;
    expect(runtime.initialize(endxr::RuntimeConfig{640, 720, 30}, &error),
           "initialize per-eye render dimensions");
    expect(runtime.createSession(&error), "create telemetry session");
    expect(runtime.beginSession(&error), "begin telemetry session");
    expect(runtime.waitFrame(1000000000LL, &first, &error), "start reference frame");
    expect(runtime.endFrame(first, false, &error), "discard reference frame");
    expect(runtime.waitFrame(1100000000LL, &late, &error), "start frame after deadline");
    expect(late.frame_deadline_ns == 1066666666LL,
           "late frame retains its original deadline");
    auto wrongDeadline = late;
    ++wrongDeadline.frame_deadline_ns;
    expect(!runtime.endFrame(wrongDeadline, true, &error),
           "reject frame completion with modified deadline");
    expect(runtime.endFrame(late, true, &error), "accept original frame timing");
    const auto snapshot = runtime.snapshot();
    expect(snapshot.config.width == 640 && snapshot.config.height == 720,
           "runtime stores per-eye dimensions");
    expect(snapshot.frames_started == 2, "both started frames are counted");
    expect(snapshot.submitted_frames == 1, "one submitted frame is counted");
    expect(snapshot.dropped_frames == 1, "one discarded frame is counted");
    expect(snapshot.late_frames == 1, "late frame start is counted");
    expect(snapshot.last_lateness_ns == 33333334LL,
           "lateness is measured against the frame deadline");
}

void testLostStateCanBeRecovered() {
    endxr::RuntimeCore runtime;
    std::string error;

    expect(runtime.initialize(endxr::RuntimeConfig{}, &error), "initialize before loss");
    expect(runtime.createSession(&error), "create session before loss");
    expect(runtime.markLost("GLES context lost", &error), "mark runtime as lost");
    const auto lost = runtime.snapshot();
    expect(lost.state == endxr::RuntimeState::Lost, "loss enters Lost state");
    expect(lost.last_error == "GLES context lost", "loss reason is retained");

    expect(runtime.initialize(endxr::RuntimeConfig{1024, 1024, 20}, &error),
           "reinitialize after loss");
    const auto recovered = runtime.snapshot();
    expect(recovered.state == endxr::RuntimeState::Initialized,
           "recovery returns to Initialized state");
    expect(recovered.frames_started == 0 && recovered.submitted_frames == 0 &&
               recovered.dropped_frames == 0 && recovered.late_frames == 0,
           "recovery resets all frame telemetry counters");
    expect(recovered.last_error.empty(),
           "successful recovery clears the previous error");
}

void testShutdownResetsTheRuntime() {
    endxr::RuntimeCore runtime;
    std::string error;

    expect(runtime.initialize(endxr::RuntimeConfig{1920, 1080, 60}, &error),
           "initialize before shutdown");
    runtime.shutdown();

    const auto snapshot = runtime.snapshot();
    expect(snapshot.state == endxr::RuntimeState::Cold,
           "shutdown returns to Cold state");
    expect(snapshot.config.width == 800 && snapshot.config.height == 800,
           "shutdown restores default per-eye render dimensions");
    expect(snapshot.config.target_fps == 30,
           "shutdown restores default target frame rate");
}

} // namespace

int main() {
    testConfigurationValidation();
    testSessionLifecycleAndFrameClock();
    testFrameValidation();
    testLateFrameAndDeadlineTelemetry();
    testLostStateCanBeRecovered();
    testShutdownResetsTheRuntime();

    std::cout << "EndXR RuntimeCore: all native tests passed.\n";
    return 0;
}
