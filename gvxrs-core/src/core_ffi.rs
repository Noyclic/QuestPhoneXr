//! Narrow, allocation-free C ABI for host-side integration/parity tests.
//!
//! Handles are integer tokens stored in a process-local registry. No Rust
//! pointer, reference, String, Vec or trait object crosses the ABI boundary.
//! This adapter is not yet connected to Android JNI or the OpenXR loader.

use crate::{FrameTiming, RuntimeConfig, RuntimeCore, RuntimeSnapshot, RuntimeState};
use std::collections::HashMap;
use std::sync::{Mutex, MutexGuard, OnceLock};

const OK: u32 = 1;
const ERROR_INVALID_HANDLE: u32 = 1;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GvxrsCoreStatus {
    pub ok: u32,
    pub error_code: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GvxrsCoreFrameTiming {
    pub frame_index: u64,
    pub predicted_display_time_ns: i64,
    pub predicted_display_period_ns: i64,
    pub frame_deadline_ns: i64,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GvxrsCoreFrameResult {
    pub status: GvxrsCoreStatus,
    pub timing: GvxrsCoreFrameTiming,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub struct GvxrsCoreSnapshot {
    pub status: GvxrsCoreStatus,
    pub state: u32,
    pub width: u32,
    pub height: u32,
    pub target_fps: u32,
    pub frames_started: u64,
    pub submitted_frames: u64,
    pub dropped_frames: u64,
    pub late_frames: u64,
    pub last_lateness_ns: i64,
    pub last_error_code: u32,
}

struct Registry {
    next_handle: u64,
    cores: HashMap<u64, RuntimeCore>,
}

impl Default for Registry {
    fn default() -> Self {
        Self { next_handle: 1, cores: HashMap::new() }
    }
}

static REGISTRY: OnceLock<Mutex<Registry>> = OnceLock::new();

fn lock_registry() -> MutexGuard<'static, Registry> {
    REGISTRY.get_or_init(|| Mutex::new(Registry::default()))
        .lock()
        .unwrap_or_else(std::sync::PoisonError::into_inner)
}

fn error_code(message: &str) -> u32 {
    match message {
        "initialize requires Cold or Lost state." => 2,
        "Per-eye render dimensions must be within 64..8192 pixels." => 3,
        "Target FPS must be within 10..120." => 4,
        "createSession requires Initialized state." => 5,
        "beginSession requires SessionReady state." => 6,
        "waitFrame requires Running state." => 7,
        "waitFrame requires an output and positive monotonic time." => 8,
        "Previous frame has not been ended." => 9,
        "Frame index space is exhausted." => 10,
        "Predicted display time overflow." => 11,
        "Frame deadline overflow." => 12,
        "endFrame requires Running state." => 13,
        "endFrame does not match the active frame." => 14,
        "endFrame timing does not match the active frame." => 15,
        "requestStop requires an active session." => 16,
        "destroySession requires Stopping or SessionReady state." => 17,
        "markLost requires an initialized runtime that is not already Lost." => 18,
        _ => 255,
    }
}

fn status_from_result(result: Result<(), String>) -> GvxrsCoreStatus {
    match result {
        Ok(()) => GvxrsCoreStatus { ok: OK, error_code: 0 },
        Err(message) => GvxrsCoreStatus { ok: 0, error_code: error_code(&message) },
    }
}

fn invalid_handle_status() -> GvxrsCoreStatus {
    GvxrsCoreStatus { ok: 0, error_code: ERROR_INVALID_HANDLE }
}

fn state_code(state: RuntimeState) -> u32 {
    match state {
        RuntimeState::Cold => 0,
        RuntimeState::Initialized => 1,
        RuntimeState::SessionReady => 2,
        RuntimeState::Running => 3,
        RuntimeState::Stopping => 4,
        RuntimeState::Lost => 5,
    }
}

fn timing_to_abi(timing: FrameTiming) -> GvxrsCoreFrameTiming {
    GvxrsCoreFrameTiming {
        frame_index: timing.frame_index,
        predicted_display_time_ns: timing.predicted_display_time_ns,
        predicted_display_period_ns: timing.predicted_display_period_ns,
        frame_deadline_ns: timing.frame_deadline_ns,
    }
}

fn timing_from_abi(timing: GvxrsCoreFrameTiming) -> FrameTiming {
    FrameTiming {
        frame_index: timing.frame_index,
        predicted_display_time_ns: timing.predicted_display_time_ns,
        predicted_display_period_ns: timing.predicted_display_period_ns,
        frame_deadline_ns: timing.frame_deadline_ns,
    }
}

fn snapshot_to_abi(snapshot: RuntimeSnapshot) -> GvxrsCoreSnapshot {
    GvxrsCoreSnapshot {
        status: GvxrsCoreStatus { ok: OK, error_code: 0 },
        state: state_code(snapshot.state),
        width: snapshot.config.width,
        height: snapshot.config.height,
        target_fps: snapshot.config.target_fps,
        frames_started: snapshot.frames_started,
        submitted_frames: snapshot.submitted_frames,
        dropped_frames: snapshot.dropped_frames,
        late_frames: snapshot.late_frames,
        last_lateness_ns: snapshot.last_lateness_ns,
        last_error_code: if snapshot.last_error.is_empty() { 0 } else { error_code(&snapshot.last_error) },
    }
}

/// Creates an independent runtime-core instance. Zero means allocation failure.
///
/// The handle is an opaque integer token and must be released with
/// gvxrs_runtime_core_destroy.
/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_create() -> u64 {
    let mut registry = lock_registry();
    let handle = registry.next_handle;
    if handle == 0 || handle == u64::MAX {
        return 0;
    }
    registry.next_handle += 1;
    registry.cores.insert(handle, RuntimeCore::new());
    handle
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_destroy(handle: u64) -> GvxrsCoreStatus {
    let mut registry = lock_registry();
    if registry.cores.remove(&handle).is_some() {
        GvxrsCoreStatus { ok: OK, error_code: 0 }
    } else {
        invalid_handle_status()
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_initialize(
    handle: u64,
    width: u32,
    height: u32,
    target_fps: u32,
) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.initialize(RuntimeConfig { width, height, target_fps })),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_create_session(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.create_session()),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_begin_session(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.begin_session()),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_wait_frame(
    handle: u64,
    monotonic_now_ns: i64,
) -> GvxrsCoreFrameResult {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => match core.wait_frame(monotonic_now_ns) {
            Ok(timing) => GvxrsCoreFrameResult {
                status: GvxrsCoreStatus { ok: OK, error_code: 0 },
                timing: timing_to_abi(timing),
            },
            Err(message) => GvxrsCoreFrameResult {
                status: GvxrsCoreStatus { ok: 0, error_code: error_code(&message) },
                timing: GvxrsCoreFrameTiming::default(),
            },
        },
        None => GvxrsCoreFrameResult {
            status: invalid_handle_status(),
            timing: GvxrsCoreFrameTiming::default(),
        },
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_end_frame(
    handle: u64,
    timing: GvxrsCoreFrameTiming,
    submitted: u32,
) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.end_frame(timing_from_abi(timing), submitted != 0)),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_request_stop(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.request_stop()),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_destroy_session(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.destroy_session()),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_mark_lost(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => status_from_result(core.mark_lost("Runtime lost.")),
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_shutdown(handle: u64) -> GvxrsCoreStatus {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => {
            core.shutdown();
            GvxrsCoreStatus { ok: OK, error_code: 0 }
        }
        None => invalid_handle_status(),
    }
}

/// SAFETY: symbol export is required by the C ABI; arguments and return values are plain values only.\n#[allow(unsafe_code)]\n#[unsafe(no_mangle)]
pub extern "C" fn gvxrs_runtime_core_snapshot(handle: u64) -> GvxrsCoreSnapshot {
    let registry = lock_registry();
    match registry.cores.get(&handle) {
        Some(core) => snapshot_to_abi(core.snapshot()),
        None => GvxrsCoreSnapshot {
            status: invalid_handle_status(),
            ..GvxrsCoreSnapshot::default()
        },
    }
}
