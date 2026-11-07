//! Platform-independent GVXRS runtime lifecycle and frame-clock logic.
//!
//! This crate intentionally does not implement OpenXR entry points, Android
//! services, EGL/GLES, JNI, RuntimeBroker discovery, or loader negotiation.
//! It is a safe Rust parity implementation of the app-side lifecycle helper
//! currently called by Android JNI. It is not the loader-facing OpenXR engine.

#![forbid(unsafe_code)]

pub mod tracking;
mod core_ffi;

use std::sync::{Mutex, MutexGuard};

const MIN_DIMENSION: u32 = 64;
const MAX_DIMENSION: u32 = 8192;
const MIN_FPS: u32 = 10;
const MAX_FPS: u32 = 120;
const NANOS_PER_SECOND: i64 = 1_000_000_000;

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
#[repr(u8)]
pub enum RuntimeState {
    #[default] Cold = 0,
    Initialized = 1,
    SessionReady = 2,
    Running = 3,
    Stopping = 4,
    Lost = 5,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct RuntimeConfig {
    /// Per-eye render width, not side-by-side output width.
    pub width: u32,
    pub height: u32,
    pub target_fps: u32,
}

impl Default for RuntimeConfig {
    fn default() -> Self { Self { width: 800, height: 800, target_fps: 30 } }
}

#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub struct FrameTiming {
    pub frame_index: u64,
    pub predicted_display_time_ns: i64,
    pub predicted_display_period_ns: i64,
    pub frame_deadline_ns: i64,
}

#[derive(Clone, Debug, Eq, PartialEq)]
pub struct RuntimeSnapshot {
    pub state: RuntimeState,
    pub config: RuntimeConfig,
    pub frames_started: u64,
    pub submitted_frames: u64,
    pub dropped_frames: u64,
    pub late_frames: u64,
    pub last_lateness_ns: i64,
    pub last_error: String,
}

struct Inner {
    state: RuntimeState,
    config: RuntimeConfig,
    next_frame_index: u64,
    frames_started: u64,
    submitted_frames: u64,
    dropped_frames: u64,
    late_frames: u64,
    active_frame_index: u64,
    last_predicted_time_ns: i64,
    active_frame_deadline_ns: i64,
    last_lateness_ns: i64,
    last_error: String,
}

impl Default for Inner {
    fn default() -> Self {
        Self {
            state: RuntimeState::Cold, config: RuntimeConfig::default(),
            next_frame_index: 1, frames_started: 0, submitted_frames: 0,
            dropped_frames: 0, late_frames: 0, active_frame_index: 0,
            last_predicted_time_ns: 0, active_frame_deadline_ns: 0,
            last_lateness_ns: 0, last_error: String::new(),
        }
    }
}

/// Thread-safe, platform-independent runtime lifecycle state.
pub struct RuntimeCore { inner: Mutex<Inner> }

impl Default for RuntimeCore {
    fn default() -> Self { Self { inner: Mutex::new(Inner::default()) } }
}

impl RuntimeCore {
    pub fn new() -> Self { Self::default() }

