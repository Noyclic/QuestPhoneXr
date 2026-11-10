#include "endgvr_vr_mode_coordinator.hpp"

namespace endgvr {

bool VrModeCoordinator::canEnterVrMode() const {
    return runtime_ready_ && activity_resumed_ && surface_available_ &&
        start_requested_;
}

VrModeTransition VrModeCoordinator::noCommand() const {
    return VrModeTransition{VrModeCommand::None, state_, false};
}

VrModeTransition VrModeCoordinator::reconcile() {
    if (state_ == VrModeState::Failed ||
        state_ == VrModeState::EnteringVrMode ||
        state_ == VrModeState::LeavingVrMode) {
        return noCommand();
    }

    if (vr_mode_active_) {
        if (!canEnterVrMode()) {
            state_ = VrModeState::LeavingVrMode;
            ++transition_count_;
            return VrModeTransition{VrModeCommand::LeaveVrMode, state_, true};
        }
        state_ = VrModeState::Running;
        return noCommand();
    }

    if (canEnterVrMode()) {
        state_ = VrModeState::EnteringVrMode;
        ++transition_count_;
        return VrModeTransition{VrModeCommand::EnterVrMode, state_, true};
    }

    state_ = start_requested_
        ? VrModeState::WaitingForPrerequisites
        : VrModeState::Idle;
    return noCommand();
}

VrModeTransition VrModeCoordinator::apply(
    LifecycleEvent event,
    const std::string& failure_reason
) {
    switch (event) {
        case LifecycleEvent::RuntimeReady:
            runtime_ready_ = true;
            break;
        case LifecycleEvent::RuntimeLost:
            vr_mode_may_be_active_ = vr_mode_may_be_active_ || vr_mode_active_ ||
                state_ == VrModeState::EnteringVrMode ||
                state_ == VrModeState::LeavingVrMode;
            vr_mode_active_ = false;
            runtime_ready_ = false;
            state_ = VrModeState::Failed;
            last_error_ = failure_reason.empty() ? "Legacy VR backend was lost." : failure_reason;
            return noCommand();
        case LifecycleEvent::ActivityResumed:
            activity_resumed_ = true;
            break;
        case LifecycleEvent::ActivityPaused:
            activity_resumed_ = false;
            break;
        case LifecycleEvent::SurfaceCreated:
            surface_available_ = true;
            break;
        case LifecycleEvent::SurfaceDestroyed:
            surface_available_ = false;
            break;
        case LifecycleEvent::StartRequested:
            start_requested_ = true;
            break;
        case LifecycleEvent::StopRequested:
            start_requested_ = false;
            break;
        case LifecycleEvent::EnterSucceeded:
            if (state_ != VrModeState::EnteringVrMode) return noCommand();
            vr_mode_active_ = true;
            vr_mode_may_be_active_ = true;
            last_error_.clear();
            state_ = VrModeState::Running;
            return reconcile();
        case LifecycleEvent::EnterFailed:
            if (state_ != VrModeState::EnteringVrMode) return noCommand();
            vr_mode_active_ = false;
            vr_mode_may_be_active_ = false;
            state_ = VrModeState::Failed;
            last_error_ = failure_reason.empty() ? "Could not enter legacy VR mode." : failure_reason;
            return noCommand();
        case LifecycleEvent::LeaveSucceeded:
            if (state_ != VrModeState::LeavingVrMode) return noCommand();
            vr_mode_active_ = false;
            vr_mode_may_be_active_ = false;
            last_error_.clear();
            state_ = start_requested_
                ? VrModeState::WaitingForPrerequisites
                : VrModeState::Idle;
            return reconcile();
        case LifecycleEvent::LeaveFailed:
            if (state_ != VrModeState::LeavingVrMode) return noCommand();
            vr_mode_active_ = false;
            vr_mode_may_be_active_ = true;
            state_ = VrModeState::Failed;
            last_error_ = failure_reason.empty() ? "Could not leave legacy VR mode safely." : failure_reason;
            return noCommand();
        case LifecycleEvent::ResetAfterRuntimeRestart:
            if (state_ != VrModeState::Failed) return noCommand();
            runtime_ready_ = false;
            vr_mode_active_ = false;
            vr_mode_may_be_active_ = false;
            state_ = start_requested_
                ? VrModeState::WaitingForPrerequisites
                : VrModeState::Idle;
            last_error_.clear();
            return noCommand();
    }

    return reconcile();
}

VrModeSnapshot VrModeCoordinator::snapshot() const {
    return VrModeSnapshot{
        state_,
        runtime_ready_,
        activity_resumed_,
        surface_available_,
        start_requested_,
        vr_mode_active_,
        vr_mode_may_be_active_,
        transition_count_,
        last_error_
    };
}

} // namespace endgvr
