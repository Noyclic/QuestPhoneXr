#include "endxr_openxr_android_self_test.hpp"

#include "endxr_openxr_runtime.hpp"

#include <EGL/egl.h>
#include <GLES3/gl3.h>

#include <algorithm>
#include <array>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <sstream>
#include <string>
#include <vector>

namespace {

std::string formatResult(XrResult result) {
    char value[24]{};
    std::snprintf(value, sizeof(value), "0x%08x",
                  static_cast<unsigned int>(result));
    return value;
}

std::string failure(const char* stage, XrResult result) {
    return std::string("OpenXR probe: FAIL · ") + stage + " · " + formatResult(result);
}

template <typename Function>
bool loadFunction(PFN_xrGetInstanceProcAddr getProc,
                  XrInstance instance,
                  const char* name,
                  Function* function) {
    if (getProc == nullptr || function == nullptr) return false;
    PFN_xrVoidFunction raw = nullptr;
    const XrResult result = getProc(instance, name, &raw);
    if (result != XR_SUCCESS || raw == nullptr) return false;
    *function = reinterpret_cast<Function>(raw);
    return true;
}

struct RuntimeApi {
    PFN_xrDestroyInstance destroyInstance = nullptr;
    PFN_xrGetSystem getSystem = nullptr;
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getGlesRequirements = nullptr;
    PFN_xrCreateSession createSession = nullptr;
    PFN_xrDestroySession destroySession = nullptr;
    PFN_xrBeginSession beginSession = nullptr;
    PFN_xrRequestExitSession requestExitSession = nullptr;
    PFN_xrEndSession endSession = nullptr;
    PFN_xrEnumerateViewConfigurationViews enumerateViewConfigurationViews = nullptr;
    PFN_xrCreateReferenceSpace createReferenceSpace = nullptr;
    PFN_xrDestroySpace destroySpace = nullptr;
    PFN_xrLocateSpace locateSpace = nullptr;
    PFN_xrLocateViews locateViews = nullptr;
    PFN_xrEnumerateSwapchainFormats enumerateSwapchainFormats = nullptr;
    PFN_xrCreateSwapchain createSwapchain = nullptr;
    PFN_xrDestroySwapchain destroySwapchain = nullptr;
    PFN_xrEnumerateSwapchainImages enumerateSwapchainImages = nullptr;
    PFN_xrAcquireSwapchainImage acquireSwapchainImage = nullptr;
    PFN_xrWaitSwapchainImage waitSwapchainImage = nullptr;
    PFN_xrReleaseSwapchainImage releaseSwapchainImage = nullptr;
    PFN_xrWaitFrame waitFrame = nullptr;
    PFN_xrBeginFrame beginFrame = nullptr;
    PFN_xrEndFrame endFrame = nullptr;
};

bool paintStereoDiagnosticPattern(GLuint texture, GLsizei width, GLsizei height) {
    if (texture == 0 || width < 2 || height < 1) return false;

    // Discard stale application errors so this result belongs to the probe itself.
    while (glGetError() != GL_NO_ERROR) {}

    GLint previousReadFramebuffer = 0;
    GLint previousDrawFramebuffer = 0;
    GLint previousViewport[4]{};
    GLint previousScissorBox[4]{};
    GLfloat previousClearColor[4]{};
    GLboolean previousColorMask[4]{};
    const GLboolean previousScissorEnabled =
        glIsEnabled(GL_SCISSOR_TEST) == GL_TRUE ? GL_TRUE : GL_FALSE;
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
    glGetIntegerv(GL_VIEWPORT, previousViewport);
    glGetIntegerv(GL_SCISSOR_BOX, previousScissorBox);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, previousClearColor);
    glGetBooleanv(GL_COLOR_WRITEMASK, previousColorMask);

    GLuint framebuffer = 0;
    glGenFramebuffers(1, &framebuffer);
    if (framebuffer == 0) return false;

    glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
    glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                           GL_TEXTURE_2D, texture, 0);
    const bool complete =
        glCheckFramebufferStatus(GL_FRAMEBUFFER) == GL_FRAMEBUFFER_COMPLETE;
    bool rendered = false;
    if (complete) {
        const GLsizei leftWidth = width / 2;
        glViewport(0, 0, width, height);
        glEnable(GL_SCISSOR_TEST);
        glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);

        glScissor(0, 0, leftWidth, height);
        glClearColor(0.92f, 0.08f, 0.06f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);

        glScissor(leftWidth, 0, width - leftWidth, height);
        glClearColor(0.06f, 0.12f, 0.94f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
        rendered = glGetError() == GL_NO_ERROR;
    }

    glBindFramebuffer(GL_READ_FRAMEBUFFER,
                      static_cast<GLuint>(previousReadFramebuffer));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER,
                      static_cast<GLuint>(previousDrawFramebuffer));
    glViewport(previousViewport[0], previousViewport[1],
               previousViewport[2], previousViewport[3]);
    glScissor(previousScissorBox[0], previousScissorBox[1],
              previousScissorBox[2], previousScissorBox[3]);
    if (previousScissorEnabled == GL_TRUE) glEnable(GL_SCISSOR_TEST);
    else glDisable(GL_SCISSOR_TEST);
    glColorMask(previousColorMask[0], previousColorMask[1],
                previousColorMask[2], previousColorMask[3]);
    glClearColor(previousClearColor[0], previousClearColor[1],
                 previousClearColor[2], previousClearColor[3]);
    glDeleteFramebuffers(1, &framebuffer);
    return complete && rendered;
}

} // namespace

