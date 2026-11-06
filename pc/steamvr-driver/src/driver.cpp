#include <openvr_driver.h>

#include <algorithm>
#include <cstdint>
#include <cstring>

#if defined(_WIN32)
#define ENDVR_EXPORT extern "C" __declspec(dllexport)
#else
#define ENDVR_EXPORT extern "C" __attribute__((visibility("default")))
#endif

namespace {
constexpr char kSerial[] = "GVXRS-LINK-PHONE-HMD-0001";
constexpr uint32_t kEyeWidth = 960;
constexpr uint32_t kEyeHeight = 1080;
constexpr float kRefreshRate = 60.0f;

vr::HmdQuaternion_t IdentityQuaternion() {
    return {1.0, 0.0, 0.0, 0.0};
}

class EndVrHmd final : public vr::ITrackedDeviceServerDriver,
                       public vr::IVRDisplayComponent {
public:
    vr::EVRInitError Activate(uint32_t objectId) override {
        objectId_ = objectId;
        active_ = true;
        auto properties = vr::VRProperties();
        const auto container = properties->TrackedDeviceToPropertyContainer(objectId_);
        properties->SetStringProperty(container, vr::Prop_TrackingSystemName_String, "gvxrs_link");
        properties->SetStringProperty(container, vr::Prop_ModelNumber_String, "GVXRS Link Phone HMD (prototype)");
        properties->SetStringProperty(container, vr::Prop_SerialNumber_String, kSerial);
        properties->SetStringProperty(container, vr::Prop_ManufacturerName_String, "Noyclic");
        properties->SetStringProperty(container, vr::Prop_RenderModelName_String, "generic_hmd");
        properties->SetFloatProperty(container, vr::Prop_UserIpdMeters_Float, 0.064f);
        properties->SetFloatProperty(container, vr::Prop_DisplayFrequency_Float, kRefreshRate);
        properties->SetBoolProperty(container, vr::Prop_IsOnDesktop_Bool, false);
        return vr::VRInitError_None;
    }

    void Deactivate() override {
        active_ = false;
        objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    }

    void EnterStandby() override {}
    void DebugRequest(const char*, char* response, uint32_t size) override {
        if (response && size) response[0] = '\0';
    }

    void* GetComponent(const char* version) override {
        if (version && std::strcmp(version, vr::IVRDisplayComponent_Version) == 0) {
            return static_cast<vr::IVRDisplayComponent*>(this);
        }
        return nullptr;
    }

    vr::DriverPose_t GetPose() override { return MakePose(); }

    void PublishPose() {
        if (!active_) return;
        auto pose = MakePose();
        vr::VRServerDriverHost()->TrackedDevicePoseUpdated(
            objectId_, pose, sizeof(vr::DriverPose_t));
    }

    void GetWindowBounds(int32_t* x, int32_t* y, uint32_t* width, uint32_t* height) override {
        *x = 0; *y = 0; *width = kEyeWidth * 2; *height = kEyeHeight;
    }
    bool IsDisplayOnDesktop() override { return false; }
    bool IsDisplayRealDisplay() override { return false; }
    void GetRecommendedRenderTargetSize(uint32_t* width, uint32_t* height) override {
        *width = kEyeWidth * 2; *height = kEyeHeight;
    }
    void GetEyeOutputViewport(vr::EVREye eye, uint32_t* x, uint32_t* y,
                              uint32_t* width, uint32_t* height) override {
        *x = eye == vr::Eye_Left ? 0 : kEyeWidth;
        *y = 0; *width = kEyeWidth; *height = kEyeHeight;
    }
    void GetProjectionRaw(vr::EVREye, float* left, float* right,
                          float* top, float* bottom) override {
        *left = -1.0f; *right = 1.0f; *top = -1.0f; *bottom = 1.0f;
    }
    vr::DistortionCoordinates_t ComputeDistortion(vr::EVREye, float u, float v) override {
        vr::DistortionCoordinates_t result{};
        for (int channel = 0; channel < 2; ++channel) {
            result.rfRed[channel] = channel == 0 ? u : v;
            result.rfGreen[channel] = channel == 0 ? u : v;
            result.rfBlue[channel] = channel == 0 ? u : v;
        }
        return result;
    }
    bool ComputeInverseDistortion(vr::HmdVector2_t* result, vr::EVREye,
                                  uint32_t, float u, float v) override {
        if (!result) return false;
        result->v[0] = u; result->v[1] = v;
        return true;
    }

private:
    vr::DriverPose_t MakePose() const {
        vr::DriverPose_t pose{};
        pose.poseTimeOffset = 0.0;
        pose.qWorldFromDriverRotation = IdentityQuaternion();
        pose.qDriverFromHeadRotation = IdentityQuaternion();
        pose.qRotation = IdentityQuaternion();
        pose.result = vr::TrackingResult_Running_OK;
        pose.poseIsValid = true;
        pose.deviceIsConnected = true;
        pose.willDriftInYaw = false;
        pose.shouldApplyHeadModel = false;
        return pose;
    }

    uint32_t objectId_ = vr::k_unTrackedDeviceIndexInvalid;
    bool active_ = false;
};

class EndVrProvider final : public vr::IServerTrackedDeviceProvider {
public:
    vr::EVRInitError Init(vr::IVRDriverContext* context) override {
        VR_INIT_SERVER_DRIVER_CONTEXT(context);
        hmd_ = new EndVrHmd();
        if (!vr::VRServerDriverHost()->TrackedDeviceAdded(
                kSerial, vr::TrackedDeviceClass_HMD, hmd_)) {
            delete hmd_;
            hmd_ = nullptr;
            VR_CLEANUP_SERVER_DRIVER_CONTEXT();
            return vr::VRInitError_Driver_Unknown;
        }
        return vr::VRInitError_None;
    }

    void Cleanup() override {
        delete hmd_;
        hmd_ = nullptr;
        VR_CLEANUP_SERVER_DRIVER_CONTEXT();
    }
    const char* const* GetInterfaceVersions() override { return vr::k_InterfaceVersions; }
    void RunFrame() override { if (hmd_) hmd_->PublishPose(); }
    bool ShouldBlockStandbyMode() override { return false; }
    void EnterStandby() override {}
    void LeaveStandby() override {}

private:
    EndVrHmd* hmd_ = nullptr;
};

EndVrProvider g_provider;
} // namespace

ENDVR_EXPORT void* HmdDriverFactory(const char* interfaceName, int* returnCode) {
    if (interfaceName && std::strcmp(interfaceName, vr::IServerTrackedDeviceProvider_Version) == 0) {
        return &g_provider;
    }
    if (returnCode) *returnCode = vr::VRInitError_Init_InterfaceNotFound;
    return nullptr;
}
