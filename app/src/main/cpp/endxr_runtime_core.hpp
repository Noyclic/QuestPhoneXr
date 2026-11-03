#pragma once
#include <cstdint>
#include <mutex>
#include <string>
namespace endxr {
enum class RuntimeState : std::uint8_t { Cold, Initialized, SessionReady, Running, Stopping, Lost };
struct RuntimeConfig { std::uint32_t width=1600, height=800, target_fps=30; };
struct FrameTiming { std::uint64_t frame_index=0; std::int64_t predicted_display_time_ns=0, predicted_display_period_ns=0; };
struct RuntimeSnapshot { RuntimeState state=RuntimeState::Cold; RuntimeConfig config{}; std::uint64_t submitted_frames=0; std::string last_error; };
/**
 * EndXR's app-owned runtime core.
 * This is an internal lifecycle/frame-clock foundation, not yet an installable
 * OpenXR runtime or a Quest APK compatibility implementation.
 * It has no Monado, Binder, loader, or graphics-driver dependency.
 */
class RuntimeCore final {
public:
 bool initialize(const RuntimeConfig&, std::string* error=nullptr);
 bool createSession(std::string* error=nullptr);
 bool beginSession(std::string* error=nullptr);
 bool waitFrame(std::int64_t monotonic_now_ns, FrameTiming*, std::string* error=nullptr);
 bool endFrame(const FrameTiming&, bool submitted, std::string* error=nullptr);
 bool requestStop(std::string* error=nullptr);
 bool destroySession(std::string* error=nullptr);
 void shutdown();
 RuntimeSnapshot snapshot() const;
private:
 bool failLocked(const char*, std::string*);
 mutable std::mutex mutex_;
 RuntimeState state_=RuntimeState::Cold;
 RuntimeConfig config_{};
 std::uint64_t next_frame_index_=1, submitted_frames_=0;
 std::int64_t last_predicted_time_ns_=0, active_frame_index_=0;
 std::string last_error_;
};
} // namespace endxr