static std::string runEndXrOpenXrAndroidSelfTestImpl(JavaVM* vm, jobject activity) {
    if (vm == nullptr || activity == nullptr) {
        return "OpenXR probe: FAIL · Android VM/activity missing";
    }

    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLContext context = eglGetCurrentContext();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT ||
        surface == EGL_NO_SURFACE) {
        return "OpenXR probe: FAIL · no current EGL display/context/surface";
    }

    EGLint surfaceWidth = 0;
    EGLint surfaceHeight = 0;
    if (eglQuerySurface(display, surface, EGL_WIDTH, &surfaceWidth) != EGL_TRUE ||
        eglQuerySurface(display, surface, EGL_HEIGHT, &surfaceHeight) != EGL_TRUE ||
        surfaceWidth < 2 || surfaceHeight < 1) {
        return "OpenXR probe: FAIL · EGL surface dimensions unavailable";
    }

    EGLint configId = 0;
    if (eglQueryContext(display, context, EGL_CONFIG_ID, &configId) != EGL_TRUE ||
        configId <= 0) {
        return "OpenXR probe: FAIL · EGL config ID unavailable";
    }

    EGLint configCount = 0;
    if (eglGetConfigs(display, nullptr, 0, &configCount) != EGL_TRUE ||
        configCount <= 0) {
        return "OpenXR probe: FAIL · EGL configs unavailable";
    }
    std::vector<EGLConfig> configs(static_cast<std::size_t>(configCount));
    EGLint returnedConfigs = 0;
    if (eglGetConfigs(display, configs.data(), configCount, &returnedConfigs) != EGL_TRUE) {
        return "OpenXR probe: FAIL · EGL config enumeration failed";
    }
    EGLConfig currentConfig = nullptr;
    for (EGLint i = 0; i < returnedConfigs; ++i) {
        EGLint candidateId = 0;
        if (eglGetConfigAttrib(display, configs[static_cast<std::size_t>(i)],
                               EGL_CONFIG_ID, &candidateId) == EGL_TRUE &&
            candidateId == configId) {
            currentConfig = configs[static_cast<std::size_t>(i)];
            break;
        }
    }
    if (currentConfig == nullptr) {
        return "OpenXR probe: FAIL · active EGLConfig could not be resolved";
    }

    XrNegotiateLoaderInfo loaderInfo{};
    loaderInfo.structType = XR_LOADER_INTERFACE_STRUCT_LOADER_INFO;
    loaderInfo.structVersion = XR_LOADER_INFO_STRUCT_VERSION;
    loaderInfo.structSize = sizeof(loaderInfo);
    loaderInfo.minInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loaderInfo.maxInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loaderInfo.minApiVersion = XR_MAKE_VERSION(1, 0, 0);
    loaderInfo.maxApiVersion = XR_MAKE_VERSION(1, 0, 0);

    XrNegotiateRuntimeRequest runtimeRequest{};
    runtimeRequest.structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtimeRequest.structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtimeRequest.structSize = sizeof(runtimeRequest);
    const XrResult negotiation = xrNegotiateLoaderRuntimeInterface(
        &loaderInfo, &runtimeRequest);
    if (negotiation != XR_SUCCESS || runtimeRequest.getInstanceProcAddr == nullptr) {
        return failure("runtime negotiation", negotiation);
    }

    const PFN_xrGetInstanceProcAddr getProc = runtimeRequest.getInstanceProcAddr;
    PFN_xrCreateInstance createInstance = nullptr;
    if (!loadFunction(getProc, XR_NULL_HANDLE, "xrCreateInstance", &createInstance)) {
        return "OpenXR probe: FAIL · xrCreateInstance entrypoint missing";
    }

    const char* enabledExtensions[] = {
        ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME
    };
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.next = nullptr;
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = activity;

    XrInstanceCreateInfo instanceInfo{};
    instanceInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.next = &androidInfo;
    std::snprintf(instanceInfo.applicationInfo.applicationName,
                  XR_MAX_APPLICATION_NAME_SIZE, "%s", "QuestPhoneXR Runtime Probe");
    instanceInfo.applicationInfo.applicationVersion = 1;
    std::snprintf(instanceInfo.applicationInfo.engineName,
                  XR_MAX_ENGINE_NAME_SIZE, "%s", "EndXR");
    instanceInfo.applicationInfo.engineVersion = 1;
    instanceInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    instanceInfo.enabledExtensionCount = 2;
    instanceInfo.enabledExtensionNames = enabledExtensions;

    XrInstance instance = XR_NULL_HANDLE;
    XrResult result = createInstance(&instanceInfo, &instance);
    if (result != XR_SUCCESS || instance == XR_NULL_HANDLE) {
        return failure("xrCreateInstance", result);
    }

    RuntimeApi api{};
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSwapchain swapchain = XR_NULL_HANDLE;
    bool sessionRunning = false;
    bool frameWaited = false;
    bool frameBegun = false;
    bool imageAcquired = false;
    bool imageWaited = false;
    std::uint32_t acquiredImageIndex = 0;
    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;

    auto cleanup = [&]() -> std::string {
        std::string cleanupFailure;
        auto rememberFailure = [&](const char* stage, XrResult value) {
            if (value != XR_SUCCESS && cleanupFailure.empty()) {
                cleanupFailure = std::string(stage) + " " + formatResult(value);
            }
        };

        // Release any acquired swapchain image before ending an outstanding frame.
        if (swapchain != XR_NULL_HANDLE && imageAcquired &&
            api.waitSwapchainImage != nullptr && api.releaseSwapchainImage != nullptr) {
            if (!imageWaited) {
                XrSwapchainImageWaitInfo waitInfo{};
                waitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
                waitInfo.timeout = XR_INFINITE_DURATION;
                const XrResult waitResult = api.waitSwapchainImage(swapchain, &waitInfo);
                rememberFailure("cleanup xrWaitSwapchainImage", waitResult);
                imageWaited = waitResult == XR_SUCCESS;
            }
            if (imageWaited) {
                XrSwapchainImageReleaseInfo releaseInfo{};
                releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
                const XrResult releaseResult =
                    api.releaseSwapchainImage(swapchain, &releaseInfo);
                rememberFailure("cleanup xrReleaseSwapchainImage", releaseResult);
                if (releaseResult == XR_SUCCESS) {
                    imageAcquired = false;
                    imageWaited = false;
                }
            }
        }

        // A successful xrWaitFrame must always be paired with begin/end frame.
        if (frameWaited && session != XR_NULL_HANDLE &&
            api.beginFrame != nullptr && api.endFrame != nullptr) {
            if (!frameBegun) {
                XrFrameBeginInfo beginInfo{};
                beginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
                const XrResult beginResult = api.beginFrame(session, &beginInfo);
                rememberFailure("cleanup xrBeginFrame", beginResult);
                frameBegun = beginResult == XR_SUCCESS;
            }
            if (frameBegun) {
                XrFrameEndInfo endInfo{};
                endInfo.type = XR_TYPE_FRAME_END_INFO;
                endInfo.displayTime = frameState.predictedDisplayTime;
                endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
                const XrResult endResult = api.endFrame(session, &endInfo);
                rememberFailure("cleanup xrEndFrame", endResult);
                if (endResult == XR_SUCCESS) {
                    frameBegun = false;
                    frameWaited = false;
                }
            }
        }

        const bool frameIdle = !frameWaited && !frameBegun;
        if (swapchain != XR_NULL_HANDLE && !imageAcquired && frameIdle &&
            api.destroySwapchain != nullptr) {
            const XrResult destroyResult = api.destroySwapchain(swapchain);
            rememberFailure("cleanup xrDestroySwapchain", destroyResult);
            if (destroyResult == XR_SUCCESS) swapchain = XR_NULL_HANDLE;
        }

        if (space != XR_NULL_HANDLE && frameIdle && api.destroySpace != nullptr) {
            const XrResult destroyResult = api.destroySpace(space);
            rememberFailure("cleanup xrDestroySpace", destroyResult);
            if (destroyResult == XR_SUCCESS) space = XR_NULL_HANDLE;
        }

        if (session != XR_NULL_HANDLE && sessionRunning && frameIdle &&
            !imageAcquired && swapchain == XR_NULL_HANDLE && space == XR_NULL_HANDLE) {
            if (api.requestExitSession != nullptr && api.endSession != nullptr) {
                const XrResult requestResult = api.requestExitSession(session);
                rememberFailure("cleanup xrRequestExitSession", requestResult);
                if (requestResult == XR_SUCCESS) {
                    const XrResult endResult = api.endSession(session);
                    rememberFailure("cleanup xrEndSession", endResult);
                    if (endResult == XR_SUCCESS) sessionRunning = false;
                }
            }
        }

        if (session != XR_NULL_HANDLE && !sessionRunning &&
            swapchain == XR_NULL_HANDLE && space == XR_NULL_HANDLE &&
            api.destroySession != nullptr) {
            const XrResult destroyResult = api.destroySession(session);
            rememberFailure("cleanup xrDestroySession", destroyResult);
            if (destroyResult == XR_SUCCESS) session = XR_NULL_HANDLE;
        }

        if (instance != XR_NULL_HANDLE && session == XR_NULL_HANDLE &&
            api.destroyInstance != nullptr) {
            const XrResult destroyResult = api.destroyInstance(instance);
            rememberFailure("cleanup xrDestroyInstance", destroyResult);
            if (destroyResult == XR_SUCCESS) instance = XR_NULL_HANDLE;
        }

        if ((imageAcquired || frameWaited || frameBegun) && cleanupFailure.empty()) {
            cleanupFailure = "cleanup left an acquired image or active XR frame";
        }
        return cleanupFailure;
    };

    auto fail = [&](const char* stage, XrResult value) -> std::string {
        std::string message = failure(stage, value);
        const std::string cleanupResult = cleanup();
        if (!cleanupResult.empty()) message += " · cleanup: " + cleanupResult;
        return message;
    };

