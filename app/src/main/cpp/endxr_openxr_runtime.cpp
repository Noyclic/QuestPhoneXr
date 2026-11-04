
#include "endxr_openxr_runtime.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <limits>
#include <mutex>
#include <new>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#if defined(__ANDROID__)
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#else
using EGLDisplay = void*;
using EGLConfig = void*;
using EGLContext = void*;
using EGLSurface = void*;
using EGLint = int;
using GLuint = std::uint32_t;
using GLenum = std::uint32_t;
using GLint = int;
using GLsizei = int;
using GLbitfield = std::uint32_t;
using GLboolean = unsigned char;
constexpr EGLint EGL_DRAW = 0x3059;
constexpr EGLint EGL_WIDTH = 0x3057;
constexpr EGLint EGL_HEIGHT = 0x3056;
constexpr EGLint EGL_FALSE = 0;
constexpr GLenum GL_TEXTURE_2D = 0x0DE1;
constexpr GLenum GL_RGBA8 = 0x8058;
constexpr GLenum GL_RGBA = 0x1908;
constexpr GLenum GL_UNSIGNED_BYTE = 0x1401;
constexpr GLenum GL_TEXTURE_MIN_FILTER = 0x2801;
constexpr GLenum GL_TEXTURE_MAG_FILTER = 0x2800;
constexpr GLenum GL_LINEAR = 0x2601;
constexpr GLenum GL_TEXTURE_WRAP_S = 0x2802;
constexpr GLenum GL_TEXTURE_WRAP_T = 0x2803;
constexpr GLenum GL_CLAMP_TO_EDGE = 0x812F;
constexpr GLenum GL_FRAMEBUFFER = 0x8D40;
constexpr GLenum GL_READ_FRAMEBUFFER = 0x8CA8;
constexpr GLenum GL_DRAW_FRAMEBUFFER = 0x8CA9;
constexpr GLenum GL_READ_FRAMEBUFFER_BINDING = 0x8CAA;
constexpr GLenum GL_DRAW_FRAMEBUFFER_BINDING = 0x8CA6;
constexpr GLenum GL_COLOR_ATTACHMENT0 = 0x8CE0;
constexpr GLenum GL_FRAMEBUFFER_COMPLETE = 0x8CD5;
constexpr GLenum GL_SCISSOR_TEST = 0x0C11;
constexpr GLenum GL_COLOR_CLEAR_VALUE = 0x0C22;
constexpr GLenum GL_COLOR_WRITEMASK = 0x0C23;
constexpr GLenum GL_COLOR_BUFFER_BIT = 0x00004000;
constexpr GLenum GL_NO_ERROR = 0;
constexpr GLenum GL_TEXTURE = 0x1702;
constexpr GLenum GL_VIEWPORT = 0x0BA2;
#endif

struct XrInstance_T {
    XrVersion requestedApiVersion = XR_MAKE_VERSION(1, 0, 0);
    void* applicationVm = nullptr;
    void* applicationActivity = nullptr;
    bool androidExtensionEnabled = false;
    bool glesExtensionEnabled = false;
    bool glesRequirementsQueried = false;
    std::mutex eventMutex;
    std::deque<XrEventDataSessionStateChanged> pendingSessionEvents;
};

struct XrSession_T {
    XrInstance owner = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    EGLDisplay display = nullptr;
    EGLConfig config = nullptr;
    EGLContext context = nullptr;
    XrSessionState state = XR_SESSION_STATE_IDLE;
    bool running = false;
    bool exitRequested = false;
    bool frameWaited = false;
    bool frameBegun = false;
    bool hasPredictedTime = false;
    XrTime predictedDisplayTime = 0;
    XrDuration predictedDisplayPeriod = 0;
    std::unordered_set<XrSwapchain> swapchains;
    std::unordered_set<XrSpace> spaces;
    GLuint compositorFramebuffer = 0;
};

struct XrSwapchain_T {
    XrSession owner = XR_NULL_HANDLE;
    std::uint32_t width = 0;
    std::uint32_t height = 0;
    std::uint32_t imageCount = 3;
    std::array<GLuint, 3> images{};
    std::uint32_t nextAcquire = 0;
    std::uint32_t acquiredIndex = std::numeric_limits<std::uint32_t>::max();
    std::uint32_t lastReleasedIndex = std::numeric_limits<std::uint32_t>::max();
    bool imageWaited = false;
};

struct XrSpace_T {
    XrSession owner = XR_NULL_HANDLE;
    XrReferenceSpaceType type = XR_REFERENCE_SPACE_TYPE_LOCAL;
    XrPosef poseInReferenceSpace{};
};

