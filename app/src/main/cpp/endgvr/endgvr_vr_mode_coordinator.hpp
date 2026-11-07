#pragma once

#include <cstdint>
#include <string>

namespace endgvr {

enum class VrModeState : std::uint8_t {
    Idle,
    WaitingForPrerequisites,
    EnteringVrMode,
    Running,
    LeavingVrMode,
    Failed
};

enum class LifecycleEvent : std::uint8_t {
    RuntimeReady,
    RuntimeLost,
    ActivityResumed,
    ActivityPaused,
    SurfaceCreated,
    SurfaceDestroyed,
    StartRequested,
    StopRequested,
    EnterSucceeded,
    EnterFailed,
    LeaveSucceeded,
    LeaveFailed,
    ResetAfterRuntimeRestart
};

enum class VrModeCommand : std::uint8_t {
    None,
    EnterVrMode,
    LeaveVrMode
};

struct VrModeTransition {
    VrModeCommand command = VrModeCommand::None;
    VrModeState state = VrModeState::Idle;
    bool command_issued = false;
};

struct VrModeSnapshot {
    VrModeState state = VrModeState::Idle;
    bool runtime_ready = false;
    bool activity_resumed = false;
    bool surface_available = false;
    bool start_requested = false;
    bool vr_mode_active = false;
    bool vr_mode_may_be_active = false;
    std::uint64_t transition_count = 0;
    std::string last_error;
};

/**
 * Serializes the preconditions for a legacy mobile-VR session.
 *
 * The owner must deliver events on one lifecycle thread. The returned command
 * must be executed by the platform adapter on the thread/context required by
 * the selected VR backend. This class does not call VrApi, EGL or Android.
 */
class VrModeCoordinator final {
public:
    VrModeTransition apply(
        LifecycleEvent event,
        const std::string& failure_reason = std::string()
    );

    VrModeSnapshot snapshot() const;

private:
    bool canEnterVrMode() const;
    VrModeTransition reconcile();
    VrModeTransition noCommand() const;

    VrModeState state_ = VrModeState::Idle;
    bool runtime_ready_ = false;
    bool activity_resumed_ = false;
    bool surface_available_ = false;
    bool start_requested_ = false;
    bool vr_mode_active_ = false;
    bool vr_mode_may_be_active_ = false;
    std::uint64_t transition_count_ = 0;
    std::string last_error_;
};

} // namespace endgvr