#define ENDXR_LOAD(field, name) \
    if (!loadFunction(getProc, instance, name, &api.field)) \
        return fail("missing " name, XR_ERROR_FUNCTION_UNSUPPORTED)
    ENDXR_LOAD(destroyInstance, "xrDestroyInstance");
    ENDXR_LOAD(getSystem, "xrGetSystem");
    ENDXR_LOAD(getGlesRequirements, "xrGetOpenGLESGraphicsRequirementsKHR");
    ENDXR_LOAD(createSession, "xrCreateSession");
    ENDXR_LOAD(destroySession, "xrDestroySession");
    ENDXR_LOAD(beginSession, "xrBeginSession");
    ENDXR_LOAD(requestExitSession, "xrRequestExitSession");
    ENDXR_LOAD(endSession, "xrEndSession");
    ENDXR_LOAD(enumerateViewConfigurationViews, "xrEnumerateViewConfigurationViews");
    ENDXR_LOAD(createReferenceSpace, "xrCreateReferenceSpace");
    ENDXR_LOAD(destroySpace, "xrDestroySpace");
    ENDXR_LOAD(locateSpace, "xrLocateSpace");
    ENDXR_LOAD(locateViews, "xrLocateViews");
    ENDXR_LOAD(enumerateSwapchainFormats, "xrEnumerateSwapchainFormats");
    ENDXR_LOAD(createSwapchain, "xrCreateSwapchain");
    ENDXR_LOAD(destroySwapchain, "xrDestroySwapchain");
    ENDXR_LOAD(enumerateSwapchainImages, "xrEnumerateSwapchainImages");
    ENDXR_LOAD(acquireSwapchainImage, "xrAcquireSwapchainImage");
    ENDXR_LOAD(waitSwapchainImage, "xrWaitSwapchainImage");
    ENDXR_LOAD(releaseSwapchainImage, "xrReleaseSwapchainImage");
    ENDXR_LOAD(waitFrame, "xrWaitFrame");
    ENDXR_LOAD(beginFrame, "xrBeginFrame");
    ENDXR_LOAD(endFrame, "xrEndFrame");
