#include "gvxrs_core.h"

#include <cmath>
#include <cstddef>
#include <cstdlib>
#include <iostream>
#include <limits>
#include <type_traits>

namespace {
void expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}
void expectNear(float actual, float expected, const char* message) {
    expect(std::isfinite(actual) && std::abs(actual - expected) < 0.00001f, message);
}

void testCAbiLayout() {
    static_assert(std::is_standard_layout<GvxrsControllerInput>::value,
                  "controller input must be standard-layout");
    static_assert(sizeof(GvxrsControllerInput) == 24,
                  "controller input C ABI size changed");
    static_assert(offsetof(GvxrsControllerInput, buttons) == 20,
                  "controller input C ABI offsets changed");
    static_assert(sizeof(GvxrsQuaternion) == 16, "quaternion C ABI size changed");
    static_assert(sizeof(GvxrsVector3) == 12, "vector C ABI size changed");
    static_assert(sizeof(GvxrsPoseInput) == 32, "pose C ABI size changed");
    static_assert(sizeof(GvxrsHeadOrientation) == 20,
                  "head orientation C ABI size changed");
}

void testControllerSanitization() {
    const auto value = gvxrs_sanitize_controller_input(
        1,
        2.0f,
        -0.5f,
        std::numeric_limits<float>::quiet_NaN(),
        std::numeric_limits<float>::infinity(),
        0xA5u
    );
    expect(value.active == 1u, "preserve active controller state");
    expectNear(value.trigger, 1.0f, "clamp trigger to upper bound");
    expectNear(value.grip, 0.0f, "clamp grip to lower bound");
    expectNear(value.stick_x, 0.0f, "neutralize NaN stick value");
    expectNear(value.stick_y, 0.0f, "neutralize infinite stick value");
    expect(value.buttons == 0xA5u, "preserve the button bit field");

    const auto negative = gvxrs_sanitize_controller_input(0, -1.0f, 0.25f, -2.0f, 2.0f, 0u);
    expect(negative.active == 0u, "preserve inactive controller state");
    expectNear(negative.trigger, 0.0f, "trigger lower bound");
    expectNear(negative.grip, 0.25f, "preserve valid grip input");
    expectNear(negative.stick_x, -1.0f, "clamp negative stick value");
    expectNear(negative.stick_y, 1.0f, "clamp positive stick value");
}

void testHandPoseValidation() {
    const GvxrsVector3 position{1.0f, -2.0f, -3.0f};
    const GvxrsQuaternion rotation{0.0f, 0.0f, 0.70710677f, 0.70710677f};
    const auto valid = gvxrs_sanitize_hand_pose(1u, position, rotation);
    expect(valid.active == 1u, "accept an in-range hand pose");
    expectNear(valid.position.x, position.x, "preserve hand x coordinate");
    expectNear(valid.position.y, position.y, "preserve hand y coordinate");
    expectNear(valid.position.z, position.z, "preserve hand z coordinate");
    expectNear(valid.orientation.z, 0.70710678f, "normalize hand quaternion z");
    expectNear(valid.orientation.w, 0.70710678f, "normalize hand quaternion w");

    const auto invalidPosition = gvxrs_sanitize_hand_pose(
        1u, GvxrsVector3{8.01f, 0.0f, -1.0f}, GvxrsQuaternion{0.0f, 0.0f, 0.0f, 1.0f}
    );
    expect(invalidPosition.active == 0u, "reject out-of-range hand position");
    expectNear(invalidPosition.orientation.w, 1.0f, "neutralize rejected pose orientation");
    expectNear(invalidPosition.position.x, 0.0f, "neutralize rejected pose position");

    const auto invalidRotation = gvxrs_sanitize_hand_pose(
        1u, GvxrsVector3{0.0f, 0.0f, -1.0f}, GvxrsQuaternion{0.0f, 0.0f, 0.0f, 0.0f}
    );
    expect(invalidRotation.active == 0u, "reject degenerate hand quaternion");

    const auto inactive = gvxrs_sanitize_hand_pose(
        0u, position, rotation
    );
    expect(inactive.active == 0u, "preserve inactive hand state");
    expectNear(inactive.position.x, 0.0f, "inactive hand pose is neutral");
}

void testHeadOrientationValidation() {
    const auto valid = gvxrs_sanitize_head_orientation(
        GvxrsQuaternion{0.0f, 0.0f, 0.70710677f, 0.70710677f}
    );
    expect(valid.active == 1u, "accept valid head orientation");
    expectNear(valid.orientation.z, 0.70710678f, "normalize head quaternion z");
    expectNear(valid.orientation.w, 0.70710678f, "normalize head quaternion w");

    const auto invalid = gvxrs_sanitize_head_orientation(
        GvxrsQuaternion{0.0f, 0.0f, std::numeric_limits<float>::infinity(), 1.0f}
    );
    expect(invalid.active == 0u, "reject infinite head orientation");
    expectNear(invalid.orientation.w, 1.0f, "return identity when head orientation is invalid");
}
} // namespace

int main() {
    testCAbiLayout();
    testControllerSanitization();
    testHandPoseValidation();
    testHeadOrientationValidation();
    std::cout << "GVXRS Rust C ABI tracking tests: all passed.\n";
    return 0;
}
