#pragma once
#include <cstdint>
#include <mutex>
#include <string>
namespace endxr {
enum class RuntimeState : std::uint8_t { Cold, Initialized, SessionReady, Running, Stopping, Lost };
/** Render dimensions are per-eye dimensions, not combined side-by-side output dimensions. */
struct RuntimeConfig {
    std::uint32_t width = 800;
    std::uint32_t height = 800;
    std::uint32_t target_fps = 30;
};
struct FrameTiming {
    std::uint64_t frame_index = 0;
    std::int64_t predicted_display_time_ns = 0;
    std::int64_t predicted_display_period_ns = 0;
    std::int64_t frame_deadline_ns = 0;
};
struct RuntimeSnapshot {
    RuntimeState state = RuntimeState::Cold;
    RuntimeConfig config{};
    std::uint64_t frames_started = 0;
    std::uint64_t submitted_frames = 0;
    std::uint64_t dropped_frames = 0;
    std::uint64_t late_frames = 0;
    std::int64_t last_lateness_ns = 0;
    std::string last_error;
};
/**
 * EndXR's app-owned runtime core.
 * This is an internal lifecycle/frame-clock foundation, not yet an installable
 * OpenXR runtime or a Quest APK compatibility implementation. It has no
 * Monado, Binder, Khronos Loader, or graphics-driver dependency.
 */
class RuntimeCore final {
public:
    bool initialize(const RuntimeConfig& config, std::string* error = nullptr);
    bool createSession(std::string* error = nullptr);
    bool beginSession(std::string* error = nullptr);
    bool waitFrame(std::int64_t monotonic_now_ns, FrameTiming* timing, std::string* error = nullptr);
    bool endFrame(const FrameTiming& timing, bool submitted, std::string* error = nullptr);
    bool requestStop(std::string* error = nullptr);
    bool destroySession(std::string* error = nullptr);
    bool markLost(const std::string& reason, std::string* error = nullptr);
    void shutdown();
    RuntimeSnapshot snapshot() const;
private:
    bool failLocked(const char* message, std::string* error);
    mutable std::mutex mutex_;
    RuntimeState state_ = RuntimeState::Cold;
    RuntimeConfig config_{};
    std::uint64_t next_frame_index_ = 1;
    std::uint64_t frames_started_ = 0;
    std::uint64_t submitted_frames_ = 0;
    std::uint64_t dropped_frames_ = 0;
    std::uint64_t late_frames_ = 0;
    std::uint64_t active_frame_index_ = 0;
    std::int64_t last_predicted_time_ns_ = 0;
    std::int64_t active_frame_deadline_ns_ = 0;
    std::int64_t last_lateness_ns_ = 0;
    std::string last_error_;
};
} // namespace endxr