#undef ENDXR_LOAD

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    result = api.getSystem(instance, &systemInfo, &systemId);
    if (result != XR_SUCCESS || systemId == XR_NULL_SYSTEM_ID) {
        return fail("xrGetSystem", result);
    }

    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    result = api.getGlesRequirements(instance, systemId, &requirements);
    if (result != XR_SUCCESS) return fail("xrGetOpenGLESGraphicsRequirementsKHR", result);
    if (XR_VERSION_MAJOR(requirements.minApiVersionSupported) > 3 ||
        XR_VERSION_MAJOR(requirements.maxApiVersionSupported) < 3) {
        return fail("runtime GLES requirements", XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING);
    }

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.next = nullptr;
    graphicsBinding.display = display;
    graphicsBinding.config = currentConfig;
    graphicsBinding.context = context;

    XrSessionCreateInfo sessionInfo{};
    sessionInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionInfo.next = &graphicsBinding;
    sessionInfo.systemId = systemId;
    result = api.createSession(instance, &sessionInfo, &session);
    if (result != XR_SUCCESS || session == XR_NULL_HANDLE) {
        return fail("xrCreateSession (current EGL context)", result);
    }

    std::uint32_t viewCount = 0;
    result = api.enumerateViewConfigurationViews(
        instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        0, &viewCount, nullptr);
    if (result != XR_SUCCESS || viewCount != 2) {
        return fail("enumerate stereo views", result == XR_SUCCESS
            ? XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED : result);
    }
    std::vector<XrViewConfigurationView> viewConfigurations(viewCount);
    for (auto& view : viewConfigurations) {
        view.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
    }
    result = api.enumerateViewConfigurationViews(
        instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        viewCount, &viewCount, viewConfigurations.data());
    if (result != XR_SUCCESS) return fail("read stereo view configuration", result);

    XrReferenceSpaceCreateInfo spaceInfo{};
    spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    result = api.createReferenceSpace(session, &spaceInfo, &space);
    if (result != XR_SUCCESS || space == XR_NULL_HANDLE) {
        return fail("xrCreateReferenceSpace", result);
    }

    std::uint32_t formatCount = 0;
    result = api.enumerateSwapchainFormats(session, 0, &formatCount, nullptr);
    if (result != XR_SUCCESS || formatCount == 0) {
        return fail("enumerate swapchain formats", result);
    }
    std::vector<std::int64_t> formats(formatCount);
    result = api.enumerateSwapchainFormats(session, formatCount, &formatCount, formats.data());
    if (result != XR_SUCCESS) return fail("read swapchain formats", result);
    const std::int64_t rgba8 = static_cast<std::int64_t>(GL_RGBA8);
    if (std::find(formats.begin(), formats.end(), rgba8) == formats.end()) {
        return fail("GLES RGBA8 swapchain format", XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED);
    }

    XrSwapchainCreateInfo swapchainInfo{};
    swapchainInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
    swapchainInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainInfo.format = rgba8;
    swapchainInfo.sampleCount = 1;
    // A single RGBA8 image is split into two eye rectangles. This exercises
    // the same sub-image path used by array/packed stereo swapchains.
    swapchainInfo.width = static_cast<std::uint32_t>(
        std::max(128, std::min(1200, surfaceWidth / 2)));
    swapchainInfo.height = static_cast<std::uint32_t>(
        std::max(64, std::min(640, surfaceHeight)));
    swapchainInfo.faceCount = 1;
    swapchainInfo.arraySize = 1;
    swapchainInfo.mipCount = 1;
    result = api.createSwapchain(session, &swapchainInfo, &swapchain);
    if (result != XR_SUCCESS || swapchain == XR_NULL_HANDLE) {
        return fail("xrCreateSwapchain / GLES texture allocation", result);
    }

    std::uint32_t imageCount = 0;
    result = api.enumerateSwapchainImages(swapchain, 0, &imageCount, nullptr);
    if (result != XR_SUCCESS || imageCount != 3) {
        return fail("enumerate triple-buffered swapchain", result == XR_SUCCESS
            ? XR_ERROR_RUNTIME_FAILURE : result);
    }
    std::vector<XrSwapchainImageOpenGLESKHR> images(imageCount);
    for (auto& image : images) {
        image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
        image.next = nullptr;
    }
    result = api.enumerateSwapchainImages(
        swapchain, imageCount, &imageCount,
        reinterpret_cast<XrSwapchainImageBaseHeader*>(images.data()));
    if (result != XR_SUCCESS) return fail("read GLES swapchain images", result);
    if (std::any_of(images.begin(), images.end(),
                    [](const XrSwapchainImageOpenGLESKHR& image) {
                        return image.image == 0;
                    })) {
        return fail("GLES swapchain texture handles", XR_ERROR_RUNTIME_FAILURE);
    }

    XrSessionBeginInfo beginSessionInfo{};
    beginSessionInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginSessionInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    result = api.beginSession(session, &beginSessionInfo);
    if (result != XR_SUCCESS) return fail("xrBeginSession", result);
    sessionRunning = true;

    XrFrameWaitInfo frameWaitInfo{};
    frameWaitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
    XrFrameBeginInfo frameBeginInfo{};
    frameBeginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    XrViewLocateInfo locateInfo{};
    locateInfo.type = XR_TYPE_VIEW_LOCATE_INFO;
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.space = space;

    XrSpaceLocation spaceLocation{};
    spaceLocation.type = XR_TYPE_SPACE_LOCATION;
    std::array<XrView, 2> locatedViews{};
    for (auto& view : locatedViews) view.type = XR_TYPE_VIEW;

    std::array<XrCompositionLayerProjectionView, 2> projectionViews{};
    const std::uint32_t eyeImageWidth = swapchainInfo.width / 2;
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        auto& projectionView = projectionViews[eye];
        projectionView.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        projectionView.subImage.swapchain = swapchain;
        projectionView.subImage.imageArrayIndex = 0;
        projectionView.subImage.imageRect.offset.x =
            eye == 0 ? 0 : static_cast<std::int32_t>(eyeImageWidth);
        projectionView.subImage.imageRect.offset.y = 0;
        projectionView.subImage.imageRect.extent.width =
            static_cast<std::int32_t>(eye == 0
                ? eyeImageWidth : swapchainInfo.width - eyeImageWidth);
        projectionView.subImage.imageRect.extent.height =
            static_cast<std::int32_t>(swapchainInfo.height);
    }

    XrCompositionLayerProjection projectionLayer{};
    projectionLayer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projectionLayer.space = space;
    projectionLayer.viewCount = static_cast<std::uint32_t>(projectionViews.size());
    projectionLayer.views = projectionViews.data();
    const XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projectionLayer)
    };
    XrFrameEndInfo frameEndInfo{};
    frameEndInfo.type = XR_TYPE_FRAME_END_INFO;
    frameEndInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    frameEndInfo.layerCount = 1;
    frameEndInfo.layers = layers;

    XrSwapchainImageAcquireInfo acquireInfo{};
    acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
    XrSwapchainImageWaitInfo imageWaitInfo{};
    imageWaitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    imageWaitInfo.timeout = XR_INFINITE_DURATION;
    XrSwapchainImageReleaseInfo releaseInfo{};
    releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;

    constexpr std::uint32_t kProbeFrameCount = 30;
    std::array<bool, 3> seenSwapchainImages{};
    std::uint32_t submittedFrameCount = 0;
    std::array<GLubyte, 4> leftPixel{};
    std::array<GLubyte, 4> rightPixel{};

    for (std::uint32_t frameIndex = 0; frameIndex < kProbeFrameCount; ++frameIndex) {
        frameState = XrFrameState{};
        frameState.type = XR_TYPE_FRAME_STATE;
        result = api.waitFrame(session, &frameWaitInfo, &frameState);
        if (result != XR_SUCCESS) return fail("xrWaitFrame", result);
        frameWaited = true;

        result = api.beginFrame(session, &frameBeginInfo);
        if (result != XR_SUCCESS) return fail("xrBeginFrame", result);
        frameBegun = true;

        locateInfo.displayTime = frameState.predictedDisplayTime;
        XrViewState viewState{};
        viewState.type = XR_TYPE_VIEW_STATE;
        std::uint32_t locatedViewCount = 0;
        result = api.locateViews(session, &locateInfo, &viewState,
                                 static_cast<std::uint32_t>(locatedViews.size()),
                                 &locatedViewCount, locatedViews.data());
        if (result != XR_SUCCESS || locatedViewCount != 2) {
            return fail("xrLocateViews (stereo pose)", result == XR_SUCCESS
                ? XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED : result);
        }

        result = api.locateSpace(space, space, frameState.predictedDisplayTime,
                                 &spaceLocation);
        if (result != XR_SUCCESS) return fail("xrLocateSpace", result);

        result = api.acquireSwapchainImage(swapchain, &acquireInfo, &acquiredImageIndex);
        if (result != XR_SUCCESS) return fail("xrAcquireSwapchainImage", result);
        imageAcquired = true;

        result = api.waitSwapchainImage(swapchain, &imageWaitInfo);
        if (result != XR_SUCCESS) return fail("xrWaitSwapchainImage", result);
        imageWaited = true;
        if (acquiredImageIndex >= images.size() ||
            acquiredImageIndex >= seenSwapchainImages.size()) {
            return fail("acquired GLES swapchain image index", XR_ERROR_RUNTIME_FAILURE);
        }
        seenSwapchainImages[acquiredImageIndex] = true;

        if (!paintStereoDiagnosticPattern(
                images[acquiredImageIndex].image,
                static_cast<GLsizei>(swapchainInfo.width),
                static_cast<GLsizei>(swapchainInfo.height))) {
            return fail("render stereo pattern into swapchain image", XR_ERROR_RUNTIME_FAILURE);
        }
        glFlush();

        result = api.releaseSwapchainImage(swapchain, &releaseInfo);
        if (result != XR_SUCCESS) return fail("xrReleaseSwapchainImage", result);
        imageAcquired = false;
        imageWaited = false;

        for (std::uint32_t eye = 0; eye < 2; ++eye) {
            projectionViews[eye].pose = locatedViews[eye].pose;
            projectionViews[eye].fov = locatedViews[eye].fov;
        }
        frameEndInfo.displayTime = frameState.predictedDisplayTime;
        result = api.endFrame(session, &frameEndInfo);
        if (result != XR_SUCCESS) {
            // The runtime closes its frame state when composition reports an error.
            frameBegun = false;
            frameWaited = false;
            return fail("xrEndFrame (projection layer)", result);
        }
        frameBegun = false;
        frameWaited = false;

        glFinish();
        glReadPixels(surfaceWidth / 4, surfaceHeight / 2, 1, 1,
                     GL_RGBA, GL_UNSIGNED_BYTE, leftPixel.data());
        glReadPixels((surfaceWidth * 3) / 4, surfaceHeight / 2, 1, 1,
                     GL_RGBA, GL_UNSIGNED_BYTE, rightPixel.data());
        const GLenum readbackError = glGetError();
        if (readbackError != GL_NO_ERROR) {
            return fail("projection layer pixel readback", XR_ERROR_RUNTIME_FAILURE);
        }
        const bool leftEyeIsRed = leftPixel[0] > 160 &&
                                  leftPixel[1] < 90 && leftPixel[2] < 90;
        const bool rightEyeIsBlue = rightPixel[2] > 160 &&
                                    rightPixel[0] < 90 && rightPixel[1] < 110;
        if (!leftEyeIsRed || !rightEyeIsBlue) {
            char pixels[160]{};
            std::snprintf(pixels, sizeof(pixels),
                          "projection layer pixel verification frame=%u left=%u,%u,%u right=%u,%u,%u",
                          frameIndex + 1, leftPixel[0], leftPixel[1], leftPixel[2],
                          rightPixel[0], rightPixel[1], rightPixel[2]);
            return fail(pixels, XR_ERROR_RUNTIME_FAILURE);
        }
        ++submittedFrameCount;
    }

    const std::size_t distinctSwapchainImages = static_cast<std::size_t>(
        std::count(seenSwapchainImages.begin(), seenSwapchainImages.end(), true));
    if (submittedFrameCount != kProbeFrameCount || distinctSwapchainImages != 3) {
        return fail("30-frame lifecycle / triple-buffer rotation",
                    XR_ERROR_RUNTIME_FAILURE);
    }

    const std::string cleanupResult = cleanup();
    if (!cleanupResult.empty()) {
        return "OpenXR probe: FAIL · teardown · " + cleanupResult;
    }

    std::ostringstream success;
    success << "OpenXR probe: PASS · runtime negotiation · GLES session · 2 stereo views · "
            << "3 swapchain images · acquire/wait/release · locate pose · projection layer "
            << "composition + pixel readback · teardown\n";
    success << "projection_layer_left_pixel_rgba="
            << static_cast<unsigned int>(leftPixel[0]) << ","
            << static_cast<unsigned int>(leftPixel[1]) << ","
            << static_cast<unsigned int>(leftPixel[2]) << ","
            << static_cast<unsigned int>(leftPixel[3]) << '\n';
    success << "projection_layer_frames_submitted=30\n";
    success << "projection_layer_swapchain_images_seen="
            << std::count(seenSwapchainImages.begin(), seenSwapchainImages.end(), true) << '\n';
    success << "projection_layer_right_pixel_rgba="
            << static_cast<unsigned int>(rightPixel[0]) << ","
            << static_cast<unsigned int>(rightPixel[1]) << ","
            << static_cast<unsigned int>(rightPixel[2]) << ","
            << static_cast<unsigned int>(rightPixel[3]);
    return success.str();
}