namespace {
constexpr XrSystemId kEndXrSystemId = 1;
constexpr std::uint32_t kRecommendedEyeWidth = 960;
constexpr std::uint32_t kRecommendedEyeHeight = 1080;
constexpr std::uint32_t kMaximumEyeWidth = 1200;
constexpr std::uint32_t kMaximumEyeHeight = 1200;
constexpr std::uint32_t kEndXrVendorId = 0x454E4458; // "ENDX"
constexpr XrVersion kEndXrApiVersion = XR_MAKE_VERSION(1, 0, 0);
constexpr XrDuration kFramePeriod = 33333333;
constexpr float kHalfIpdMeters = 0.032f;
constexpr const char* kRuntimeName = "EndXR Phone Runtime";
constexpr const char* kSystemName = "EndXR Phone VR";

std::mutex g_instanceMutex;
std::unordered_map<XrInstance, XrInstance_T*> g_instances;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextInstanceHandle = 1;
#endif

std::mutex g_sessionMutex;
std::unordered_map<XrSession, XrSession_T*> g_sessions;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSessionHandle = 1;
#endif

std::mutex g_swapchainMutex;
std::unordered_map<XrSwapchain, XrSwapchain_T*> g_swapchains;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSwapchainHandle = 1;
#endif

std::mutex g_spaceMutex;
std::unordered_map<XrSpace, XrSpace_T*> g_spaces;
#if XR_PTR_SIZE != 8
std::uint64_t g_nextSpaceHandle = 1;
#endif

std::mutex g_headPoseMutex;
XrQuaternionf g_headOrientation{0.0f, 0.0f, 0.0f, 1.0f};
bool g_hasHeadOrientation = false;

XrInstance makeInstanceHandle(XrInstance_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrInstance>(object);
#else
    (void)object;
    return g_nextInstanceHandle++;
#endif
}
XrSession makeSessionHandle(XrSession_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSession>(object);
#else
    (void)object;
    return g_nextSessionHandle++;
#endif
}
XrSwapchain makeSwapchainHandle(XrSwapchain_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSwapchain>(object);
#else
    (void)object;
    return g_nextSwapchainHandle++;
#endif
}
XrSpace makeSpaceHandle(XrSpace_T* object) {
#if XR_PTR_SIZE == 8
    return reinterpret_cast<XrSpace>(object);
#else
    (void)object;
    return g_nextSpaceHandle++;
#endif
}

struct BaseInStructure {
    XrStructureType type;
    const void* next;
};

constexpr const char* kAndroidExtension =
    ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
constexpr const char* kGlesExtension =
    ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME;

bool isValidInstance(XrInstance instance) {
    if (instance == XR_NULL_HANDLE) return false;
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    return g_instances.find(instance) != g_instances.end();
}
XrInstance_T* getInstance(XrInstance instance) {
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    const auto found = g_instances.find(instance);
    return found == g_instances.end() ? nullptr : found->second;
}
XrSession_T* getSession(XrSession session) {
    std::lock_guard<std::mutex> lock(g_sessionMutex);
    const auto found = g_sessions.find(session);
    return found == g_sessions.end() ? nullptr : found->second;
}
XrSwapchain_T* getSwapchain(XrSwapchain swapchain) {
    std::lock_guard<std::mutex> lock(g_swapchainMutex);
    const auto found = g_swapchains.find(swapchain);
    return found == g_swapchains.end() ? nullptr : found->second;
}
XrSpace_T* getSpace(XrSpace space) {
    std::lock_guard<std::mutex> lock(g_spaceMutex);
    const auto found = g_spaces.find(space);
    return found == g_spaces.end() ? nullptr : found->second;
}
const XrInstanceCreateInfoAndroidKHR* findAndroidCreateInfo(const void* next) {
    auto* current = static_cast<const BaseInStructure*>(next);
    while (current != nullptr) {
        if (current->type == XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR) {
            return reinterpret_cast<const XrInstanceCreateInfoAndroidKHR*>(current);
        }
        current = static_cast<const BaseInStructure*>(current->next);
    }
    return nullptr;
}
const XrGraphicsBindingOpenGLESAndroidKHR* findGlesBinding(const void* next) {
    auto* current = static_cast<const BaseInStructure*>(next);
    while (current != nullptr) {
        if (current->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) {
            return reinterpret_cast<const XrGraphicsBindingOpenGLESAndroidKHR*>(current);
        }
        current = static_cast<const BaseInStructure*>(current->next);
    }
    return nullptr;
}
XrTime monotonicTimeNow() {
    return static_cast<XrTime>(std::chrono::duration_cast<std::chrono::nanoseconds>(
        std::chrono::steady_clock::now().time_since_epoch()).count());
}
void queueSessionState(XrSession_T* session, XrSessionState state) {
    session->state = state;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return;
    XrEventDataSessionStateChanged event{};
    event.type = XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED;
    event.session = reinterpret_cast<XrSession>(session);
    event.state = state;
    event.time = monotonicTimeNow();
#if XR_PTR_SIZE != 8
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second == session) {
                event.session = entry.first;
                break;
            }
        }
    }
#endif
    std::lock_guard<std::mutex> lock(instance->eventMutex);
    instance->pendingSessionEvents.push_back(event);
}
bool graphicsContextIsCurrent(const XrSession_T* session) {
#if defined(__ANDROID__)
    return session != nullptr &&
        eglGetCurrentDisplay() == session->display &&
        eglGetCurrentContext() == session->context;
#else
    return session != nullptr && session->display != nullptr &&
        session->config != nullptr && session->context != nullptr;
#endif
}
bool createGlesTexture(std::uint32_t width, std::uint32_t height, GLuint* output) {
    if (output == nullptr || width == 0 || height == 0) return false;
#if defined(__ANDROID__)
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (texture == 0) return false;
    glBindTexture(GL_TEXTURE_2D, texture);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, static_cast<GLsizei>(width),
                   static_cast<GLsizei>(height));
    if (glGetError() != GL_NO_ERROR) {
        glDeleteTextures(1, &texture);
        return false;
    }
    *output = texture;
#else
    static std::uint32_t nextTexture = 1;
    *output = nextTexture++;
#endif
    return true;
}
void destroyGlesTexture(GLuint texture) {
    if (texture == 0) return;
#if defined(__ANDROID__)
    glDeleteTextures(1, &texture);
#else
    (void)texture;
#endif
}
void destroySessionFramebuffer(XrSession_T* session) {
    if (session == nullptr || session->compositorFramebuffer == 0) return;
#if defined(__ANDROID__)
    glDeleteFramebuffers(1, &session->compositorFramebuffer);
#endif
    session->compositorFramebuffer = 0;
}
bool compositeProjectionLayer(XrSession_T* session,
                              const XrCompositionLayerProjection* projection,
                              XrResult* error) {
    if (projection == nullptr || projection->viewCount != 2 ||
        projection->views == nullptr) {
        if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
        return false;
    }
    std::array<XrSwapchain_T*, 2> swapchains{};
    std::array<std::uint32_t, 2> imageIndices{};
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        const auto& view = projection->views[eye];
        if (view.type != XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW ||
            view.subImage.swapchain == XR_NULL_HANDLE ||
            view.subImage.imageArrayIndex != 0 ||
            view.subImage.imageRect.offset.x < 0 ||
            view.subImage.imageRect.offset.y < 0 ||
            view.subImage.imageRect.extent.width <= 0 ||
            view.subImage.imageRect.extent.height <= 0) {
            if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
            return false;
        }
        XrSwapchain_T* swapchain = getSwapchain(view.subImage.swapchain);
        if (swapchain == nullptr || swapchain->owner != reinterpret_cast<XrSession>(session) ||
            swapchain->lastReleasedIndex >= swapchain->imageCount) {
#if XR_PTR_SIZE != 8
            XrSession ownerHandle = XR_NULL_HANDLE;
            {
                std::lock_guard<std::mutex> lock(g_sessionMutex);
                for (const auto& entry : g_sessions) {
                    if (entry.second == session) ownerHandle = entry.first;
                }
            }
            if (swapchain == nullptr || swapchain->owner != ownerHandle ||
                swapchain->lastReleasedIndex >= swapchain->imageCount) {
                if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
                return false;
            }
#else
            if (error != nullptr) *error = XR_ERROR_LAYER_INVALID;
            return false;
#endif
        }
        const auto& rect = view.subImage.imageRect;
        if (static_cast<std::uint32_t>(rect.offset.x) + 
                static_cast<std::uint32_t>(rect.extent.width) > swapchain->width ||
            static_cast<std::uint32_t>(rect.offset.y) +
                static_cast<std::uint32_t>(rect.extent.height) > swapchain->height) {
            if (error != nullptr) *error = XR_ERROR_SWAPCHAIN_RECT_INVALID;
            return false;
        }
        if (swapchain->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
            if (error != nullptr) *error = XR_ERROR_CALL_ORDER_INVALID;
            return false;
        }
        swapchains[eye] = swapchain;
        imageIndices[eye] = swapchain->lastReleasedIndex;
    }

