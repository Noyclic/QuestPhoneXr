#include "endxr_runtime_core.hpp"
#include <algorithm>
#include <limits>
namespace endxr {
namespace {
constexpr std::uint32_t kMinDimension=64, kMaxDimension=8192, kMinFps=10, kMaxFps=120;
constexpr std::int64_t kNanosecondsPerSecond=1000000000LL;
}
bool RuntimeCore::failLocked(const char* message,std::string* error){
    last_error_=message; if(error!=nullptr)*error=last_error_; return false;
}
bool RuntimeCore::initialize(const RuntimeConfig& config,std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Cold&&state_!=RuntimeState::Lost)
        return failLocked("initialize requires Cold or Lost state.",error);
    if(config.width<kMinDimension||config.width>kMaxDimension||
       config.height<kMinDimension||config.height>kMaxDimension)
        return failLocked("Per-eye render dimensions must be within 64..8192 pixels.",error);
    if(config.target_fps<kMinFps||config.target_fps>kMaxFps)
        return failLocked("Target FPS must be within 10..120.",error);
    config_=config; next_frame_index_=1; frames_started_=0; submitted_frames_=0;
    dropped_frames_=0; late_frames_=0; active_frame_index_=0;
    last_predicted_time_ns_=0; active_frame_deadline_ns_=0; last_lateness_ns_=0;
    last_error_.clear(); state_=RuntimeState::Initialized; return true;
}
bool RuntimeCore::createSession(std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Initialized)return failLocked("createSession requires Initialized state.",error);
    state_=RuntimeState::SessionReady; last_error_.clear(); return true;
}
bool RuntimeCore::beginSession(std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::SessionReady)return failLocked("beginSession requires SessionReady state.",error);
    state_=RuntimeState::Running; last_error_.clear(); return true;
}
bool RuntimeCore::waitFrame(std::int64_t now,FrameTiming* timing,std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Running)return failLocked("waitFrame requires Running state.",error);
    if(timing==nullptr||now<=0)return failLocked("waitFrame requires an output and positive monotonic time.",error);
    if(active_frame_index_!=0)return failLocked("Previous frame has not been ended.",error);
    if(next_frame_index_==0||next_frame_index_==std::numeric_limits<std::uint64_t>::max())
        return failLocked("Frame index space is exhausted.",error);
    const auto period=kNanosecondsPerSecond/static_cast<std::int64_t>(config_.target_fps);
    if(last_predicted_time_ns_>std::numeric_limits<std::int64_t>::max()-period)
        return failLocked("Predicted display time overflow.",error);
    const auto earliest=last_predicted_time_ns_>0?last_predicted_time_ns_+period:now;
    if(earliest>std::numeric_limits<std::int64_t>::max()-period)
        return failLocked("Frame deadline overflow.",error);
    const auto deadline=earliest+period;
    timing->frame_index=next_frame_index_++;
    timing->predicted_display_time_ns=std::max(now,earliest);
    timing->predicted_display_period_ns=period;
    timing->frame_deadline_ns=deadline;
    last_lateness_ns_=now>deadline?now-deadline:0;
    if(last_lateness_ns_>0)++late_frames_;
    ++frames_started_;
    active_frame_index_=timing->frame_index;
    active_frame_deadline_ns_=deadline;
    last_predicted_time_ns_=timing->predicted_display_time_ns;
    last_error_.clear(); return true;
}
bool RuntimeCore::endFrame(const FrameTiming& timing,bool submitted,std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Running)return failLocked("endFrame requires Running state.",error);
    if(active_frame_index_==0||timing.frame_index!=active_frame_index_)
        return failLocked("endFrame does not match the active frame.",error);
    const auto period=kNanosecondsPerSecond/static_cast<std::int64_t>(config_.target_fps);
    if(timing.predicted_display_time_ns!=last_predicted_time_ns_||
       timing.predicted_display_period_ns!=period||
       timing.frame_deadline_ns!=active_frame_deadline_ns_)
        return failLocked("endFrame timing does not match the active frame.",error);
    if(submitted)++submitted_frames_; else ++dropped_frames_;
    active_frame_index_=0; active_frame_deadline_ns_=0; last_error_.clear(); return true;
}
bool RuntimeCore::requestStop(std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Running&&state_!=RuntimeState::SessionReady)
        return failLocked("requestStop requires an active session.",error);
    state_=RuntimeState::Stopping; active_frame_index_=0; active_frame_deadline_ns_=0;
    last_error_.clear(); return true;
}
bool RuntimeCore::destroySession(std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_!=RuntimeState::Stopping&&state_!=RuntimeState::SessionReady)
        return failLocked("destroySession requires Stopping or SessionReady state.",error);
    state_=RuntimeState::Initialized; active_frame_index_=0; active_frame_deadline_ns_=0;
    last_error_.clear(); return true;
}
bool RuntimeCore::markLost(const std::string& reason,std::string* error){
    std::lock_guard<std::mutex> lock(mutex_);
    if(state_==RuntimeState::Cold||state_==RuntimeState::Lost)
        return failLocked("markLost requires an initialized runtime that is not already Lost.",error);
    state_=RuntimeState::Lost; active_frame_index_=0; active_frame_deadline_ns_=0;
    last_error_=reason.empty()?"Runtime lost.":reason;
    if(error!=nullptr)*error=last_error_; return true;
}
void RuntimeCore::shutdown(){
    std::lock_guard<std::mutex> lock(mutex_);
    state_=RuntimeState::Cold; config_=RuntimeConfig{}; next_frame_index_=1;
    frames_started_=0; submitted_frames_=0; dropped_frames_=0; late_frames_=0;
    active_frame_index_=0; last_predicted_time_ns_=0; active_frame_deadline_ns_=0;
    last_lateness_ns_=0; last_error_.clear();
}
RuntimeSnapshot RuntimeCore::snapshot() const{
    std::lock_guard<std::mutex> lock(mutex_);
    RuntimeSnapshot result; result.state=state_; result.config=config_;
    result.frames_started=frames_started_; result.submitted_frames=submitted_frames_;
    result.dropped_frames=dropped_frames_; result.late_frames=late_frames_;
    result.last_lateness_ns=last_lateness_ns_; result.last_error=last_error_; return result;
}
} // namespace endxr