namespace {
std::string endxrDiagnosticResultName(std::uint32_t code) {
    switch (static_cast<XrResult>(code)) {
        case XR_SUCCESS: return "XR_SUCCESS";
        case XR_ERROR_VALIDATION_FAILURE: return "XR_ERROR_VALIDATION_FAILURE";
        case XR_ERROR_RUNTIME_FAILURE: return "XR_ERROR_RUNTIME_FAILURE";
        case XR_ERROR_OUT_OF_MEMORY: return "XR_ERROR_OUT_OF_MEMORY";
        case XR_ERROR_INITIALIZATION_FAILED: return "XR_ERROR_INITIALIZATION_FAILED";
        case XR_ERROR_FUNCTION_UNSUPPORTED: return "XR_ERROR_FUNCTION_UNSUPPORTED";
        case XR_ERROR_EXTENSION_NOT_PRESENT: return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case XR_ERROR_FORM_FACTOR_UNSUPPORTED: return "XR_ERROR_FORM_FACTOR_UNSUPPORTED";
        case XR_ERROR_SYSTEM_INVALID: return "XR_ERROR_SYSTEM_INVALID";
        case XR_ERROR_HANDLE_INVALID: return "XR_ERROR_HANDLE_INVALID";
        case XR_ERROR_SESSION_NOT_READY: return "XR_ERROR_SESSION_NOT_READY";
        case XR_ERROR_SESSION_NOT_RUNNING: return "XR_ERROR_SESSION_NOT_RUNNING";
        case XR_ERROR_CALL_ORDER_INVALID: return "XR_ERROR_CALL_ORDER_INVALID";
        case XR_ERROR_GRAPHICS_DEVICE_INVALID: return "XR_ERROR_GRAPHICS_DEVICE_INVALID";
        case XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING: return "XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING";
        case XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED: return "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED";
        case XR_ERROR_SIZE_INSUFFICIENT: return "XR_ERROR_SIZE_INSUFFICIENT";
        default: return "XR_RESULT_UNKNOWN";
    }
}
std::string endxrDiagnosticGlString(GLenum key) {
    const GLubyte* value = glGetString(key);
    return value == nullptr ? "unavailable" : reinterpret_cast<const char*>(value);
}
} // namespace