#if defined(__ANDROID__)
    if (!graphicsContextIsCurrent(session)) {
        if (error != nullptr) *error = XR_ERROR_GRAPHICS_DEVICE_INVALID;
        return false;
    }
    EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
    EGLint screenWidth = 0;
    EGLint screenHeight = 0;
    if (surface == EGL_NO_SURFACE ||
        eglQuerySurface(session->display, surface, EGL_WIDTH, &screenWidth) != EGL_TRUE ||
        eglQuerySurface(session->display, surface, EGL_HEIGHT, &screenHeight) != EGL_TRUE ||
        screenWidth < 2 || screenHeight < 1) {
        if (error != nullptr) *error = XR_ERROR_GRAPHICS_DEVICE_INVALID;
        return false;
    }

    GLint previousReadFramebuffer = 0;
    GLint previousDrawFramebuffer = 0;
    GLint previousScissorEnabled = glIsEnabled(GL_SCISSOR_TEST);
    GLfloat previousClearColor[4]{};
    GLboolean previousColorMask[4]{};
    glGetIntegerv(GL_READ_FRAMEBUFFER_BINDING, &previousReadFramebuffer);
    glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &previousDrawFramebuffer);
    glGetFloatv(GL_COLOR_CLEAR_VALUE, previousClearColor);
    glGetBooleanv(GL_COLOR_WRITEMASK, previousColorMask);

    if (session->compositorFramebuffer == 0) {
        glGenFramebuffers(1, &session->compositorFramebuffer);
    }
    if (session->compositorFramebuffer == 0) {
        if (error != nullptr) *error = XR_ERROR_RUNTIME_FAILURE;
        return false;
    }

    glDisable(GL_SCISSOR_TEST);
    glColorMask(GL_TRUE, GL_TRUE, GL_TRUE, GL_TRUE);
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
    glClear(GL_COLOR_BUFFER_BIT);

    bool success = true;
    const GLint halfWidth = screenWidth / 2;
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        const auto& rect = projection->views[eye].subImage.imageRect;
        glBindFramebuffer(GL_READ_FRAMEBUFFER, session->compositorFramebuffer);
        glFramebufferTexture2D(GL_READ_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                               GL_TEXTURE_2D, swapchains[eye]->images[imageIndices[eye]], 0);
        if (glCheckFramebufferStatus(GL_READ_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            success = false;
            break;
        }
        const GLint destinationLeft = eye == 0 ? 0 : halfWidth;
        const GLint destinationRight = eye == 0 ? halfWidth : screenWidth;
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
        glBlitFramebuffer(
            rect.offset.x,
            rect.offset.y,
            rect.offset.x + rect.extent.width,
            rect.offset.y + rect.extent.height,
            destinationLeft,
            0,
            destinationRight,
            screenHeight,
            GL_COLOR_BUFFER_BIT,
            GL_LINEAR
        );
        if (glGetError() != GL_NO_ERROR) {
            success = false;
            break;
        }
    }

    glBindFramebuffer(GL_READ_FRAMEBUFFER, static_cast<GLuint>(previousReadFramebuffer));
    glBindFramebuffer(GL_DRAW_FRAMEBUFFER, static_cast<GLuint>(previousDrawFramebuffer));
    glColorMask(previousColorMask[0], previousColorMask[1],
                previousColorMask[2], previousColorMask[3]);
    glClearColor(previousClearColor[0], previousClearColor[1],
                 previousClearColor[2], previousClearColor[3]);
    if (previousScissorEnabled == GL_TRUE) glEnable(GL_SCISSOR_TEST);
    else glDisable(GL_SCISSOR_TEST);

    if (!success) {
        if (error != nullptr) *error = XR_ERROR_RUNTIME_FAILURE;
        return false;
    }
#else
    (void)swapchains;
    (void)imageIndices;