    fn lock(&self) -> MutexGuard<'_, Inner> {
        self.inner.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
    }

    fn fail(inner: &mut Inner, message: &'static str) -> String {
        inner.last_error = message.to_owned();
        inner.last_error.clone()
    }

    pub fn initialize(&self, config: RuntimeConfig) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Cold && inner.state != RuntimeState::Lost {
            return Err(Self::fail(&mut inner, "initialize requires Cold or Lost state."));
        }
        if !(MIN_DIMENSION..=MAX_DIMENSION).contains(&config.width)
            || !(MIN_DIMENSION..=MAX_DIMENSION).contains(&config.height) {
            return Err(Self::fail(&mut inner, "Per-eye render dimensions must be within 64..8192 pixels."));
        }
        if !(MIN_FPS..=MAX_FPS).contains(&config.target_fps) {
            return Err(Self::fail(&mut inner, "Target FPS must be within 10..120."));
        }
        inner.config = config;
        inner.next_frame_index = 1;
        inner.frames_started = 0;
        inner.submitted_frames = 0;
        inner.dropped_frames = 0;
        inner.late_frames = 0;
        inner.active_frame_index = 0;
        inner.last_predicted_time_ns = 0;
        inner.active_frame_deadline_ns = 0;
        inner.last_lateness_ns = 0;
        inner.last_error.clear();
        inner.state = RuntimeState::Initialized;
        Ok(())
    }

    pub fn create_session(&self) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Initialized {
            return Err(Self::fail(&mut inner, "createSession requires Initialized state."));
        }
        inner.state = RuntimeState::SessionReady;
        inner.last_error.clear();
        Ok(())
    }

    pub fn begin_session(&self) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::SessionReady {
            return Err(Self::fail(&mut inner, "beginSession requires SessionReady state."));
        }
        inner.state = RuntimeState::Running;
        inner.last_error.clear();
        Ok(())
    }

    /// The supplied timestamp must be positive and monotonic.
    pub fn wait_frame(&self, monotonic_now_ns: i64) -> Result<FrameTiming, String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Running {
            return Err(Self::fail(&mut inner, "waitFrame requires Running state."));
        }
        if monotonic_now_ns <= 0 {
            return Err(Self::fail(&mut inner, "waitFrame requires an output and positive monotonic time."));
        }
        if inner.active_frame_index != 0 {
            return Err(Self::fail(&mut inner, "Previous frame has not been ended."));
        }
        if inner.next_frame_index == 0 || inner.next_frame_index == u64::MAX {
            return Err(Self::fail(&mut inner, "Frame index space is exhausted."));
        }
        let period = NANOS_PER_SECOND / i64::from(inner.config.target_fps);
        let earliest = if inner.last_predicted_time_ns > 0 {
            inner.last_predicted_time_ns.checked_add(period)
                .ok_or_else(|| Self::fail(&mut inner, "Predicted display time overflow."))?
        } else { monotonic_now_ns };
        let deadline = earliest.checked_add(period)
            .ok_or_else(|| Self::fail(&mut inner, "Frame deadline overflow."))?;
        let timing = FrameTiming {
            frame_index: inner.next_frame_index,
            predicted_display_time_ns: monotonic_now_ns.max(earliest),
            predicted_display_period_ns: period,
            frame_deadline_ns: deadline,
        };
        inner.next_frame_index += 1;
        inner.last_lateness_ns = if monotonic_now_ns > deadline { monotonic_now_ns - deadline } else { 0 };
        if inner.last_lateness_ns > 0 { inner.late_frames += 1; }
        inner.frames_started += 1;
        inner.active_frame_index = timing.frame_index;
        inner.active_frame_deadline_ns = deadline;
        inner.last_predicted_time_ns = timing.predicted_display_time_ns;
        inner.last_error.clear();
        Ok(timing)
    }

    pub fn end_frame(&self, timing: FrameTiming, submitted: bool) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Running {
            return Err(Self::fail(&mut inner, "endFrame requires Running state."));
        }
        if inner.active_frame_index == 0 || timing.frame_index != inner.active_frame_index {
            return Err(Self::fail(&mut inner, "endFrame does not match the active frame."));
        }
        let period = NANOS_PER_SECOND / i64::from(inner.config.target_fps);
        if timing.predicted_display_time_ns != inner.last_predicted_time_ns
            || timing.predicted_display_period_ns != period
            || timing.frame_deadline_ns != inner.active_frame_deadline_ns {
            return Err(Self::fail(&mut inner, "endFrame timing does not match the active frame."));
        }
        if submitted { inner.submitted_frames += 1; } else { inner.dropped_frames += 1; }
        inner.active_frame_index = 0;
        inner.active_frame_deadline_ns = 0;
        inner.last_error.clear();
        Ok(())
    }

    pub fn request_stop(&self) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Running && inner.state != RuntimeState::SessionReady {
            return Err(Self::fail(&mut inner, "requestStop requires an active session."));
        }
        inner.state = RuntimeState::Stopping;
        inner.active_frame_index = 0;
        inner.active_frame_deadline_ns = 0;
        inner.last_error.clear();
        Ok(())
    }

    pub fn destroy_session(&self) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state != RuntimeState::Stopping && inner.state != RuntimeState::SessionReady {
            return Err(Self::fail(&mut inner, "destroySession requires Stopping or SessionReady state."));
        }
        inner.state = RuntimeState::Initialized;
        inner.active_frame_index = 0;
        inner.active_frame_deadline_ns = 0;
        inner.last_error.clear();
        Ok(())
    }

    pub fn mark_lost(&self, reason: &str) -> Result<(), String> {
        let mut inner = self.lock();
        if inner.state == RuntimeState::Cold || inner.state == RuntimeState::Lost {
            return Err(Self::fail(&mut inner, "markLost requires an initialized runtime that is not already Lost."));
        }
        inner.state = RuntimeState::Lost;
        inner.active_frame_index = 0;
        inner.active_frame_deadline_ns = 0;
        inner.last_error = if reason.is_empty() { "Runtime lost.".to_owned() } else { reason.to_owned() };
        Ok(())
    }

    pub fn shutdown(&self) { *self.lock() = Inner::default(); }

    pub fn snapshot(&self) -> RuntimeSnapshot {
        let inner = self.lock();
        RuntimeSnapshot {
            state: inner.state, config: inner.config,
            frames_started: inner.frames_started, submitted_frames: inner.submitted_frames,
            dropped_frames: inner.dropped_frames, late_frames: inner.late_frames,
            last_lateness_ns: inner.last_lateness_ns, last_error: inner.last_error.clone(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn running(config: RuntimeConfig) -> RuntimeCore {
        let core = RuntimeCore::new();
        core.initialize(config).unwrap();
        core.create_session().unwrap();
        core.begin_session().unwrap();
        core
    }

    #[test]
    fn configuration_validation_and_state_transitions() {
        let core = RuntimeCore::new();
        assert!(core.initialize(RuntimeConfig { width: 32, height: 720, target_fps: 30 }).is_err());
        assert_eq!(core.snapshot().state, RuntimeState::Cold);
        assert!(core.initialize(RuntimeConfig { width: 1280, height: 720, target_fps: 121 }).is_err());
        core.initialize(RuntimeConfig { width: 1280, height: 720, target_fps: 30 }).unwrap();
        assert_eq!(core.snapshot().state, RuntimeState::Initialized);
        core.create_session().unwrap();
        core.begin_session().unwrap();
        assert_eq!(core.snapshot().state, RuntimeState::Running);
    }

    #[test]
    fn frame_clock_and_submission_counters_match_legacy_contract() {
        let core = running(RuntimeConfig { width: 1280, height: 720, target_fps: 30 });
        let first = core.wait_frame(1_000_000_000).unwrap();
        assert_eq!(first, FrameTiming { frame_index: 1, predicted_display_time_ns: 1_000_000_000, predicted_display_period_ns: 33_333_333, frame_deadline_ns: 1_033_333_333 });
        assert!(core.wait_frame(1_000_000_001).is_err());
        core.end_frame(first, true).unwrap();
        let second = core.wait_frame(1_000_000_001).unwrap();
        assert_eq!(second.frame_index, 2);
        assert_eq!(second.predicted_display_time_ns, first.predicted_display_time_ns + first.predicted_display_period_ns);
        core.end_frame(second, true).unwrap();
        let snapshot = core.snapshot();
        assert_eq!(snapshot.frames_started, 2);
        assert_eq!(snapshot.submitted_frames, 2);
        assert_eq!(snapshot.dropped_frames, 0);
        assert_eq!(snapshot.late_frames, 0);
    }

    #[test]
    fn validates_frame_tokens_and_counts_drops() {
        let core = running(RuntimeConfig::default());
        assert!(core.wait_frame(0).is_err());
        let frame = core.wait_frame(1_000_000).unwrap();
        let mut altered = frame;
        altered.frame_index += 1;
        assert!(core.end_frame(altered, true).is_err());
        let mut altered_deadline = frame;
        altered_deadline.frame_deadline_ns += 1;
        assert!(core.end_frame(altered_deadline, true).is_err());
        core.end_frame(frame, false).unwrap();
        let snapshot = core.snapshot();
        assert_eq!(snapshot.submitted_frames, 0);
        assert_eq!(snapshot.dropped_frames, 1);
    }

    #[test]
    fn records_late_frames_against_the_original_deadline() {
        let core = running(RuntimeConfig { width: 640, height: 720, target_fps: 30 });
        let first = core.wait_frame(1_000_000_000).unwrap();
        core.end_frame(first, false).unwrap();
        let late = core.wait_frame(1_100_000_000).unwrap();
        assert_eq!(late.frame_deadline_ns, 1_066_666_666);
        core.end_frame(late, true).unwrap();
        let snapshot = core.snapshot();
        assert_eq!(snapshot.late_frames, 1);
        assert_eq!(snapshot.last_lateness_ns, 33_333_334);
    }

    #[test]
    fn recovers_after_runtime_loss_and_shutdown() {
        let core = RuntimeCore::new();
        core.initialize(RuntimeConfig::default()).unwrap();
        core.create_session().unwrap();
        core.mark_lost("GLES context lost").unwrap();
        assert_eq!(core.snapshot().last_error, "GLES context lost");
        core.initialize(RuntimeConfig { width: 1024, height: 1024, target_fps: 20 }).unwrap();
        assert_eq!(core.snapshot().state, RuntimeState::Initialized);
        assert_eq!(core.snapshot().frames_started, 0);
        core.shutdown();
        let snapshot = core.snapshot();
        assert_eq!(snapshot.state, RuntimeState::Cold);
        assert_eq!(snapshot.config, RuntimeConfig::default());
    }

    #[test]
    fn reports_timestamp_overflow_without_starting_a_frame() {
        let core = running(RuntimeConfig::default());
        let error = core.wait_frame(i64::MAX - 10).unwrap_err();
        assert_eq!(error, "Frame deadline overflow.");
        let snapshot = core.snapshot();
        assert_eq!(snapshot.state, RuntimeState::Running);
        assert_eq!(snapshot.frames_started, 0);
        assert_eq!(snapshot.last_error, "Frame deadline overflow.");
    }

    #[test]
    fn concurrent_snapshots_are_safe_during_state_changes() {
        use std::sync::Arc;
        use std::thread;

        let core = Arc::new(RuntimeCore::new());
        core.initialize(RuntimeConfig::default()).unwrap();
        let readers: Vec<_> = (0..4).map(|_| {
            let shared = Arc::clone(&core);
            thread::spawn(move || {
                for _ in 0..500 {
                    let snapshot = shared.snapshot();
                    assert!(snapshot.config.target_fps >= MIN_FPS);
                }
            })
        }).collect();

        core.create_session().unwrap();
        core.begin_session().unwrap();
        for reader in readers {
            reader.join().expect("snapshot reader thread must not panic");
        }
        assert_eq!(core.snapshot().state, RuntimeState::Running);
    }

    #[test]
    fn allows_destroying_a_session_before_it_starts() {
        let core = RuntimeCore::new();
        core.initialize(RuntimeConfig::default()).unwrap();
        core.create_session().unwrap();
        core.destroy_session().unwrap();
        assert_eq!(core.snapshot().state, RuntimeState::Initialized);
    }
}
