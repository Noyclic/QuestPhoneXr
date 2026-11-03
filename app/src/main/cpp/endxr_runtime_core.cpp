#include "endxr_runtime_core.hpp"
#include <algorithm>
namespace endxr {
namespace {
constexpr std::uint32_t kMinDimension=64, kMaxDimension=8192, kMinFps=10, kMaxFps=120;
}
bool RuntimeCore::failLocked(const char* message, std::string* error) {
 last_error_=message; if(error) *error=last_error_; return false;
}
bool RuntimeCore::initialize(const RuntimeConfig& c, std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_);
 if(state_!=RuntimeState::Cold && state_!=RuntimeState::Lost) return failLocked("initialize requires Cold or Lost state.",error);
 if(c.width<kMinDimension || c.width>kMaxDimension || c.height<kMinDimension || c.height>kMaxDimension) return failLocked("Render dimensions must be within 64..8192 pixels.",error);
 if(c.target_fps<kMinFps || c.target_fps>kMaxFps) return failLocked("Target FPS must be within 10..120.",error);
 config_=c; next_frame_index_=1; submitted_frames_=0; last_predicted_time_ns_=0; active_frame_index_=0; last_error_.clear(); state_=RuntimeState::Initialized; return true;
}
bool RuntimeCore::createSession(std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_); if(state_!=RuntimeState::Initialized) return failLocked("createSession requires Initialized state.",error);
 state_=RuntimeState::SessionReady; last_error_.clear(); return true;
}
bool RuntimeCore::beginSession(std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_); if(state_!=RuntimeState::SessionReady) return failLocked("beginSession requires SessionReady state.",error);
 state_=RuntimeState::Running; last_error_.clear(); return true;
}
bool RuntimeCore::waitFrame(std::int64_t now, FrameTiming* timing, std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_);
 if(state_!=RuntimeState::Running) return failLocked("waitFrame requires Running state.",error);
 if(!timing || now<=0) return failLocked("waitFrame requires an output and positive monotonic time.",error);
 if(active_frame_index_!=0) return failLocked("Previous frame has not been ended.",error);
 const auto period=static_cast<std::int64_t>(1000000000ULL/config_.target_fps);
 const auto earliest=last_predicted_time_ns_>0 ? last_predicted_time_ns_+period : now;
 timing->frame_index=next_frame_index_++;
 timing->predicted_display_time_ns=std::max(now,earliest);
 timing->predicted_display_period_ns=period;
 active_frame_index_=static_cast<std::int64_t>(timing->frame_index);
 last_predicted_time_ns_=timing->predicted_display_time_ns; last_error_.clear(); return true;
}
bool RuntimeCore::endFrame(const FrameTiming& timing, bool submitted, std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_);
 if(state_!=RuntimeState::Running) return failLocked("endFrame requires Running state.",error);
 if(active_frame_index_==0 || timing.frame_index!=static_cast<std::uint64_t>(active_frame_index_)) return failLocked("endFrame does not match the active frame.",error);
 if(submitted) ++submitted_frames_; active_frame_index_=0; last_error_.clear(); return true;
}
bool RuntimeCore::requestStop(std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_);
 if(state_!=RuntimeState::Running && state_!=RuntimeState::SessionReady) return failLocked("requestStop requires an active session.",error);
 state_=RuntimeState::Stopping; active_frame_index_=0; last_error_.clear(); return true;
}
bool RuntimeCore::destroySession(std::string* error) {
 std::lock_guard<std::mutex> lock(mutex_);
 if(state_!=RuntimeState::Stopping && state_!=RuntimeState::SessionReady) return failLocked("destroySession requires Stopping or SessionReady state.",error);
 state_=RuntimeState::Initialized; active_frame_index_=0; last_error_.clear(); return true;
}
void RuntimeCore::shutdown() {
 std::lock_guard<std::mutex> lock(mutex_); state_=RuntimeState::Cold; next_frame_index_=1; submitted_frames_=0; last_predicted_time_ns_=0; active_frame_index_=0; last_error_.clear();
}
RuntimeSnapshot RuntimeCore::snapshot() const {
 std::lock_guard<std::mutex> lock(mutex_); RuntimeSnapshot r; r.state=state_; r.config=config_; r.submitted_frames=submitted_frames_; r.last_error=last_error_; return r;
}
} // namespace endxr
