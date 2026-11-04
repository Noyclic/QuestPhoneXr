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
XrInstance createInstance(PFN_xrGetInstanceProcAddr gipa, bool enableAndroid, bool enableGles) {
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = reinterpret_cast<void*>(0x1000);
    androidInfo.applicationActivity = reinterpret_cast<void*>(0x2000);
    XrInstanceCreateInfo info{};
    info.type = XR_TYPE_INSTANCE_CREATE_INFO;
    info.next = enableAndroid ? &androidInfo : nullptr;
    info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    const char* enabled[2]{};
    std::uint32_t count = 0;
    if (enableAndroid) enabled[count++] = ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
    if (enableGles) enabled[count++] = ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;
    info.enabledExtensionCount = count;
    info.enabledExtensionNames = enabled;
    PFN_xrVoidFunction raw = nullptr;
    expect(gipa(XR_NULL_HANDLE, "xrCreateInstance", &raw) == XR_SUCCESS && raw != nullptr,
           "resolve xrCreateInstance");
    XrInstance instance = XR_NULL_HANDLE;
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    expect(create(&info, &instance) == XR_SUCCESS && instance != XR_NULL_HANDLE,
           "create OpenXR instance");
    return instance;
}
PFN_xrVoidFunction resolve(PFN_xrGetInstanceProcAddr gipa, XrInstance instance,
                            const char* name) {
    PFN_xrVoidFunction raw = nullptr;
    expect(gipa(instance, name, &raw) == XR_SUCCESS && raw != nullptr, name);
    return raw;
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
void testExtensionEnumerationAndInstance() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before extension enumeration");
    PFN_xrVoidFunction raw = resolve(request.getInstanceProcAddr, XR_NULL_HANDLE,
                                     "xrEnumerateInstanceExtensionProperties");
    auto enumerate = reinterpret_cast<PFN_xrEnumerateInstanceExtensionProperties>(raw);
    std::uint32_t count = 0;
    expect(enumerate(nullptr, 0, &count, nullptr) == XR_SUCCESS && count == 2,
           "report Android-create and GLES extensions");
    XrExtensionProperties extensions[2]{};
    extensions[0].type = XR_TYPE_EXTENSION_PROPERTIES;
    extensions[1].type = XR_TYPE_EXTENSION_PROPERTIES;
    expect(enumerate(nullptr, 2, &count, extensions) == XR_SUCCESS,
           "fill both extension properties");
    expect(std::strcmp(extensions[0].extensionName,
                       ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME) == 0,
           "publish android-create extension");
    expect(std::strcmp(extensions[1].extensionName,
                       ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME) == 0 &&
               extensions[1].extensionVersion == ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION,
           "publish GLES enable extension version 10");
    expect(enumerate(nullptr, 1, &count, extensions) == XR_ERROR_SIZE_INSUFFICIENT,
           "report insufficient extension enumeration capacity");

    XrInstance instance = createInstance(request.getInstanceProcAddr, true, true);
    raw = resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(raw);
    expect(destroy(instance) == XR_SUCCESS, "destroy OpenXR instance");

    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = reinterpret_cast<void*>(0x1000);
    androidInfo.applicationActivity = nullptr;
    XrInstanceCreateInfo bad{};
    bad.type = XR_TYPE_INSTANCE_CREATE_INFO;
    bad.next = &androidInfo;
    bad.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    const char* enabled[] = {ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    bad.enabledExtensionCount = 1;
    bad.enabledExtensionNames = enabled;
    raw = resolve(request.getInstanceProcAddr, XR_NULL_HANDLE, "xrCreateInstance");
    auto create = reinterpret_cast<PFN_xrCreateInstance>(raw);
    expect(create(&bad, &instance) == XR_ERROR_VALIDATION_FAILURE,
           "reject Android instance without Activity");
}
void testSystemDiscoveryAndGraphicsRequirements() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before system queries");
    XrInstance instance = createInstance(request.getInstanceProcAddr, false, true);
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &systemId) == XR_SUCCESS,
           "select phone HMD system");
    auto getProperties = reinterpret_cast<PFN_xrGetSystemProperties>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystemProperties"));
    XrSystemProperties properties{};
    properties.type = XR_TYPE_SYSTEM_PROPERTIES;
    expect(getProperties(instance, systemId, &properties) == XR_SUCCESS,
           "query phone system capabilities");
    expect(properties.trackingProperties.orientationTracking == XR_TRUE &&
               properties.trackingProperties.positionTracking == XR_FALSE,
           "advertise orientation-only tracking");
    auto getRequirements = reinterpret_cast<PFN_xrGetOpenGLESGraphicsRequirementsKHR>(
        resolve(request.getInstanceProcAddr, instance,
                "xrGetOpenGLESGraphicsRequirementsKHR"));
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    expect(getRequirements(instance, systemId, &requirements) == XR_SUCCESS,
           "query GLES requirements");
    expect(requirements.minApiVersionSupported == XR_MAKE_VERSION(3, 0, 0) &&
               requirements.maxApiVersionSupported == XR_MAKE_VERSION(3, 2, 0),
           "publish GLES 3.0 through 3.2 support");
    auto enumerateViews = reinterpret_cast<PFN_xrEnumerateViewConfigurationViews>(
        resolve(request.getInstanceProcAddr, instance,
                "xrEnumerateViewConfigurationViews"));
    std::uint32_t count = 0;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          0, &count, nullptr) == XR_SUCCESS && count == 2,
           "report two stereo views");
    XrViewConfigurationView views[2]{};
    views[0].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    views[1].type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    expect(enumerateViews(instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                          2, &count, views) == XR_SUCCESS &&
               views[0].recommendedImageRectWidth == 960 &&
               views[1].recommendedImageRectHeight == 1080,
           "return per-eye render recommendations");
    auto destroy = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroy(instance) == XR_SUCCESS, "destroy queried instance");
}
void testGlesSessionSwapchainAndFrameLifecycle() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before session tests");
    XrInstance instance = createInstance(request.getInstanceProcAddr, true, true);
    auto getSystem = reinterpret_cast<PFN_xrGetSystem>(
        resolve(request.getInstanceProcAddr, instance, "xrGetSystem"));
    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    expect(getSystem(instance, &systemInfo, &system) == XR_SUCCESS,
           "get EndXR system for session");

    auto createSession = reinterpret_cast<PFN_xrCreateSession>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSession"));
    XrGraphicsBindingOpenGLESAndroidKHR binding{};
    binding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    binding.display = reinterpret_cast<void*>(0x1000);
    binding.config = reinterpret_cast<void*>(0x2000);
    binding.context = reinterpret_cast<void*>(0x3000);
    XrSessionCreateInfo createInfo{};
    createInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    createInfo.next = &binding;
    createInfo.systemId = system;
    XrSession session = XR_NULL_HANDLE;
    expect(createSession(instance, &createInfo, &session) == XR_SUCCESS &&
               session != XR_NULL_HANDLE,
           "create GLES-backed XR session");

    auto pollEvent = reinterpret_cast<PFN_xrPollEvent>(
        resolve(request.getInstanceProcAddr, instance, "xrPollEvent"));
    XrEventDataBuffer event{};
    event.type = XR_TYPE_EVENT_DATA_BUFFER;
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_READY,
           "session creation queues READY event");

    auto createSwapchain = reinterpret_cast<PFN_xrCreateSwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrCreateSwapchain"));
    XrSwapchainCreateInfo swapInfo{};
    swapInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
    swapInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                          XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
    swapInfo.format = 0x8058; // GL_RGBA8
    swapInfo.sampleCount = 1;
    swapInfo.width = 960;
    swapInfo.height = 1080;
    swapInfo.faceCount = 1;
    swapInfo.arraySize = 1;
    swapInfo.mipCount = 1;
    XrSwapchain left = XR_NULL_HANDLE;
    XrSwapchain right = XR_NULL_HANDLE;
    expect(createSwapchain(session, &swapInfo, &left) == XR_SUCCESS,
           "create left-eye GLES texture swapchain");
    expect(createSwapchain(session, &swapInfo, &right) == XR_SUCCESS,
           "create right-eye GLES texture swapchain");

    auto enumerateImages = reinterpret_cast<PFN_xrEnumerateSwapchainImages>(
        resolve(request.getInstanceProcAddr, instance, "xrEnumerateSwapchainImages"));
    std::uint32_t imageCount = 0;
    expect(enumerateImages(left, 0, &imageCount, nullptr) == XR_SUCCESS &&
               imageCount == 3,
           "report triple-buffered swapchain");
    XrSwapchainImageOpenGLESKHR images[3]{};
    for (auto& image : images) image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
    expect(enumerateImages(left, 3, &imageCount,
                           reinterpret_cast<XrSwapchainImageBaseHeader*>(images)) == XR_SUCCESS &&
               images[0].image != 0 && images[0].image != images[1].image,
           "expose distinct GL texture names");

    auto acquire = reinterpret_cast<PFN_xrAcquireSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrAcquireSwapchainImage"));
    auto wait = reinterpret_cast<PFN_xrWaitSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitSwapchainImage"));
    auto release = reinterpret_cast<PFN_xrReleaseSwapchainImage>(
        resolve(request.getInstanceProcAddr, instance, "xrReleaseSwapchainImage"));
    XrSwapchainImageAcquireInfo acquireInfo{};
    acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
    XrSwapchainImageWaitInfo waitInfo{};
    waitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    waitInfo.timeout = XR_INFINITE_DURATION;
    XrSwapchainImageReleaseInfo releaseInfo{};
    releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
    std::uint32_t imageIndex = 99;
    expect(acquire(left, &acquireInfo, &imageIndex) == XR_SUCCESS && imageIndex < 3,
           "acquire a swapchain image");
    expect(release(left, &releaseInfo) == XR_ERROR_CALL_ORDER_INVALID,
           "require waiting before image release");
    expect(wait(left, &waitInfo) == XR_SUCCESS, "wait for acquired swapchain image");
    expect(release(left, &releaseInfo) == XR_SUCCESS, "release rendered swapchain image");
    for (XrSwapchain swapchain : {right}) {
        expect(acquire(swapchain, &acquireInfo, &imageIndex) == XR_SUCCESS,
               "acquire right-eye image");
        expect(wait(swapchain, &waitInfo) == XR_SUCCESS, "wait right-eye image");
        expect(release(swapchain, &releaseInfo) == XR_SUCCESS, "release right-eye image");
    }

    auto beginSession = reinterpret_cast<PFN_xrBeginSession>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginSession"));
    XrSessionBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    expect(beginSession(session, &beginInfo) == XR_SUCCESS, "begin XR session");
    for (const XrSessionState expected : {
             XR_SESSION_STATE_SYNCHRONIZED,
             XR_SESSION_STATE_VISIBLE,
             XR_SESSION_STATE_FOCUSED
         }) {
        event = {};
        expect(pollEvent(instance, &event) == XR_SUCCESS &&
                   reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state == expected,
               "session state transition event is queued");
    }

    auto waitFrame = reinterpret_cast<PFN_xrWaitFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrWaitFrame"));
    auto beginFrame = reinterpret_cast<PFN_xrBeginFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrBeginFrame"));
    auto endFrame = reinterpret_cast<PFN_xrEndFrame>(
        resolve(request.getInstanceProcAddr, instance, "xrEndFrame"));
    XrFrameWaitInfo frameWait{};
    frameWait.type = XR_TYPE_FRAME_WAIT_INFO;
    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;
    expect(waitFrame(session, &frameWait, &frameState) == XR_SUCCESS &&
               frameState.shouldRender == XR_TRUE && frameState.predictedDisplayTime > 0,
           "wait frame returns a renderable predicted time");
    expect(waitFrame(session, &frameWait, &frameState) == XR_ERROR_CALL_ORDER_INVALID,
           "reject a second wait before ending prior frame");
    XrFrameBeginInfo frameBegin{};
    frameBegin.type = XR_TYPE_FRAME_BEGIN_INFO;
    expect(beginFrame(session, &frameBegin) == XR_SUCCESS, "begin frame");
    expect(beginFrame(session, &frameBegin) == XR_ERROR_CALL_ORDER_INVALID,
           "reject double begin frame");
    XrCompositionLayerProjectionView views[2]{};
    for (auto& view : views) {
        view.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        view.subImage.imageRect.extent = {960, 1080};
    }
    views[0].subImage.swapchain = left;
    views[1].subImage.swapchain = right;
    XrCompositionLayerProjection projection{};
    projection.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projection.viewCount = 2;
    projection.views = views;
    const XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projection)
    };
    XrFrameEndInfo frameEnd{};
    frameEnd.type = XR_TYPE_FRAME_END_INFO;
    frameEnd.displayTime = frameState.predictedDisplayTime;
    frameEnd.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    frameEnd.layerCount = 1;
    frameEnd.layers = layers;
    expect(endFrame(session, &frameEnd) == XR_SUCCESS,
           "submit stereo projection layer to compositor");

    auto requestExit = reinterpret_cast<PFN_xrRequestExitSession>(
        resolve(request.getInstanceProcAddr, instance, "xrRequestExitSession"));
    auto endSession = reinterpret_cast<PFN_xrEndSession>(
        resolve(request.getInstanceProcAddr, instance, "xrEndSession"));
    expect(requestExit(session) == XR_SUCCESS, "request XR session exit");
    event = {};
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_STOPPING,
           "queue STOPPING event");
    expect(endSession(session) == XR_SUCCESS, "end XR session");
    event = {};
    expect(pollEvent(instance, &event) == XR_SUCCESS &&
               reinterpret_cast<XrEventDataSessionStateChanged*>(&event)->state ==
                   XR_SESSION_STATE_IDLE,
           "queue IDLE event");

    auto destroySwapchain = reinterpret_cast<PFN_xrDestroySwapchain>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySwapchain"));
    expect(destroySwapchain(left) == XR_SUCCESS, "destroy left swapchain");
    expect(destroySwapchain(right) == XR_SUCCESS, "destroy right swapchain");
    auto destroySession = reinterpret_cast<PFN_xrDestroySession>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroySession"));
    expect(destroySession(session) == XR_SUCCESS, "destroy XR session");
    auto destroyInstance = reinterpret_cast<PFN_xrDestroyInstance>(
        resolve(request.getInstanceProcAddr, instance, "xrDestroyInstance"));
    expect(destroyInstance(instance) == XR_SUCCESS, "destroy XR instance");
}
void testUnsupportedCommandsAreNotAdvertised() {
    XrNegotiateLoaderInfo loader{};
    XrNegotiateRuntimeRequest request{};
    setup(loader, request);
    expect(xrNegotiateLoaderRuntimeInterface(&loader, &request) == XR_SUCCESS,
           "negotiate before unsupported command check");
    PFN_xrVoidFunction function = nullptr;
    expect(request.getInstanceProcAddr(XR_NULL_HANDLE, "xrCreateActionSet", &function) ==
               XR_ERROR_FUNCTION_UNSUPPORTED && function == nullptr,
           "do not claim input action support before implementation");
}
}
int main() {
    testNegotiation();
    testExtensionEnumerationAndInstance();
    testSystemDiscoveryAndGraphicsRequirements();
    testGlesSessionSwapchainAndFrameLifecycle();
    testUnsupportedCommandsAreNotAdvertised();
    std::cout << "EndXR OpenXR GLES session and swapchain tests: all passed.\n";
    return 0;
}
