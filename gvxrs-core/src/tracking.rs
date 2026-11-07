//! Pure, platform-independent tracking/input sanitization.
//!
//! These routines mirror the boundary checks currently performed by
//! GVXRS Mobile's C++ input setters. They are deliberately not wired into the
//! loader-facing OpenXR runtime yet; parity is tested before integration.

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct ControllerInput {
    pub active: bool,
    pub trigger: f32,
    pub grip: f32,
    pub stick_x: f32,
    pub stick_y: f32,
    pub buttons: u32,
}

/// Mirrors endxrSetControllerInput's finite-value fallback and analog ranges.
pub fn sanitize_controller_input(
    active: bool,
    trigger: f32,
    grip: f32,
    stick_x: f32,
    stick_y: f32,
    buttons: u32,
) -> ControllerInput {
    fn clean(value: f32) -> f32 {
        if value.is_finite() {
            value.clamp(-1.0, 1.0)
        } else {
            0.0
        }
    }

    ControllerInput {
        active,
        trigger: clean(trigger).clamp(0.0, 1.0),
        grip: clean(grip).clamp(0.0, 1.0),
        stick_x: clean(stick_x),
        stick_y: clean(stick_y),
        buttons,
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Quaternion {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub w: f32,
}

impl Quaternion {
    pub const IDENTITY: Self = Self { x: 0.0, y: 0.0, z: 0.0, w: 1.0 };
}

#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct Vector3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct HandPose {
    pub orientation: Quaternion,
    pub position: Vector3,
}

impl HandPose {
    pub const NEUTRAL: Self = Self {
        orientation: Quaternion::IDENTITY,
        position: Vector3 { x: 0.0, y: 0.0, z: 0.0 },
    };
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct PoseInput {
    pub active: bool,
    pub pose: HandPose,
}

impl PoseInput {
    const INACTIVE: Self = Self { active: false, pose: HandPose::NEUTRAL };
}

/// Mirrors the current GVXRS Mobile hand-pose validation envelope.
///
/// Positions are phone-camera-relative estimates, not calibrated 6DoF poses.
/// The existing runtime accepts x in [-8, 8], y in [-6, 6], and z in [-6, -0.25].
pub fn sanitize_hand_pose(active: bool, position: Vector3, orientation: Quaternion) -> PoseInput {
    let values = [
        position.x, position.y, position.z,
        orientation.x, orientation.y, orientation.z, orientation.w,
    ];
    if !active || values.iter().any(|value| !value.is_finite()) {
        return PoseInput::INACTIVE;
    }

    // Use f64 intermediates to match the C++ double-precision magnitude check.
    let magnitude = ((orientation.x as f64 * orientation.x as f64)
        + (orientation.y as f64 * orientation.y as f64)
        + (orientation.z as f64 * orientation.z as f64)
        + (orientation.w as f64 * orientation.w as f64))
        .sqrt();

    if !magnitude.is_finite()
        || !(0.5..=1.5).contains(&magnitude)
        || position.x.abs() > 8.0
        || position.y.abs() > 6.0
        || position.z > -0.25
        || position.z < -6.0
    {
        return PoseInput::INACTIVE;
    }

    let inverse = 1.0 / magnitude;
    PoseInput {
        active: true,
        pose: HandPose {
            orientation: Quaternion {
                x: (orientation.x as f64 * inverse) as f32,
                y: (orientation.y as f64 * inverse) as f32,
                z: (orientation.z as f64 * inverse) as f32,
                w: (orientation.w as f64 * inverse) as f32,
            },
            position,
        },
    }
}

/// Returns a normalized orientation only when its source magnitude is
/// finite and within the legacy runtime's accepted [0.5, 1.5] interval.
pub fn sanitize_head_orientation(orientation: Quaternion) -> Option<Quaternion> {
    let magnitude = ((orientation.x as f64 * orientation.x as f64)
        + (orientation.y as f64 * orientation.y as f64)
        + (orientation.z as f64 * orientation.z as f64)
        + (orientation.w as f64 * orientation.w as f64))
        .sqrt();

    if !magnitude.is_finite() || !(0.5..=1.5).contains(&magnitude) {
        return None;
    }

    let inverse = 1.0 / magnitude;
    Some(Quaternion {
        x: (orientation.x as f64 * inverse) as f32,
        y: (orientation.y as f64 * inverse) as f32,
        z: (orientation.z as f64 * inverse) as f32,
        w: (orientation.w as f64 * inverse) as f32,
    })
}

/// Stable C ABI representation used by host-side parity tests and a future
/// narrow JNI/native adapter. These types contain no pointers or Rust-owned
/// allocations and can be returned by value across the C boundary.
#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GvxrsControllerInput {
    pub active: u32,
    pub trigger: f32,
    pub grip: f32,
    pub stick_x: f32,
    pub stick_y: f32,
    pub buttons: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GvxrsQuaternion {
    pub x: f32,
    pub y: f32,
    pub z: f32,
    pub w: f32,
}

impl From<Quaternion> for GvxrsQuaternion {
    fn from(value: Quaternion) -> Self {
        Self { x: value.x, y: value.y, z: value.z, w: value.w }
    }
}

impl From<GvxrsQuaternion> for Quaternion {
    fn from(value: GvxrsQuaternion) -> Self {
        Self { x: value.x, y: value.y, z: value.z, w: value.w }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GvxrsVector3 {
    pub x: f32,
    pub y: f32,
    pub z: f32,
}

impl From<GvxrsVector3> for Vector3 {
    fn from(value: GvxrsVector3) -> Self {
        Self { x: value.x, y: value.y, z: value.z }
    }
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GvxrsPoseInput {
    pub active: u32,
    pub orientation: GvxrsQuaternion,
    pub position: GvxrsVector3,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq)]
pub struct GvxrsHeadOrientation {
    pub active: u32,
    pub orientation: GvxrsQuaternion,
}

/// C ABI wrapper around controller input sanitization.
///
/// The function is intentionally stateless and safe to call from C/C++.
/// It does not publish input into the OpenXR runtime.
#[no_mangle]
pub extern "C" fn gvxrs_sanitize_controller_input(
    active: u32,
    trigger: f32,
    grip: f32,
    stick_x: f32,
    stick_y: f32,
    buttons: u32,
) -> GvxrsControllerInput {
    let value = sanitize_controller_input(
        active != 0, trigger, grip, stick_x, stick_y, buttons,
    );
    GvxrsControllerInput {
        active: u32::from(value.active),
        trigger: value.trigger,
        grip: value.grip,
        stick_x: value.stick_x,
        stick_y: value.stick_y,
        buttons: value.buttons,
    }
}

/// C ABI wrapper around estimated hand-pose validation and normalization.
#[no_mangle]
pub extern "C" fn gvxrs_sanitize_hand_pose(
    active: u32,
    position: GvxrsVector3,
    orientation: GvxrsQuaternion,
) -> GvxrsPoseInput {
    let value = sanitize_hand_pose(active != 0, position.into(), orientation.into());
    GvxrsPoseInput {
        active: u32::from(value.active),
        orientation: value.pose.orientation.into(),
        position: GvxrsVector3 {
            x: value.pose.position.x,
            y: value.pose.position.y,
            z: value.pose.position.z,
        },
    }
}

/// C ABI wrapper around phone head-orientation normalization.
#[no_mangle]
pub extern "C" fn gvxrs_sanitize_head_orientation(
    orientation: GvxrsQuaternion,
) -> GvxrsHeadOrientation {
    match sanitize_head_orientation(orientation.into()) {
        Some(value) => GvxrsHeadOrientation {
            active: 1,
            orientation: value.into(),
        },
        None => GvxrsHeadOrientation {
            active: 0,
            orientation: Quaternion::IDENTITY.into(),
        },
    }
}


#[cfg(test)]
mod tests {
    use super::*;

    fn close(left: f32, right: f32) {
        assert!((left - right).abs() < 0.00001, "{left} != {right}");
    }

    #[test]
    fn controller_values_are_clamped_and_non_finite_values_are_neutralized() {
        let value = sanitize_controller_input(
            true,
            2.0,
            -0.5,
            f32::NAN,
            f32::INFINITY,
            0xA5,
        );
        assert!(value.active);
        close(value.trigger, 1.0);
        close(value.grip, 0.0);
        close(value.stick_x, 0.0);
        close(value.stick_y, 0.0);
        assert_eq!(value.buttons, 0xA5);

        let negative = sanitize_controller_input(false, -1.0, 0.25, -2.0, 2.0, 0);
        assert!(!negative.active);
        close(negative.trigger, 0.0);
        close(negative.grip, 0.25);
        close(negative.stick_x, -1.0);
        close(negative.stick_y, 1.0);
    }

    #[test]
    fn valid_hand_pose_is_normalized_without_changing_position() {
        let position = Vector3 { x: 1.0, y: -2.0, z: -3.0 };
        let result = sanitize_hand_pose(
            true,
            position,
            Quaternion { x: 0.0, y: 0.0, z: 0.70710677, w: 0.70710677 },
        );
        assert!(result.active);
        assert_eq!(result.pose.position, position);
        close(result.pose.orientation.z, std::f32::consts::FRAC_1_SQRT_2);
        close(result.pose.orientation.w, std::f32::consts::FRAC_1_SQRT_2);
    }

    #[test]
    fn hand_pose_rejects_non_finite_and_out_of_envelope_samples() {
        let identity = Quaternion::IDENTITY;
        for position in [
            Vector3 { x: f32::NAN, y: 0.0, z: -1.0 },
            Vector3 { x: 8.001, y: 0.0, z: -1.0 },
            Vector3 { x: 0.0, y: -6.001, z: -1.0 },
            Vector3 { x: 0.0, y: 0.0, z: -0.249 },
            Vector3 { x: 0.0, y: 0.0, z: -6.001 },
        ] {
            let result = sanitize_hand_pose(true, position, identity);
            assert_eq!(result, PoseInput::INACTIVE);
        }

        for orientation in [
            Quaternion { x: f32::NAN, y: 0.0, z: 0.0, w: 1.0 },
            Quaternion { x: 0.0, y: 0.0, z: 0.0, w: 0.0 },
            Quaternion { x: 0.0, y: 0.0, z: 0.0, w: 1.501 },
        ] {
            let result = sanitize_hand_pose(
                true,
                Vector3 { x: 0.0, y: 0.0, z: -1.0 },
                orientation,
            );
            assert_eq!(result, PoseInput::INACTIVE);
        }

        assert_eq!(
            sanitize_hand_pose(false, Vector3::default(), identity),
            PoseInput::INACTIVE
        );
    }

    #[test]
    fn hand_pose_accepts_inclusive_position_and_quaternion_boundaries() {
        let result = sanitize_hand_pose(
            true,
            Vector3 { x: 8.0, y: -6.0, z: -6.0 },
            Quaternion { x: 0.0, y: 0.0, z: 0.0, w: 0.5 },
        );
        assert!(result.active);
        close(result.pose.orientation.w, 1.0);

        let result = sanitize_hand_pose(
            true,
            Vector3 { x: -8.0, y: 6.0, z: -0.25 },
            Quaternion { x: 0.0, y: 0.0, z: 0.0, w: 1.5 },
        );
        assert!(result.active);
        close(result.pose.orientation.w, 1.0);
    }

    #[test]
    fn head_orientation_is_normalized_or_rejected() {
        let normalized = sanitize_head_orientation(Quaternion {
            x: 0.0, y: 0.0, z: 0.70710677, w: 0.70710677,
        }).unwrap();
        close(normalized.z, std::f32::consts::FRAC_1_SQRT_2);
        close(normalized.w, std::f32::consts::FRAC_1_SQRT_2);

        assert!(sanitize_head_orientation(Quaternion {
            x: 0.0, y: 0.0, z: f32::INFINITY, w: 1.0,
        }).is_none());
        assert!(sanitize_head_orientation(Quaternion {
            x: 0.0, y: 0.0, z: 0.0, w: 0.49,
        }).is_none());
        assert!(sanitize_head_orientation(Quaternion {
            x: 0.0, y: 0.0, z: 0.0, w: 1.51,
        }).is_none());
    }
}