std::string runEndXrOpenXrAndroidSelfTest(JavaVM* vm, jobject activity) {
    const EGLDisplay display = eglGetCurrentDisplay();
    const EGLContext context = eglGetCurrentContext();
    const EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    std::ostringstream out;
    const std::string nativeResult = runEndXrOpenXrAndroidSelfTestImpl(vm, activity);
    out << nativeResult << '\n';
    out << "diagnostic_schema=4\n";
    out << "probe_scope=direct bundled runtime call on Sandbox GLSurfaceView EGL context\n";
    out << "runtime_broker_discovery=NOT_TESTED\n";
    out << "third_party_game_process=NOT_TESTED\n";
    const bool probePassed = nativeResult.rfind("OpenXR probe: PASS", 0) == 0;
    const bool projectionLayerAttempted =
        nativeResult.find("projection layer") != std::string::npos;
    out << "projection_layer_composition="
        << (probePassed ? "PASS (30 consecutive two-eye frames submitted and read back)"
            : projectionLayerAttempted ? "FAIL" : "NOT_REACHED") << '\n';
    out << "android_vm=" << (vm == nullptr ? "missing" : "present") << '\n';
    out << "android_activity=" << (activity == nullptr ? "missing" : "present") << '\n';
    out << "egl_display=" << (display == EGL_NO_DISPLAY ? "missing" : "present") << '\n';
    out << "egl_context=" << (context == EGL_NO_CONTEXT ? "missing" : "present") << '\n';
    out << "egl_draw_surface=" << (surface == EGL_NO_SURFACE ? "missing" : "present") << '\n';

    if (display != EGL_NO_DISPLAY) {
        const char* vendor = eglQueryString(display, EGL_VENDOR);
        const char* version = eglQueryString(display, EGL_VERSION);
        const char* extensions = eglQueryString(display, EGL_EXTENSIONS);
        out << "egl_vendor=" << (vendor == nullptr ? "unavailable" : vendor) << '\n';
        out << "egl_version=" << (version == nullptr ? "unavailable" : version) << '\n';
        out << "egl_extensions=" << (extensions == nullptr ? "unavailable" : std::string(extensions).substr(0, 1800)) << '\n';
    }
    if (display != EGL_NO_DISPLAY && surface != EGL_NO_SURFACE) {
        EGLint width = 0;
        EGLint height = 0;
        if (eglQuerySurface(display, surface, EGL_WIDTH, &width) == EGL_TRUE)
            out << "egl_surface_width=" << width << '\n';
        else out << "egl_surface_width_error=" << formatResult(static_cast<XrResult>(eglGetError())) << '\n';
        if (eglQuerySurface(display, surface, EGL_HEIGHT, &height) == EGL_TRUE)
            out << "egl_surface_height=" << height << '\n';
        else out << "egl_surface_height_error=" << formatResult(static_cast<XrResult>(eglGetError())) << '\n';
    }
    if (display != EGL_NO_DISPLAY && context != EGL_NO_CONTEXT) {
        EGLint configId = 0;
        EGLint clientVersion = 0;
        out << "egl_config_id_result=" << (eglQueryContext(display, context, EGL_CONFIG_ID, &configId) == EGL_TRUE ? "ok" : "fail") << '\n';
        out << "egl_config_id=" << configId << '\n';
        out << "egl_context_client_version_result=" << (eglQueryContext(display, context, EGL_CONTEXT_CLIENT_VERSION, &clientVersion) == EGL_TRUE ? "ok" : "fail") << '\n';
        out << "egl_context_client_version=" << clientVersion << '\n';
        GLint major = 0;
        GLint minor = 0;
        GLint maxTexture = 0;
        GLint maxRenderbuffer = 0;
        glGetIntegerv(GL_MAJOR_VERSION, &major);
        glGetIntegerv(GL_MINOR_VERSION, &minor);
        glGetIntegerv(GL_MAX_TEXTURE_SIZE, &maxTexture);
        glGetIntegerv(GL_MAX_RENDERBUFFER_SIZE, &maxRenderbuffer);
        out << "gl_vendor=" << endxrDiagnosticGlString(GL_VENDOR) << '\n';
        out << "gl_renderer=" << endxrDiagnosticGlString(GL_RENDERER) << '\n';
        out << "gl_version=" << endxrDiagnosticGlString(GL_VERSION) << '\n';
        out << "glsl_version=" << endxrDiagnosticGlString(GL_SHADING_LANGUAGE_VERSION) << '\n';
        out << "gl_context_major=" << major << '\n';
        out << "gl_context_minor=" << minor << '\n';
        out << "gl_max_texture_size=" << maxTexture << '\n';
        out << "gl_max_renderbuffer_size=" << maxRenderbuffer << '\n';
        out << "gl_error_after_probe=" << formatResult(static_cast<XrResult>(glGetError())) << '\n';
    }
    out << "gles_last_texture_allocation_error=" << formatResult(static_cast<XrResult>(endxrGetLastGlesError())) << '\n';

    const std::string failPrefix = "OpenXR probe: FAIL · ";
    if (nativeResult.rfind(failPrefix, 0) == 0) {
        const std::string detail = nativeResult.substr(failPrefix.size());
        const std::size_t separator = detail.find(" · ");
        const std::string stage = separator == std::string::npos ? detail : detail.substr(0, separator);
        out << "failure_stage=" << stage << '\n';
        out << "failure_api_call=" << stage << '\n';
        const std::size_t codePosition = detail.find("0x");
        if (codePosition != std::string::npos) {
            const std::uint32_t code = static_cast<std::uint32_t>(
                std::strtoul(detail.c_str() + codePosition, nullptr, 16));
            out << "failure_result_hex=" << formatResult(static_cast<XrResult>(code)) << '\n';
            out << "failure_result_name=" << endxrDiagnosticResultName(code) << '\n';
        } else {
            out << "failure_result_name=not_provided_by_early_bootstrap\n";
        }
        const std::size_t cleanupPosition = detail.find("cleanup:");
        if (cleanupPosition != std::string::npos)
            out << "failure_cleanup_detail=" << detail.substr(cleanupPosition + 8) << '\n';
    } else {
        out << "check_1=runtime negotiation | PASS\n";
        out << "check_2=OpenXR instance + system | PASS\n";
        out << "check_3=GLES session + stereo view configuration | PASS\n";
        out << "check_4=triple-buffered swapchain + acquire/wait/release | PASS\n";
        out << "check_5=space pose + frame lifecycle | PASS\n";
        out << "check_6=resource teardown | PASS\n";
        out << "check_7=stereo projection layer composition + GLES backbuffer readback | PASS\n";
        out << "check_8=30 consecutive OpenXR frames + triple-buffer rotation | PASS\n";
    }
    return out.str();
}
