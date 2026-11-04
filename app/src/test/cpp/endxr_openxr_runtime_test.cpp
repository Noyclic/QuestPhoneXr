#include "endxr_openxr_runtime.hpp"
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <iostream>

namespace {
void expect(bool condition, const char* message) {
    if (!condition) {
        std::cerr << "FAILED: " << message << '\n';
        std::exit(1);
    }
}
void setup(XrNegotiateLoaderInfo& loader, XrNegotiateRuntimeRequest& runtime) {
    loader = {};
    loader.structType = XR_LOADER_INTERFACE_STRUCT_LOADER_INFO;
    loader.structVersion = XR_LOADER_INFO_STRUCT_VERSION;
    loader.structSize = sizeof(loader);
    loader.minInterfaceVersion = 1;
    loader.maxInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loader.minApiVersion = XR_MAKE_VERSION(1, 0, 0);
    loader.maxApiVersion = XR_MAKE_VERSION(1, 1, 99);
    runtime = {};
    runtime.structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtime.structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtime.structSize = sizeof(runtime);
}
void testNegotiation() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate OpenXR loader interface");
    expect(request.runtimeInterfaceVersion == 1, "publish loader interface version");
    expect(request.runtimeApiVersion == XR_MAKE_VERSION(1, 0, 0),
           "publish OpenXR 1.0 support");
    expect(request.getInstanceProcAddr != nullptr, "publish GIPA");
    loader.maxInterfaceVersion = 0;
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) ==
               XR_ERROR_INITIALIZATION_FAILED, "reject incompatible loader interface");
    setup(loader, request);
    loader.minApiVersion = XR_MAKE_VERSION(1, 1, 0);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) ==
               XR_ERROR_API_VERSION_UNSUPPORTED, "reject unsupported API version");
}
void testExtensionEnumerationAndAndroidCreate() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before extension enumeration");
    PFN_xrVoidFunction raw = nullptr;
    expect(request.getInstanceProcAddr(
               XR_NULL_HANDLE, "xrEnumerateInstanceExtensionProperties", &raw) == XR_SUCCESS,
           "resolve extension enumeration");
    auto enumerate = reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(raw);
    std::uint32_t count = 0;
    expect(enumerate(nullptr, 0, &count, nullptr) == XR_SUCCESS && count == 1,
           "report one implemented Android instance extension");
    XrExtensionProperties extension{};
    extension.type = XR_TYPE_EXTENSION_PROPERTIES;
    expect(enumerate(nullptr, 1, &count, &extension) == XR_SUCCESS,
           "fill Android instance extension properties");
    expect(std::strcmp(extension.extensionName,
                       ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0 &&
               extension.extensionVersion == ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION,
           "publish XR_KHR_android_create_instance version 3");

    expect(request.getInstanceProcAddr(XR_NULL_HANDLE, "xrCreateInstance", &raw) == XR_SUCCESS,
           "resolve xrCreateInstance");
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = reinterpret_cast<void*>(0x1000);
    androidInfo.applicationActivity = reinterpret_cast<void*>(0x2000);
    XrInstanceCreateInfo createInfo{};
    createInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    createInfo.next = &androidInfo;
    createInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    const char* enabled[] = {ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    createInfo.enabledExtensionCount = 1;
    createInfo.enabledExtensionNames = enabled;
    XrInstance instance = XR_NULL_HANDLE;
    expect(create(&createInfo, &instance) == XR_SUCCESS && instance != XR_NULL_HANDLE,
           "create Android OpenXR instance with JavaVM and Activity");

    raw = nullptr;
    expect(request.getInstanceProcAddr(instance, "xrDestroyInstance", &raw) == XR_SUCCESS,
           "resolve instance destruction");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(raw);
    expect(destroy(instance) == XR_SUCCESS, "destroy Android OpenXR instance");

    androidInfo.applicationActivity = nullptr;
    expect(create(&createInfo, &instance) == XR_ERROR_VALIDATION_FAILURE,
           "reject Android instance creation without an Activity");
}
void testSystemAndStereoDiscovery() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before system queries");
    PFN_xrVoidFunction raw = nullptr;
    expect(request.getInstanceProcAddr(XR_NULL_HANDLE, "xrCreateInstance", &raw) == XR_SUCCESS,
           "resolve create instance for system tests");
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    XrInstanceCreateInfo info{};
    info.type = XR_TYPE_INSTANCE_CREATE_INFO;
    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    XrInstance instance = XR_NULL_HANDLE;
    expect(create(&info, &instance) == XR_SUCCESS, "create core OpenXR instance");

    raw = nullptr;
    expect(request.getInstanceProcAddr(instance, "xrGetSystem", &raw) == XR_SUCCESS,
           "resolve system selection");
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(raw);
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &systemId) == XR_SUCCESS,
           "select phone HMD system");

    raw = nullptr;
    expect(request.getInstanceProcAddr(instance, "xrGetSystemProperties", &raw) == XR_SUCCESS,
           "resolve system properties");
    auto getSystemProperties = reinterpret_cast<PFN_xrGetSystemProperties>(raw);
    XrSystemProperties systemProperties{};
    systemProperties.type = XR_TYPE_SYSTEM_PROPERTIES;
    expect(getSystemProperties(instance, systemId, &systemProperties) == XR_SUCCESS,
           "query phone system capabilities");
    expect(systemProperties.trackingProperties.orientationTracking == XR_TRUE &&
               systemProperties.trackingProperties.positionTracking == XR_FALSE,
           "advertise orientation tracking without 6DoF position");

    raw = nullptr;
    expect(request.getInstanceProcAddr(
               instance, "xrEnumerateViewConfigurationViews", &raw) == XR_SUCCESS,
           "resolve stereo view enumeration");
    auto enumerateViews = reinterpret_cast<PFN_xrEnumerateViewConfigurationViews>(raw);
    std::uint32_t viewCount = 0;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          0, &viewCount, nullptr) == XR_SUCCESS && viewCount == 2,
           "report two stereo views");
    XrViewConfigurationView views[2]{};
    views[0].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    views[1].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          2, &viewCount, views) == XR_SUCCESS,
           "fill stereo view dimensions");
    expect(views[0].recommendedImageRectWidth == 960 &&
               views[1].recommendedImageRectHeight == 1080,
           "return expected per-eye render recommendations");

    raw = nullptr;
    expect(request.getInstanceProcAddr(instance, "xrDestroyInstance", &raw) == XR_SUCCESS,
           "resolve destroy instance");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(raw);
    expect(destroy(instance) == XR_SUCCESS, "destroy test instance");
    expect(destroy(instance) == XR_ERROR_HANDLE_INVALID, "reject stale instance handle");
}
void testUnsupportedSessionIsExplicit() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before unsupported command test");
    PFN_xrVoidFunction function = nullptr;
    expect(request.getInstanceProcAddr(XR_NULL_HANDLE, "xrCreateSession", &function) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && function == nullptr,
           "do not claim a graphics session before swapchain support exists");
}
}
int main() {
    testNegotiation();
    testExtensionEnumerationAndAndroidCreate();
    testSystemAndStereoDiscovery();
    testUnsupportedSessionIsExplicit();
    std::cout << "EndXR OpenXR bootstrap: all tests passed.\n";
    return 0;
}
