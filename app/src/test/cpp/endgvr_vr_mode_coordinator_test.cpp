#include "endgvr_vr_mode_coordinator.hpp"

#include <cstdlib>
#include <iostream>

namespace {

void expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}

void prepareReadyBackend(endgvr::VrModeCoordinator& coordinator) {
    coordinator.apply(endgvr::LifecycleEvent::RuntimeReady);
    coordinator.apply(endgvr::LifecycleEvent::StartRequested);
}

void testWaitsForRuntimeActivityAndSurface() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);

    auto transition = coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    expect(transition.command == endgvr::VrModeCommand::None,
           "resume alone must not enter VR without a valid surface");
    expect(transition.state == endgvr::VrModeState::WaitingForPrerequisites,
           "session waits for the missing surface");

    transition = coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    expect(transition.command == endgvr::VrModeCommand::EnterVrMode,
           "enter VR after runtime, activity and surface are ready");
    expect(transition.state == endgvr::VrModeState::EnteringVrMode,
           "enter command records an in-flight transition");
    expect(coordinator.snapshot().transition_count == 1,
           "only one enter command is issued");

    transition = coordinator.apply(endgvr::LifecycleEvent::EnterSucceeded);
    expect(transition.state == endgvr::VrModeState::Running,
           "successful backend entry marks the session running");
    expect(coordinator.snapshot().vr_mode_active,
           "running session is reported active");
}

void testSurfaceCanArriveBeforeActivityResume() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);

    auto transition = coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    expect(transition.command == endgvr::VrModeCommand::EnterVrMode,
           "enter VR when the activity resumes after surface creation");
}

void testPauseAndSurfaceLossLeaveVrModeExactlyOnce() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    coordinator.apply(endgvr::LifecycleEvent::EnterSucceeded);

    auto transition = coordinator.apply(endgvr::LifecycleEvent::ActivityPaused);
    expect(transition.command == endgvr::VrModeCommand::LeaveVrMode,
           "pause while running requests leaving VR mode");
    transition = coordinator.apply(endgvr::LifecycleEvent::SurfaceDestroyed);
    expect(transition.command == endgvr::VrModeCommand::None,
           "surface loss during leave must not issue a duplicate leave");
    transition = coordinator.apply(endgvr::LifecycleEvent::LeaveSucceeded);
    expect(transition.command == endgvr::VrModeCommand::None,
           "completed leave waits while activity remains paused");
    expect(coordinator.snapshot().state == endgvr::VrModeState::WaitingForPrerequisites,
           "requested session waits for lifecycle prerequisites again");

    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    transition = coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    expect(transition.command == endgvr::VrModeCommand::EnterVrMode,
           "session can re-enter after activity and surface become valid again");
}

void testStopDuringEnterLeavesImmediatelyAfterEnterCompletes() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    auto transition = coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    expect(transition.command == endgvr::VrModeCommand::EnterVrMode,
           "begin enter transition");

    transition = coordinator.apply(endgvr::LifecycleEvent::StopRequested);
    expect(transition.command == endgvr::VrModeCommand::None,
           "cannot cancel a backend enter operation that is already in flight");
    transition = coordinator.apply(endgvr::LifecycleEvent::EnterSucceeded);
    expect(transition.command == endgvr::VrModeCommand::LeaveVrMode,
           "leave immediately when the late enter completes after stop");
    transition = coordinator.apply(endgvr::LifecycleEvent::LeaveSucceeded);
    expect(transition.state == endgvr::VrModeState::Idle,
           "stopped session returns to Idle");
}

void testEnterFailureRequiresExplicitBackendRestart() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    auto transition = coordinator.apply(
        endgvr::LifecycleEvent::EnterFailed,
        "EGL surface rejected"
    );
    expect(transition.state == endgvr::VrModeState::Failed,
           "enter failure moves the coordinator to Failed");
    expect(coordinator.snapshot().last_error == "EGL surface rejected",
           "failure reason is preserved for diagnostics");
    expect(coordinator.apply(endgvr::LifecycleEvent::ActivityPaused).command ==
               endgvr::VrModeCommand::None,
           "failed coordinator does not retry automatically");
    coordinator.apply(endgvr::LifecycleEvent::ResetAfterRuntimeRestart);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    transition = coordinator.apply(endgvr::LifecycleEvent::RuntimeReady);
    expect(transition.command == endgvr::VrModeCommand::EnterVrMode,
           "explicit backend restart permits a fresh enter attempt");
}

void testLeaveFailureFailsClosedWhenVrModeMayStillBeActive() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    coordinator.apply(endgvr::LifecycleEvent::EnterSucceeded);
    auto transition = coordinator.apply(endgvr::LifecycleEvent::ActivityPaused);
    expect(transition.command == endgvr::VrModeCommand::LeaveVrMode,
           "pause requests leave before testing leave failure");

    transition = coordinator.apply(endgvr::LifecycleEvent::LeaveFailed, "backend did not stop");
    const auto snapshot = coordinator.snapshot();
    expect(transition.state == endgvr::VrModeState::Failed,
           "leave failure moves coordinator to Failed");
    expect(snapshot.vr_mode_may_be_active,
           "leave failure warns that backend VR mode may still be active");
    expect(transition.command == endgvr::VrModeCommand::None,
           "failed leave does not trigger an unsafe automatic retry");
}

void testRuntimeLossDuringEnterFailsClosed() {
    endgvr::VrModeCoordinator coordinator;
    prepareReadyBackend(coordinator);
    coordinator.apply(endgvr::LifecycleEvent::ActivityResumed);
    coordinator.apply(endgvr::LifecycleEvent::SurfaceCreated);
    auto transition = coordinator.apply(endgvr::LifecycleEvent::RuntimeLost,
                                         "VrApi service disconnected");
    const auto snapshot = coordinator.snapshot();
    expect(transition.state == endgvr::VrModeState::Failed,
           "runtime loss prevents further session transitions");
    expect(snapshot.vr_mode_may_be_active,
           "runtime loss during enter is treated as potentially active");
    expect(!snapshot.runtime_ready,
           "lost backend is no longer marked ready");
}

} // namespace

int main() {
    testWaitsForRuntimeActivityAndSurface();
    testSurfaceCanArriveBeforeActivityResume();
    testPauseAndSurfaceLossLeaveVrModeExactlyOnce();
    testStopDuringEnterLeavesImmediatelyAfterEnterCompletes();
    testEnterFailureRequiresExplicitBackendRestart();
    testLeaveFailureFailsClosedWhenVrModeMayStillBeActive();
    testRuntimeLossDuringEnterFailsClosed();
    std::cout << "EndGVR VrModeCoordinator: all native tests passed.\n";
    return 0;
}