#endif
    if (error != nullptr) *error = XR_SUCCESS;
    return true;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
);

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateApiLayerProperties(
    std::uint32_t capacity, std::uint32_t* count, XrApiLayerProperties*
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 0;
    (void)capacity;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateInstanceExtensionProperties(
    const char* layerName, std::uint32_t capacity,
    std::uint32_t* count, XrExtensionProperties* properties
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (layerName != nullptr && layerName[0] != '\0') {
        return XR_ERROR_API_LAYER_NOT_PRESENT;
    }
    constexpr std::array<const char*, 2> names{{kAndroidExtension, kGlesExtension}};
    constexpr std::array<std::uint32_t, 2> versions{{
        ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION,
        ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION
    }};
    *count = static_cast<std::uint32_t>(names.size());
    if (capacity == 0) return XR_SUCCESS;
    if (properties == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fillCount = std::min(capacity, *count);
    for (std::uint32_t i = 0; i < fillCount; ++i) {
        if (properties[i].type != XR_TYPE_EXTENSION_PROPERTIES) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        std::snprintf(properties[i].extensionName,
                      sizeof(properties[i].extensionName), "%s", names[i]);
        properties[i].extensionVersion = versions[i];
    }
    return capacity < *count ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateInstance(
    const XrInstanceCreateInfo* createInfo, XrInstance* instance
) {
    if (createInfo == nullptr || instance == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *instance = XR_NULL_HANDLE;
    if (createInfo->type != XR_TYPE_INSTANCE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (createInfo->enabledApiLayerCount != 0) return XR_ERROR_API_LAYER_NOT_PRESENT;
    if (createInfo->enabledExtensionCount > 2) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (createInfo->enabledExtensionCount > 0 && createInfo->enabledExtensionNames == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    bool androidEnabled = false;
    bool glesEnabled = false;
    for (std::uint32_t i = 0; i < createInfo->enabledExtensionCount; ++i) {
        const char* name = createInfo->enabledExtensionNames[i];
        if (name == nullptr) return XR_ERROR_VALIDATION_FAILURE;
        if (std::strcmp(name, kAndroidExtension) == 0) {
            if (androidEnabled) return XR_ERROR_NAME_DUPLICATED;
            androidEnabled = true;
        } else if (std::strcmp(name, kGlesExtension) == 0) {
            if (glesEnabled) return XR_ERROR_NAME_DUPLICATED;
            glesEnabled = true;
        } else {
            return XR_ERROR_EXTENSION_NOT_PRESENT;
        }
    }

    const XrInstanceCreateInfoAndroidKHR* androidInfo = nullptr;
    if (androidEnabled) {
        androidInfo = findAndroidCreateInfo(createInfo->next);
        if (androidInfo == nullptr ||
            androidInfo->applicationVM == nullptr ||
            androidInfo->applicationActivity == nullptr) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
    } else if (findAndroidCreateInfo(createInfo->next) != nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    const XrVersion requested = createInfo->applicationInfo.apiVersion;
    if (XR_VERSION_MAJOR(requested) != 1 || XR_VERSION_MINOR(requested) > 0) {
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }

    XrInstance_T* created = new (std::nothrow) XrInstance_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->requestedApiVersion = requested;
    created->androidExtensionEnabled = androidEnabled;
    created->glesExtensionEnabled = glesEnabled;
    if (androidInfo != nullptr) {
        created->applicationVm = androidInfo->applicationVM;
        created->applicationActivity = androidInfo->applicationActivity;
    }
    const XrInstance createdHandle = makeInstanceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_instanceMutex);
        g_instances.emplace(createdHandle, created);
    }
    *instance = createdHandle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyInstance(XrInstance instance) {
    XrInstance_T* object = nullptr;
    {
        std::lock_guard<std::mutex> sessionLock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second->owner == instance) return XR_ERROR_CALL_ORDER_INVALID;
        }
    }
    {
        std::lock_guard<std::mutex> lock(g_instanceMutex);
        const auto found = g_instances.find(instance);
        if (found == g_instances.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        g_instances.erase(found);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProperties(
    XrInstance instance, XrInstanceProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (properties == nullptr || properties->type != XR_TYPE_INSTANCE_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->runtimeVersion = kEndXrApiVersion;
    std::snprintf(properties->runtimeName, sizeof(properties->runtimeName),
                  "%s", kRuntimeName);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetSystem(
    XrInstance instance, const XrSystemGetInfo* getInfo, XrSystemId* systemId
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (getInfo == nullptr || systemId == nullptr ||
        getInfo->type != XR_TYPE_SYSTEM_GET_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (getInfo->formFactor != XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) {
        return XR_ERROR_FORM_FACTOR_UNSUPPORTED;
    }
    *systemId = kEndXrSystemId;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetSystemProperties(
    XrInstance instance, XrSystemId systemId, XrSystemProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (properties == nullptr || properties->type != XR_TYPE_SYSTEM_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->systemId = kEndXrSystemId;
    properties->vendorId = kEndXrVendorId;
    std::snprintf(properties->systemName, sizeof(properties->systemName), "%s", kSystemName);
    properties->graphicsProperties.maxSwapchainImageWidth = kMaximumEyeWidth;
    properties->graphicsProperties.maxSwapchainImageHeight = kMaximumEyeHeight;
    properties->graphicsProperties.maxLayerCount = XR_MIN_COMPOSITION_LAYERS_SUPPORTED;
    properties->trackingProperties.orientationTracking = XR_TRUE;
    properties->trackingProperties.positionTracking = XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateViewConfigurations(
    XrInstance instance, XrSystemId systemId, std::uint32_t capacity,
    std::uint32_t* count, XrViewConfigurationType* configurations
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (configurations == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    configurations[0] = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetViewConfigurationProperties(
    XrInstance instance, XrSystemId systemId,
    XrViewConfigurationType viewType, XrViewConfigurationProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (properties == nullptr ||
        properties->type != XR_TYPE_VIEW_CONFIGURATION_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->viewConfigurationType = viewType;
    properties->fovMutable = XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateViewConfigurationViews(
    XrInstance instance, XrSystemId systemId, XrViewConfigurationType viewType,
    std::uint32_t capacity, std::uint32_t* count, XrViewConfigurationView* views
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (views == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    for (std::uint32_t i = 0; i < 2; ++i) {
        if (views[i].type != XR_TYPE_VIEW_CONFIGURATION_VIEW) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        views[i].recommendedImageRectWidth = kRecommendedEyeWidth;
        views[i].maxImageRectWidth = kMaximumEyeWidth;
        views[i].recommendedImageRectHeight = kRecommendedEyeHeight;
        views[i].maxImageRectHeight = kMaximumEyeHeight;
        views[i].recommendedSwapchainSampleCount = 1;
        views[i].maxSwapchainSampleCount = 1;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateEnvironmentBlendModes(
    XrInstance instance, XrSystemId systemId,
    XrViewConfigurationType viewType, std::uint32_t capacity,
    std::uint32_t* count, XrEnvironmentBlendMode* modes
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (viewType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (modes == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    modes[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetOpenGLESGraphicsRequirementsKHR(
    XrInstance instance, XrSystemId systemId,
    XrGraphicsRequirementsOpenGLESKHR* requirements
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!owner->glesExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (requirements == nullptr ||
        requirements->type != XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    requirements->minApiVersionSupported = XR_MAKE_VERSION(3, 0, 0);
    requirements->maxApiVersionSupported = XR_MAKE_VERSION(3, 0, 0);
    owner->glesRequirementsQueried = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateSession(
    XrInstance instance, const XrSessionCreateInfo* createInfo, XrSession* session
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || session == nullptr ||
        createInfo->type != XR_TYPE_SESSION_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *session = XR_NULL_HANDLE;
    if (createInfo->systemId != kEndXrSystemId) return XR_ERROR_SYSTEM_INVALID;
    if (!owner->glesExtensionEnabled) return XR_ERROR_EXTENSION_NOT_PRESENT;
    if (!owner->glesRequirementsQueried) return XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING;
    const auto* binding = findGlesBinding(createInfo->next);
    if (binding == nullptr ||
        binding->type != XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR ||
        binding->display == nullptr || binding->config == nullptr ||
        binding->context == nullptr) {
        return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    }
#if defined(__ANDROID__)
    if (eglGetCurrentDisplay() != reinterpret_cast<EGLDisplay>(binding->display) ||
        eglGetCurrentContext() != reinterpret_cast<EGLContext>(binding->context)) {
        return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    }
#endif
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        for (const auto& entry : g_sessions) {
            if (entry.second->owner == instance) return XR_ERROR_LIMIT_REACHED;
        }
    }
    XrSession_T* created = new (std::nothrow) XrSession_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = instance;
    created->systemId = createInfo->systemId;
    created->display = reinterpret_cast<EGLDisplay>(binding->display);
    created->config = reinterpret_cast<EGLConfig>(binding->config);
    created->context = reinterpret_cast<EGLContext>(binding->context);
    const XrSession handle = makeSessionHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        g_sessions.emplace(handle, created);
    }
    queueSessionState(created, XR_SESSION_STATE_READY);
    *session = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySession(XrSession session) {
    XrSession_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_sessionMutex);
        const auto found = g_sessions.find(session);
        if (found == g_sessions.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (object->running) return XR_ERROR_SESSION_RUNNING;
        if (!object->swapchains.empty() || !object->spaces.empty()) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_sessions.erase(found);
    }
    destroySessionFramebuffer(object);
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrBeginSession(
    XrSession session, const XrSessionBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (beginInfo == nullptr || beginInfo->type != XR_TYPE_SESSION_BEGIN_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->state != XR_SESSION_STATE_READY) return XR_ERROR_SESSION_NOT_READY;
    if (beginInfo->primaryViewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    object->running = true;
    object->exitRequested = false;
    object->frameWaited = false;
    object->frameBegun = false;
    object->hasPredictedTime = false;
    queueSessionState(object, XR_SESSION_STATE_SYNCHRONIZED);
    queueSessionState(object, XR_SESSION_STATE_VISIBLE);
    queueSessionState(object, XR_SESSION_STATE_FOCUSED);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrRequestExitSession(XrSession session) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (object->exitRequested) return XR_ERROR_CALL_ORDER_INVALID;
    object->exitRequested = true;
    queueSessionState(object, XR_SESSION_STATE_STOPPING);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEndSession(XrSession session) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (!object->running || !object->exitRequested ||
        object->state != XR_SESSION_STATE_STOPPING) {
        return XR_ERROR_SESSION_NOT_STOPPING;
    }
    if (object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    object->running = false;
    object->exitRequested = false;
    queueSessionState(object, XR_SESSION_STATE_IDLE);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrPollEvent(
    XrInstance instance, XrEventDataBuffer* eventData
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (eventData == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    std::lock_guard<std::mutex> lock(owner->eventMutex);
    if (owner->pendingSessionEvents.empty()) return XR_EVENT_UNAVAILABLE;
    const XrEventDataSessionStateChanged event = owner->pendingSessionEvents.front();
    owner->pendingSessionEvents.pop_front();
    std::memset(eventData, 0, sizeof(*eventData));
    std::memcpy(eventData, &event, sizeof(event));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrWaitFrame(
    XrSession session, const XrFrameWaitInfo* waitInfo, XrFrameState* frameState
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (waitInfo == nullptr || waitInfo->type != XR_TYPE_FRAME_WAIT_INFO ||
        frameState == nullptr || frameState->type != XR_TYPE_FRAME_STATE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    const XrTime now = monotonicTimeNow();
    const XrTime next = object->hasPredictedTime
        ? std::max(now, object->predictedDisplayTime + kFramePeriod)
        : now + kFramePeriod;
    object->predictedDisplayTime = next;
    object->predictedDisplayPeriod = kFramePeriod;
    object->hasPredictedTime = true;
    object->frameWaited = true;
    frameState->predictedDisplayTime = next;
    frameState->predictedDisplayPeriod = kFramePeriod;
    frameState->shouldRender =
        object->state == XR_SESSION_STATE_VISIBLE ||
        object->state == XR_SESSION_STATE_FOCUSED ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrBeginFrame(
    XrSession session, const XrFrameBeginInfo* beginInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (beginInfo != nullptr && beginInfo->type != XR_TYPE_FRAME_BEGIN_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!object->frameWaited || object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    object->frameBegun = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEndFrame(
    XrSession session, const XrFrameEndInfo* endInfo
) {
    XrSession_T* object = getSession(session);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (endInfo == nullptr || endInfo->type != XR_TYPE_FRAME_END_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!object->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!object->frameWaited || !object->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    if (endInfo->displayTime != object->predictedDisplayTime) return XR_ERROR_TIME_INVALID;
    if (endInfo->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE) {
        return XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED;
    }
    if (endInfo->layerCount > 1 ||
        (endInfo->layerCount > 0 && endInfo->layers == nullptr)) {
        return XR_ERROR_LAYER_LIMIT_EXCEEDED;
    }
    if (endInfo->layerCount == 1) {
        const XrCompositionLayerBaseHeader* layer = endInfo->layers[0];
        if (layer == nullptr || layer->type != XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
            return XR_ERROR_LAYER_INVALID;
        }
        const auto* projection = reinterpret_cast<const XrCompositionLayerProjection*>(layer);
        XrResult composeError = XR_SUCCESS;
        if (compositeProjectionLayer(object, projection, &composeError)) {
            // The application is done with this frame. The host compositor has
            // copied the two released images into the current Android window surface.
        } else {
            object->frameWaited = false;
            object->frameBegun = false;
            return composeError;
        }
    }
    object->frameWaited = false;
    object->frameBegun = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateReferenceSpaces(
    XrSession session, std::uint32_t capacity, std::uint32_t* count,
    XrReferenceSpaceType* spaces
) {
    if (getSession(session) == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (spaces == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const std::uint32_t fill = std::min<std::uint32_t>(capacity, 2);
    if (fill >= 1) spaces[0] = XR_REFERENCE_SPACE_TYPE_VIEW;
    if (fill >= 2) spaces[1] = XR_REFERENCE_SPACE_TYPE_LOCAL;
    return capacity < 2 ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateReferenceSpace(
    XrSession session, const XrReferenceSpaceCreateInfo* createInfo, XrSpace* space
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || space == nullptr ||
        createInfo->type != XR_TYPE_REFERENCE_SPACE_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *space = XR_NULL_HANDLE;
    if (createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_VIEW &&
        createInfo->referenceSpaceType != XR_REFERENCE_SPACE_TYPE_LOCAL) {
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }
    XrSpace_T* created = new (std::nothrow) XrSpace_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->type = createInfo->referenceSpaceType;
    created->poseInReferenceSpace = createInfo->poseInReferenceSpace;
    const XrSpace handle = makeSpaceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        g_spaces.emplace(handle, created);
    }
    owner->spaces.insert(handle);
    *space = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySpace(XrSpace space) {
    XrSpace_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        const auto found = g_spaces.find(space);
        if (found == g_spaces.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        g_spaces.erase(found);
    }
    XrSession_T* owner = getSession(object->owner);
    if (owner != nullptr) owner->spaces.erase(space);
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateSpace(
    XrSpace space, XrSpace baseSpace, XrTime time, XrSpaceLocation* location
) {
    XrSpace_T* object = getSpace(space);
    XrSpace_T* base = getSpace(baseSpace);
    if (object == nullptr || base == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (object->owner != base->owner) return XR_ERROR_HANDLE_INVALID;
    if (location == nullptr || location->type != XR_TYPE_SPACE_LOCATION) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (time <= 0) return XR_ERROR_TIME_INVALID;
    XrQuaternionf orientation{};
    bool hasOrientation = false;
    {
        std::lock_guard<std::mutex> lock(g_headPoseMutex);
        orientation = g_headOrientation;
        hasOrientation = g_hasHeadOrientation;
    }
    location->pose.orientation = orientation;
    location->pose.position = {0.0f, 0.0f, 0.0f};
    location->locationFlags = hasOrientation
        ? XR_SPACE_LOCATION_ORIENTATION_VALID_BIT |
          XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT
        : 0;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrLocateViews(
    XrSession session, const XrViewLocateInfo* locateInfo, XrViewState* viewState,
    std::uint32_t capacity, std::uint32_t* count, XrView* views
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (locateInfo == nullptr || locateInfo->type != XR_TYPE_VIEW_LOCATE_INFO ||
        viewState == nullptr || viewState->type != XR_TYPE_VIEW_STATE ||
        count == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!owner->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (locateInfo->viewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
        return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    }
    if (getSpace(locateInfo->space) == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (locateInfo->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (views == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;

    XrQuaternionf orientation{};
    bool tracked = false;
    {
        std::lock_guard<std::mutex> lock(g_headPoseMutex);
        orientation = g_headOrientation;
        tracked = g_hasHeadOrientation;
    }
    viewState->viewStateFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT;
    if (tracked) {
        viewState->viewStateFlags |= XR_VIEW_STATE_ORIENTATION_TRACKED_BIT;
    }
    for (std::uint32_t eye = 0; eye < 2; ++eye) {
        if (views[eye].type != XR_TYPE_VIEW) return XR_ERROR_VALIDATION_FAILURE;
        views[eye].pose.orientation = orientation;
        views[eye].pose.position = {
            eye == 0 ? -kHalfIpdMeters : kHalfIpdMeters,
            0.0f,
            0.0f
        };
        views[eye].fov = {-0.785398163f, 0.785398163f, 0.785398163f, -0.785398163f};
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateSwapchainFormats(
    XrSession session, std::uint32_t capacity, std::uint32_t* count,
    std::int64_t* formats
) {
    if (getSession(session) == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (formats == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    formats[0] = static_cast<std::int64_t>(GL_RGBA8);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateSwapchain(
    XrSession session, const XrSwapchainCreateInfo* createInfo, XrSwapchain* swapchain
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || swapchain == nullptr ||
        createInfo->type != XR_TYPE_SWAPCHAIN_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *swapchain = XR_NULL_HANDLE;
    if (!graphicsContextIsCurrent(owner)) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    if (createInfo->format != static_cast<std::int64_t>(GL_RGBA8)) {
        return XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
    }
    if ((createInfo->usageFlags & XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT) == 0 ||
        (createInfo->usageFlags & ~(XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT |
                                   XR_SWAPCHAIN_USAGE_SAMPLED_BIT)) != 0) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (createInfo->width == 0 || createInfo->height == 0 ||
        createInfo->width > kMaximumEyeWidth || createInfo->height > kMaximumEyeHeight ||
        createInfo->sampleCount != 1 || createInfo->faceCount != 1 ||
        createInfo->arraySize != 1 || createInfo->mipCount != 1) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrSwapchain_T* created = new (std::nothrow) XrSwapchain_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->width = createInfo->width;
    created->height = createInfo->height;
    for (std::uint32_t i = 0; i < created->imageCount; ++i) {
        if (!createGlesTexture(created->width, created->height, &created->images[i])) {
            for (std::uint32_t j = 0; j < i; ++j) destroyGlesTexture(created->images[j]);
            delete created;
            return XR_ERROR_RUNTIME_FAILURE;
        }
    }
    const XrSwapchain handle = makeSwapchainHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_swapchainMutex);
        g_swapchains.emplace(handle, created);
    }
    owner->swapchains.insert(handle);
    *swapchain = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroySwapchain(XrSwapchain swapchain) {
    XrSwapchain_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_swapchainMutex);
        const auto found = g_swapchains.find(swapchain);
        if (found == g_swapchains.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (object->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_swapchains.erase(found);
    }
    XrSession_T* owner = getSession(object->owner);
    if (owner != nullptr) owner->swapchains.erase(swapchain);
    for (GLuint texture : object->images) destroyGlesTexture(texture);
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateSwapchainImages(
    XrSwapchain swapchain, std::uint32_t capacity, std::uint32_t* count,
    XrSwapchainImageBaseHeader* images
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = object->imageCount;
    if (capacity == 0) return XR_SUCCESS;
    if (images == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < object->imageCount) return XR_ERROR_SIZE_INSUFFICIENT;
    auto* glesImages = reinterpret_cast<XrSwapchainImageOpenGLESKHR*>(images);
    for (std::uint32_t i = 0; i < object->imageCount; ++i) {
        if (glesImages[i].type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
        glesImages[i].image = object->images[i];
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrAcquireSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageAcquireInfo* acquireInfo,
    std::uint32_t* index
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (acquireInfo == nullptr ||
        acquireInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO ||
        index == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (object->acquiredIndex != std::numeric_limits<std::uint32_t>::max()) {
        return XR_ERROR_CALL_ORDER_INVALID;
    }
    object->acquiredIndex = object->nextAcquire;
    object->nextAcquire = (object->nextAcquire + 1) % object->imageCount;
    object->imageWaited = false;
    *index = object->acquiredIndex;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrWaitSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageWaitInfo* waitInfo
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (waitInfo == nullptr || waitInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->acquiredIndex == std::numeric_limits<std::uint32_t>::max() ||
        object->imageWaited) return XR_ERROR_CALL_ORDER_INVALID;
    object->imageWaited = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrReleaseSwapchainImage(
    XrSwapchain swapchain, const XrSwapchainImageReleaseInfo* releaseInfo
) {
    XrSwapchain_T* object = getSwapchain(swapchain);
    if (object == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (releaseInfo == nullptr ||
        releaseInfo->type != XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (object->acquiredIndex == std::numeric_limits<std::uint32_t>::max() ||
        !object->imageWaited) return XR_ERROR_CALL_ORDER_INVALID;
    object->lastReleasedIndex = object->acquiredIndex;
    object->acquiredIndex = std::numeric_limits<std::uint32_t>::max();
    object->imageWaited = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrResultToString(
    XrInstance instance, XrResult value, char buffer[XR_MAX_RESULT_STRING_SIZE]
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const char* label = "XR_RESULT_UNKNOWN";
    switch (value) {
        case XR_SUCCESS: label = "XR_SUCCESS"; break;
        case XR_EVENT_UNAVAILABLE: label = "XR_EVENT_UNAVAILABLE"; break;
        case XR_ERROR_VALIDATION_FAILURE: label = "XR_ERROR_VALIDATION_FAILURE"; break;
        case XR_ERROR_FUNCTION_UNSUPPORTED: label = "XR_ERROR_FUNCTION_UNSUPPORTED"; break;
        case XR_ERROR_EXTENSION_NOT_PRESENT: label = "XR_ERROR_EXTENSION_NOT_PRESENT"; break;
        case XR_ERROR_FEATURE_UNSUPPORTED: label = "XR_ERROR_FEATURE_UNSUPPORTED"; break;
        case XR_ERROR_FORM_FACTOR_UNSUPPORTED: label = "XR_ERROR_FORM_FACTOR_UNSUPPORTED"; break;
        case XR_ERROR_SYSTEM_INVALID: label = "XR_ERROR_SYSTEM_INVALID"; break;
        case XR_ERROR_HANDLE_INVALID: label = "XR_ERROR_HANDLE_INVALID"; break;
        case XR_ERROR_SESSION_NOT_RUNNING: label = "XR_ERROR_SESSION_NOT_RUNNING"; break;
        case XR_ERROR_CALL_ORDER_INVALID: label = "XR_ERROR_CALL_ORDER_INVALID"; break;
        default: break;
    }
    std::snprintf(buffer, XR_MAX_RESULT_STRING_SIZE, "%s", label);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStructureTypeToString(
    XrInstance instance, XrStructureType value, char buffer[XR_MAX_STRUCTURE_NAME_SIZE]
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const char* label = "XR_TYPE_UNKNOWN";
    switch (value) {
        case XR_TYPE_INSTANCE_CREATE_INFO: label = "XR_TYPE_INSTANCE_CREATE_INFO"; break;
        case XR_TYPE_INSTANCE_PROPERTIES: label = "XR_TYPE_INSTANCE_PROPERTIES"; break;
        case XR_TYPE_SYSTEM_GET_INFO: label = "XR_TYPE_SYSTEM_GET_INFO"; break;
        case XR_TYPE_SYSTEM_PROPERTIES: label = "XR_TYPE_SYSTEM_PROPERTIES"; break;
        case XR_TYPE_VIEW_CONFIGURATION_PROPERTIES: label = "XR_TYPE_VIEW_CONFIGURATION_PROPERTIES"; break;
        case XR_TYPE_VIEW_CONFIGURATION_VIEW: label = "XR_TYPE_VIEW_CONFIGURATION_VIEW"; break;
        case XR_TYPE_SESSION_CREATE_INFO: label = "XR_TYPE_SESSION_CREATE_INFO"; break;
        case XR_TYPE_FRAME_STATE: label = "XR_TYPE_FRAME_STATE"; break;
        case XR_TYPE_FRAME_WAIT_INFO: label = "XR_TYPE_FRAME_WAIT_INFO"; break;
        case XR_TYPE_FRAME_BEGIN_INFO: label = "XR_TYPE_FRAME_BEGIN_INFO"; break;
        case XR_TYPE_FRAME_END_INFO: label = "XR_TYPE_FRAME_END_INFO"; break;
        case XR_TYPE_SWAPCHAIN_CREATE_INFO: label = "XR_TYPE_SWAPCHAIN_CREATE_INFO"; break;
        default: break;
    }
    std::snprintf(buffer, XR_MAX_STRUCTURE_NAME_SIZE, "%s", label);
    return XR_SUCCESS;
}

#define ENDXR_PROC(name, fn) {name, reinterpret_cast<PFN_xrVoidFunction>(fn)}
struct ProcEntry { const char* name; PFN_xrVoidFunction function; };
const std::array<ProcEntry, 35> kProcTable{{
    ENDXR_PROC("xrGetInstanceProcAddr", endxrGetInstanceProcAddr),
    ENDXR_PROC("xrEnumerateApiLayerProperties", endxrEnumerateApiLayerProperties),
    ENDXR_PROC("xrEnumerateInstanceExtensionProperties", endxrEnumerateInstanceExtensionProperties),
    ENDXR_PROC("xrCreateInstance", endxrCreateInstance),
    ENDXR_PROC("xrDestroyInstance", endxrDestroyInstance),
    ENDXR_PROC("xrGetInstanceProperties", endxrGetInstanceProperties),
    ENDXR_PROC("xrPollEvent", endxrPollEvent),
    ENDXR_PROC("xrResultToString", endxrResultToString),
    ENDXR_PROC("xrStructureTypeToString", endxrStructureTypeToString),
    ENDXR_PROC("xrGetSystem", endxrGetSystem),
    ENDXR_PROC("xrGetSystemProperties", endxrGetSystemProperties),
    ENDXR_PROC("xrEnumerateViewConfigurations", endxrEnumerateViewConfigurations),
    ENDXR_PROC("xrGetViewConfigurationProperties", endxrGetViewConfigurationProperties),
    ENDXR_PROC("xrEnumerateViewConfigurationViews", endxrEnumerateViewConfigurationViews),
    ENDXR_PROC("xrEnumerateEnvironmentBlendModes", endxrEnumerateEnvironmentBlendModes),
    ENDXR_PROC("xrGetOpenGLESGraphicsRequirementsKHR", endxrGetOpenGLESGraphicsRequirementsKHR),
    ENDXR_PROC("xrCreateSession", endxrCreateSession),
    ENDXR_PROC("xrDestroySession", endxrDestroySession),
    ENDXR_PROC("xrBeginSession", endxrBeginSession),
    ENDXR_PROC("xrRequestExitSession", endxrRequestExitSession),
    ENDXR_PROC("xrEndSession", endxrEndSession),
    ENDXR_PROC("xrWaitFrame", endxrWaitFrame),
    ENDXR_PROC("xrBeginFrame", endxrBeginFrame),
    ENDXR_PROC("xrEndFrame", endxrEndFrame),
    ENDXR_PROC("xrEnumerateReferenceSpaces", endxrEnumerateReferenceSpaces),
    ENDXR_PROC("xrCreateReferenceSpace", endxrCreateReferenceSpace),
    ENDXR_PROC("xrDestroySpace", endxrDestroySpace),
    ENDXR_PROC("xrLocateSpace", endxrLocateSpace),
    ENDXR_PROC("xrLocateViews", endxrLocateViews),
    ENDXR_PROC("xrEnumerateSwapchainFormats", endxrEnumerateSwapchainFormats),
    ENDXR_PROC("xrCreateSwapchain", endxrCreateSwapchain),
    ENDXR_PROC("xrDestroySwapchain", endxrDestroySwapchain),
    ENDXR_PROC("xrEnumerateSwapchainImages", endxrEnumerateSwapchainImages),
    ENDXR_PROC("xrAcquireSwapchainImage", endxrAcquireSwapchainImage),
    ENDXR_PROC("xrWaitSwapchainImage", endxrWaitSwapchainImage)
}};
#undef ENDXR_PROC

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
) {
    if (function == nullptr || name == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *function = nullptr;
    XrInstance_T* owner = nullptr;
    if (instance != XR_NULL_HANDLE) {
        owner = getInstance(instance);
        if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    }
    for (const auto& entry : kProcTable) {
        if (std::strcmp(name, entry.name) == 0) {
            if (std::strcmp(name, "xrGetOpenGLESGraphicsRequirementsKHR") == 0 &&
                owner != nullptr && !owner->glesExtensionEnabled) {
                return XR_ERROR_FUNCTION_UNSUPPORTED;
            }
            *function = entry.function;
            return XR_SUCCESS;
        }
    }
    if (std::strcmp(name, "xrReleaseSwapchainImage") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(endxrReleaseSwapchainImage);
        return XR_SUCCESS;
    }
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}
} // namespace

extern "C" __attribute__((visibility("default"))) XRAPI_ATTR XrResult XRAPI_CALL
xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
) {
    if (loaderInfo == nullptr || runtimeRequest == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (loaderInfo->structType != XR_LOADER_INTERFACE_STRUCT_LOADER_INFO ||
        loaderInfo->structVersion != XR_LOADER_INFO_STRUCT_VERSION ||
        loaderInfo->structSize < sizeof(XrNegotiateLoaderInfo) ||
        runtimeRequest->structType != XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST ||
        runtimeRequest->structVersion != XR_RUNTIME_INFO_STRUCT_VERSION ||
        runtimeRequest->structSize < sizeof(XrNegotiateRuntimeRequest)) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (loaderInfo->minInterfaceVersion > XR_CURRENT_LOADER_RUNTIME_VERSION ||
        loaderInfo->maxInterfaceVersion < XR_CURRENT_LOADER_RUNTIME_VERSION) {
        return XR_ERROR_INITIALIZATION_FAILED;
    }
    if (loaderInfo->minApiVersion > kEndXrApiVersion ||
        loaderInfo->maxApiVersion < kEndXrApiVersion) {
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }
    runtimeRequest->runtimeInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    runtimeRequest->runtimeApiVersion = kEndXrApiVersion;
    runtimeRequest->getInstanceProcAddr = endxrGetInstanceProcAddr;
    return XR_SUCCESS;
}

extern "C" __attribute__((visibility("default"))) void endxrSetHeadOrientation(
    float x, float y, float z, float w
) {
    const double magnitude = std::sqrt(
        static_cast<double>(x) * x + static_cast<double>(y) * y +
        static_cast<double>(z) * z + static_cast<double>(w) * w
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5) return;
    std::lock_guard<std::mutex> lock(g_headPoseMutex);
    g_headOrientation = {
        static_cast<float>(x / magnitude),
        static_cast<float>(y / magnitude),
        static_cast<float>(z / magnitude),
        static_cast<float>(w / magnitude)
    };
    g_hasHeadOrientation = true;
}
