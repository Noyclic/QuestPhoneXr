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
    expect(snapshot.submitted_frames == 2,
           "submitted frame count tracks successful submissions");

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
    expect(recovered.submitted_frames == 0,
           "recovery resets the submitted frame counter");
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
    expect(snapshot.config.width == 1600 && snapshot.config.height == 800,
           "shutdown restores default render dimensions");
    expect(snapshot.config.target_fps == 30,
           "shutdown restores default target frame rate");
}

} // namespace

int main() {
    testConfigurationValidation();
    testSessionLifecycleAndFrameClock();
    testFrameValidation();
    testLostStateCanBeRecovered();
    testShutdownResetsTheRuntime();

    std::cout << "EndXR RuntimeCore: all native tests passed.\n";
    return 0;
}
