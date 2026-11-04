
#include "endxr_openxr_runtime.hpp"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <deque>
#include <functional>
#include <limits>
#include <mutex>
#include <new>
#include <string>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#if defined(__ANDROID__)
#include <jni.h>
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

struct XrControllerInputSample {
    bool active = false;
    float trigger = 0.0f;
    float grip = 0.0f;
    float stickX = 0.0f;
    float stickY = 0.0f;
    std::uint32_t buttons = 0;
    bool poseActive = false;
    XrPosef gripPose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
};

struct XrActionStateKey {
    XrAction action = XR_NULL_HANDLE;
    XrPath subactionPath = XR_NULL_PATH;
    bool operator==(const XrActionStateKey& other) const {
        return action == other.action && subactionPath == other.subactionPath;
    }
};

struct XrActionStateKeyHash {
    std::size_t operator()(const XrActionStateKey& key) const {
        const std::size_t actionHash = std::hash<XrAction>{}(key.action);
        const std::size_t pathHash = std::hash<XrPath>{}(key.subactionPath);
        return actionHash ^ (pathHash + 0x9e3779b9u +
            (actionHash << 6u) + (actionHash >> 2u));
    }
};

struct XrActionStateSnapshot {
    bool active = false;
    bool booleanValue = false;
    float scalarValue = 0.0f;
    XrVector2f vectorValue{0.0f, 0.0f};
    bool booleanChanged = false;
    bool scalarChanged = false;
    bool vectorChanged = false;
    XrTime booleanLastChangeTime = 0;
    XrTime scalarLastChangeTime = 0;
    XrTime vectorLastChangeTime = 0;
};

struct XrInstance_T {
    XrVersion requestedApiVersion = XR_MAKE_VERSION(1, 0, 0);
#if defined(__ANDROID__)
    JavaVM* applicationVm = nullptr;
    jobject applicationActivity = nullptr;
    std::mutex externalInputMutex;
    std::chrono::steady_clock::time_point lastExternalInputPoll{};
#else
    void* applicationVm = nullptr;
    void* applicationActivity = nullptr;
#endif
    bool androidExtensionEnabled = false;
    bool glesExtensionEnabled = false;
    bool glesRequirementsQueried = false;
    std::mutex eventMutex;
    std::deque<XrEventDataSessionStateChanged> pendingSessionEvents;
    std::unordered_map<std::string, XrPath> pathHandles;
    std::unordered_map<XrPath, std::string> pathStrings;
    std::unordered_map<XrPath, std::vector<XrActionSuggestedBinding>> suggestedBindings;
    XrPath nextPath = 1;
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
    bool actionSetsAttached = false;
    bool actionSyncCompleted = false;
    std::unordered_set<XrActionSet> attachedActionSets;
    std::vector<XrActiveActionSet> activeActionSets;
    std::array<XrControllerInputSample, 2> synchronizedControllerInputs{};
    std::unordered_map<XrActionStateKey, XrActionStateSnapshot, XrActionStateKeyHash>
        actionStateSnapshots;
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
    bool isActionSpace = false;
    XrAction action = XR_NULL_HANDLE;
    XrPath actionSubactionPath = XR_NULL_PATH;
};

struct XrActionSet_T {
    XrInstance owner = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    std::uint32_t priority = 0;
    XrSession attachedSession = XR_NULL_HANDLE;
    std::unordered_set<XrAction> actions;
};

struct XrAction_T {
    XrActionSet owner = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    XrActionType type = XR_ACTION_TYPE_BOOLEAN_INPUT;
    std::vector<XrPath> subactionPaths;
    std::unordered_set<XrSpace> spaces;
};

namespace {
#if defined(__ANDROID__)
bool refreshExternalControllerInput(XrInstance_T* instance);
#endif
bool locateEstimatedHandPose(XrSpace_T* object, XrSpace_T* base, XrPosef* pose);
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

std::mutex g_actionMutex;
std::unordered_map<XrActionSet, XrActionSet_T*> g_actionSets;
std::unordered_map<XrAction, XrAction_T*> g_actions;

std::mutex g_headPoseMutex;
XrQuaternionf g_headOrientation{0.0f, 0.0f, 0.0f, 1.0f};
bool g_hasHeadOrientation = false;
struct EndXrControllerInput {
    bool active = false;
    float trigger = 0.0f, grip = 0.0f, stickX = 0.0f, stickY = 0.0f;
    std::uint32_t buttons = 0;
    bool poseActive = false;
    XrPosef gripPose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    std::chrono::steady_clock::time_point updatedAt{};
};
std::mutex g_controllerInputMutex;
std::array<EndXrControllerInput, 2> g_controllerInputs{};
thread_local std::uint32_t g_lastGlesError = 0;

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
XrActionSet makeActionSetHandle(XrActionSet_T* object) {
    return reinterpret_cast<XrActionSet>(object);
}
XrAction makeActionHandle(XrAction_T* object) {
    return reinterpret_cast<XrAction>(object);
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
XrActionSet_T* getActionSet(XrActionSet actionSet) {
    std::lock_guard<std::mutex> lock(g_actionMutex);
    const auto found = g_actionSets.find(actionSet);
    return found == g_actionSets.end() ? nullptr : found->second;
}
XrAction_T* getAction(XrAction action) {
    std::lock_guard<std::mutex> lock(g_actionMutex);
    const auto found = g_actions.find(action);
    return found == g_actions.end() ? nullptr : found->second;
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
    g_lastGlesError = 0;
    auto captureError = []() {
        const GLenum error = glGetError();
        if (error != GL_NO_ERROR && g_lastGlesError == 0) g_lastGlesError = error;
        return error;
    };
    GLuint texture = 0;
    glGenTextures(1, &texture);
    if (captureError() != GL_NO_ERROR || texture == 0) return false;
    glBindTexture(GL_TEXTURE_2D, texture);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
    glTexStorage2D(GL_TEXTURE_2D, 1, GL_RGBA8, static_cast<GLsizei>(width),
                   static_cast<GLsizei>(height));
    if (captureError() != GL_NO_ERROR) { glDeleteTextures(1, &texture); return false; }
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
#if defined(__ANDROID__)
        created->applicationVm = reinterpret_cast<JavaVM*>(androidInfo->applicationVM);
        JNIEnv* env = nullptr;
        bool attachedHere = false;
        if (created->applicationVm == nullptr) {
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
        jint envStatus = created->applicationVm->GetEnv(
            reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            envStatus = created->applicationVm->AttachCurrentThread(&env, nullptr);
            attachedHere = envStatus == JNI_OK;
        }
        if (envStatus != JNI_OK || env == nullptr) {
            if (attachedHere) created->applicationVm->DetachCurrentThread();
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
        created->applicationActivity = env->NewGlobalRef(
            reinterpret_cast<jobject>(androidInfo->applicationActivity));
        const bool referenceFailed =
            created->applicationActivity == nullptr || env->ExceptionCheck();
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (attachedHere) created->applicationVm->DetachCurrentThread();
        if (referenceFailed) {
            delete created;
            return XR_ERROR_INITIALIZATION_FAILED;
        }
#else
        created->applicationVm = androidInfo->applicationVM;
        created->applicationActivity = androidInfo->applicationActivity;
#endif
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
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        for (const auto& entry : g_actionSets) {
            if (entry.second->owner == instance) return XR_ERROR_CALL_ORDER_INVALID;
        }
    }
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
#if defined(__ANDROID__)
    if (object->applicationVm != nullptr && object->applicationActivity != nullptr) {
        JNIEnv* env = nullptr;
        bool attachedHere = false;
        const jint envStatus = object->applicationVm->GetEnv(
            reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
        if (envStatus == JNI_EDETACHED) {
            if (object->applicationVm->AttachCurrentThread(&env, nullptr) == JNI_OK) {
                attachedHere = true;
            }
        }
        if (env != nullptr) {
            env->DeleteGlobalRef(object->applicationActivity);
        }
        if (attachedHere) object->applicationVm->DetachCurrentThread();
    }
#endif
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
    {
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        for (XrActionSet actionSet : object->attachedActionSets) {
            const auto found = g_actionSets.find(actionSet);
            if (found != g_actionSets.end() && found->second->attachedSession == session) {
                found->second->attachedSession = XR_NULL_HANDLE;
            }
        }
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
    if (object->isActionSpace && object->action != XR_NULL_HANDLE) {
        std::lock_guard<std::mutex> actionLock(g_actionMutex);
        const auto action = g_actions.find(object->action);
        if (action != g_actions.end()) action->second->spaces.erase(space);
    }
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
    if (object->isActionSpace) {
        location->pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
        location->pose.position = {0.0f, 0.0f, 0.0f};
        location->locationFlags = 0;
        if (base->isActionSpace) return XR_SUCCESS;
#if defined(__ANDROID__)
        if (XrSession_T* sessionOwner = getSession(object->owner)) {
            refreshExternalControllerInput(getInstance(sessionOwner->owner));
        }
#endif
        XrPosef estimatedPose{};
        if (!locateEstimatedHandPose(object, base, &estimatedPose)) return XR_SUCCESS;
        location->pose = estimatedPose;
        // The palm pose is estimated from camera landmarks and monocular depth.
        // It is usable as a grip interaction pose, but deliberately not TRACKED.
        location->locationFlags =
            XR_SPACE_LOCATION_POSITION_VALID_BIT |
            XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
        return XR_SUCCESS;
    }
    if (base->isActionSpace) {
        location->pose.orientation = {0.0f, 0.0f, 0.0f, 1.0f};
        location->pose.position = {0.0f, 0.0f, 0.0f};
        location->locationFlags = 0;
        return XR_SUCCESS;
    }
    XrQuaternionf orientation{};
    bool hasOrientation = false;
    {
        std::lock_guard<std::mutex> lock(g_headPoseMutex);
        orientation = g_headOrientation;
        hasOrientation = g_hasHeadOrientation;
    }
#if defined(__ANDROID__)
    if (XrSession_T* sessionOwner = getSession(object->owner)) {
        refreshExternalControllerInput(getInstance(sessionOwner->owner));
    }
    {
        std::lock_guard<std::mutex> lock(g_headPoseMutex);
        orientation = g_headOrientation;
        hasOrientation = g_hasHeadOrientation;
    }
#endif
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

#if defined(__ANDROID__)
    refreshExternalControllerInput(getInstance(owner->owner));
#endif
    XrQuaternionf orientation{};
    bool tracked = false;
    {
        std::lock_guard<std::mutex> lock(g_headPoseMutex);
        orientation = g_headOrientation;
        tracked = g_hasHeadOrientation;
    }
    viewState->viewStateFlags = tracked
        ? XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_ORIENTATION_TRACKED_BIT
        : 0;
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


namespace {

bool readFixedName(const char* value, std::size_t capacity, std::string* output) {
    if (value == nullptr || output == nullptr || capacity == 0) return false;
    const auto* terminator = static_cast<const char*>(std::memchr(value, '\0', capacity));
    if (terminator == nullptr || terminator == value) return false;
    output->assign(value, static_cast<std::size_t>(terminator - value));
    return true;
}

bool validActionIdentifier(const std::string& value) {
    if (value.empty()) return false;
    for (const unsigned char character : value) {
        const bool allowed =
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') ||
            character == '_';
        if (!allowed) return false;
    }
    return true;
}

bool validOpenXrPath(const std::string& value) {
    if (value.size() < 2 || value.size() >= XR_MAX_PATH_LENGTH ||
        value.front() != '/' || value.back() == '/') return false;
    bool previousSlash = false;
    for (std::size_t index = 1; index < value.size(); ++index) {
        const unsigned char character = static_cast<unsigned char>(value[index]);
        if (character == '/') {
            if (previousSlash) return false;
            previousSlash = true;
            continue;
        }
        previousSlash = false;
        const bool allowed =
            (character >= 'a' && character <= 'z') ||
            (character >= '0' && character <= '9') ||
            character == '_' || character == '-' || character == '.';
        if (!allowed) return false;
    }
    return !previousSlash;
}

bool pathBelongsToInstance(XrInstance instance, XrPath path, std::string* value = nullptr) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr || path == XR_NULL_PATH) return false;
    const auto found = owner->pathStrings.find(path);
    if (found == owner->pathStrings.end()) return false;
    if (value != nullptr) *value = found->second;
    return true;
}

bool validUserPath(XrInstance instance, XrPath path) {
    std::string value;
    return pathBelongsToInstance(instance, path, &value) &&
        value.compare(0, 6, "/user/") == 0;
}

bool validActionSubactionPath(const XrAction_T* action, XrPath path) {
    if (action == nullptr) return false;
    if (path == XR_NULL_PATH) return true;
    return std::find(action->subactionPaths.begin(), action->subactionPaths.end(), path) !=
        action->subactionPaths.end();
}
int handIndexFromPath(const std::string& path) {
    if (path.compare(0, 15, "/user/hand/left") == 0) return 0;
    if (path.compare(0, 16, "/user/hand/right") == 0) return 1;
    return -1;
}
EndXrControllerInput readControllerInput(int hand) {
    if (hand < 0 || hand > 1) return {};
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto input = g_controllerInputs[static_cast<std::size_t>(hand)];
    if (!input.active || input.updatedAt.time_since_epoch().count() == 0 ||
        std::chrono::steady_clock::now() - input.updatedAt > std::chrono::milliseconds(300)) return {};
    return input;
}

#if defined(__ANDROID__)
bool refreshExternalControllerInputImpl(XrInstance_T* instance) {
    if (instance == nullptr || !instance->androidExtensionEnabled ||
        instance->applicationVm == nullptr || instance->applicationActivity == nullptr) {
        return false;
    }

    const auto now = std::chrono::steady_clock::now();
    {
        std::lock_guard<std::mutex> lock(instance->externalInputMutex);
        if (instance->lastExternalInputPoll.time_since_epoch().count() != 0 &&
            now - instance->lastExternalInputPoll < std::chrono::milliseconds(8)) {
            return true;
        }
        instance->lastExternalInputPoll = now;
    }

    JNIEnv* env = nullptr;
    bool attachedHere = false;
    const jint envStatus = instance->applicationVm->GetEnv(
        reinterpret_cast<void**>(&env), JNI_VERSION_1_6);
    if (envStatus == JNI_EDETACHED) {
        if (instance->applicationVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            endxrClearControllerInput();
            endxrClearHeadOrientation();
            return false;
        }
        attachedHere = true;
    } else if (envStatus != JNI_OK || env == nullptr) {
        endxrClearControllerInput();
        endxrClearHeadOrientation();
        return false;
    }

    bool success = false;
    if (env->PushLocalFrame(32) >= 0) {
        do {
            jclass contextClass = env->FindClass("android/content/Context");
            if (contextClass == nullptr || env->ExceptionCheck()) break;
            jmethodID getContentResolver = env->GetMethodID(
                contextClass, "getContentResolver",
                "()Landroid/content/ContentResolver;");
            if (getContentResolver == nullptr || env->ExceptionCheck()) break;
            jobject resolver = env->CallObjectMethod(
                instance->applicationActivity, getContentResolver);
            if (resolver == nullptr || env->ExceptionCheck()) break;

            jclass uriClass = env->FindClass("android/net/Uri");
            if (uriClass == nullptr || env->ExceptionCheck()) break;
            jmethodID parseUri = env->GetStaticMethodID(
                uriClass, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
            if (parseUri == nullptr || env->ExceptionCheck()) break;
            jstring uriText = env->NewStringUTF(
                "content://com.noyclic.questphonexr.endxrinput/controller_state");
            if (uriText == nullptr || env->ExceptionCheck()) break;
            jobject uri = env->CallStaticObjectMethod(uriClass, parseUri, uriText);
            if (uri == nullptr || env->ExceptionCheck()) break;

            jclass resolverClass = env->FindClass("android/content/ContentResolver");
            if (resolverClass == nullptr || env->ExceptionCheck()) break;
            jmethodID query = env->GetMethodID(
                resolverClass, "query",
                "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;");
            if (query == nullptr || env->ExceptionCheck()) break;
            jobject cursor = env->CallObjectMethod(
                resolver, query, uri, nullptr, nullptr, nullptr, nullptr);
            if (cursor == nullptr || env->ExceptionCheck()) break;

            jclass cursorClass = env->FindClass("android/database/Cursor");
            if (cursorClass == nullptr || env->ExceptionCheck()) break;
            jmethodID moveToFirst = env->GetMethodID(cursorClass, "moveToFirst", "()Z");
            jmethodID getColumnIndex = env->GetMethodID(
                cursorClass, "getColumnIndexOrThrow", "(Ljava/lang/String;)I");
            jmethodID getInt = env->GetMethodID(cursorClass, "getInt", "(I)I");
            jmethodID getFloat = env->GetMethodID(cursorClass, "getFloat", "(I)F");
            jmethodID close = env->GetMethodID(cursorClass, "close", "()V");
            if (moveToFirst == nullptr || getColumnIndex == nullptr ||
                getInt == nullptr || getFloat == nullptr || close == nullptr ||
                env->ExceptionCheck()) break;

            const jboolean hasRow = env->CallBooleanMethod(cursor, moveToFirst);
            if (env->ExceptionCheck() || hasRow != JNI_TRUE) {
                if (env->ExceptionCheck()) env->ExceptionClear();
                env->CallVoidMethod(cursor, close);
                break;
            }

            auto readInt = [&](const char* name, jint* value) -> bool {
                jstring columnName = env->NewStringUTF(name);
                if (columnName == nullptr || env->ExceptionCheck()) return false;
                const jint index = env->CallIntMethod(cursor, getColumnIndex, columnName);
                if (env->ExceptionCheck()) return false;
                *value = env->CallIntMethod(cursor, getInt, index);
                return !env->ExceptionCheck();
            };
            auto readFloat = [&](const char* name, jfloat* value) -> bool {
                jstring columnName = env->NewStringUTF(name);
                if (columnName == nullptr || env->ExceptionCheck()) return false;
                const jint index = env->CallIntMethod(cursor, getColumnIndex, columnName);
                if (env->ExceptionCheck()) return false;
                *value = env->CallFloatMethod(cursor, getFloat, index);
                return !env->ExceptionCheck();
            };

            jint stale = 1;
            jint leftActive = 0, leftButtons = 0, rightActive = 0, rightButtons = 0;
            jint headActive = 0, headStale = 1;
            jint handPoseStale = 1, leftPoseActive = 0, rightPoseActive = 0;
            jfloat leftTrigger = 0, leftGrip = 0, leftStickX = 0, leftStickY = 0;
            jfloat rightTrigger = 0, rightGrip = 0, rightStickX = 0, rightStickY = 0;
            jfloat headX = 0, headY = 0, headZ = 0, headW = 1;
            jfloat leftPoseX = 0, leftPoseY = 0, leftPoseZ = 0;
            jfloat leftPoseQx = 0, leftPoseQy = 0, leftPoseQz = 0, leftPoseQw = 1;
            jfloat rightPoseX = 0, rightPoseY = 0, rightPoseZ = 0;
            jfloat rightPoseQx = 0, rightPoseQy = 0, rightPoseQz = 0, rightPoseQw = 1;
            const bool columnsRead =
                readInt("stale", &stale) &&
                readInt("left_active", &leftActive) &&
                readFloat("left_trigger", &leftTrigger) &&
                readFloat("left_grip", &leftGrip) &&
                readFloat("left_stick_x", &leftStickX) &&
                readFloat("left_stick_y", &leftStickY) &&
                readInt("left_buttons", &leftButtons) &&
                readInt("right_active", &rightActive) &&
                readFloat("right_trigger", &rightTrigger) &&
                readFloat("right_grip", &rightGrip) &&
                readFloat("right_stick_x", &rightStickX) &&
                readFloat("right_stick_y", &rightStickY) &&
                readInt("right_buttons", &rightButtons) &&
                readInt("head_orientation_active", &headActive) &&
                readInt("head_orientation_stale", &headStale) &&
                readFloat("head_orientation_x", &headX) &&
                readFloat("head_orientation_y", &headY) &&
                readFloat("head_orientation_z", &headZ) &&
                readFloat("head_orientation_w", &headW) &&
                readInt("hand_pose_stale", &handPoseStale) &&
                readInt("left_pose_active", &leftPoseActive) &&
                readFloat("left_pose_x", &leftPoseX) &&
                readFloat("left_pose_y", &leftPoseY) &&
                readFloat("left_pose_z", &leftPoseZ) &&
                readFloat("left_pose_qx", &leftPoseQx) &&
                readFloat("left_pose_qy", &leftPoseQy) &&
                readFloat("left_pose_qz", &leftPoseQz) &&
                readFloat("left_pose_qw", &leftPoseQw) &&
                readInt("right_pose_active", &rightPoseActive) &&
                readFloat("right_pose_x", &rightPoseX) &&
                readFloat("right_pose_y", &rightPoseY) &&
                readFloat("right_pose_z", &rightPoseZ) &&
                readFloat("right_pose_qx", &rightPoseQx) &&
                readFloat("right_pose_qy", &rightPoseQy) &&
                readFloat("right_pose_qz", &rightPoseQz) &&
                readFloat("right_pose_qw", &rightPoseQw);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->CallVoidMethod(cursor, close);
            if (env->ExceptionCheck()) {
                env->ExceptionClear();
                break;
            }
            if (!columnsRead) break;

            if (stale != 0) {
                endxrClearControllerInput();
            } else {
                endxrSetControllerInput(0, static_cast<std::uint32_t>(leftActive != 0),
                    leftTrigger, leftGrip, leftStickX, leftStickY,
                    static_cast<std::uint32_t>(leftButtons));
                endxrSetControllerInput(1, static_cast<std::uint32_t>(rightActive != 0),
                    rightTrigger, rightGrip, rightStickX, rightStickY,
                    static_cast<std::uint32_t>(rightButtons));
            }
            if (headStale != 0 || headActive == 0) {
                endxrClearHeadOrientation();
            } else {
                endxrSetHeadOrientation(headX, headY, headZ, headW);
            }
            if (handPoseStale != 0) {
                endxrClearHandPoses();
            } else {
                endxrSetHandPose(0, static_cast<std::uint32_t>(leftPoseActive != 0),
                    leftPoseX, leftPoseY, leftPoseZ,
                    leftPoseQx, leftPoseQy, leftPoseQz, leftPoseQw);
                endxrSetHandPose(1, static_cast<std::uint32_t>(rightPoseActive != 0),
                    rightPoseX, rightPoseY, rightPoseZ,
                    rightPoseQx, rightPoseQy, rightPoseQz, rightPoseQw);
            }
            success = true;
        } while (false);

        if (env->ExceptionCheck()) {
            env->ExceptionClear();
            endxrClearControllerInput();
            endxrClearHeadOrientation();
        }
        env->PopLocalFrame(nullptr);
    } else {
        if (env->ExceptionCheck()) env->ExceptionClear();
        endxrClearControllerInput();
        endxrClearHeadOrientation();
    }

    if (!success) {
        endxrClearControllerInput();
        endxrClearHeadOrientation();
    }
    if (attachedHere) instance->applicationVm->DetachCurrentThread();
    return success;
}
#endif

XrPath currentProfileForHand(XrSession_T* session, int hand) {
    if (session == nullptr || hand < 0 || hand > 1 ||
        !session->synchronizedControllerInputs[static_cast<std::size_t>(hand)].active) {
        return XR_NULL_PATH;
    }
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return XR_NULL_PATH;
    const std::string prefix = hand == 0 ? "/user/hand/left/" : "/user/hand/right/";
    std::vector<std::pair<std::string, XrPath>> profiles;
    for (const auto& entry : instance->suggestedBindings) {
        for (const auto& binding : entry.second) {
            std::string bindingPath, profilePath;
            if (pathBelongsToInstance(session->owner, binding.binding, &bindingPath) &&
                bindingPath.compare(0, prefix.size(), prefix) == 0 &&
                pathBelongsToInstance(session->owner, entry.first, &profilePath)) {
                profiles.emplace_back(profilePath, entry.first);
                break;
            }
        }
    }
    std::sort(profiles.begin(), profiles.end(),
        [](const auto& a, const auto& b) { return a.first < b.first; });
    return profiles.empty() ? XR_NULL_PATH : profiles.front().second;
}
struct ResolvedActionInput {
    bool active = false, booleanValue = false;
    float scalarValue = 0.0f;
    XrVector2f vectorValue{0.0f, 0.0f};
};
ResolvedActionInput resolveActionInput(XrSession_T* session, XrAction_T* action, XrPath requestedHand) {
    ResolvedActionInput result{};
    if (session == nullptr || action == nullptr) return result;
    const bool actionSetActive = std::any_of(session->activeActionSets.begin(),
        session->activeActionSets.end(), [&](const XrActiveActionSet& active) {
            return active.actionSet == action->owner &&
                (active.subactionPath == XR_NULL_PATH || requestedHand == XR_NULL_PATH ||
                 active.subactionPath == requestedHand);
        });
    if (!actionSetActive) return result;
    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return result;
    const auto requested = instance->pathStrings.find(requestedHand);
    const int firstHand = requestedHand == XR_NULL_PATH ? 0 :
        (requested == instance->pathStrings.end() ? -1 : handIndexFromPath(requested->second));
    const int lastHand = requestedHand == XR_NULL_PATH ? 1 : firstHand;
    if (firstHand < 0) return result;
    for (int hand = firstHand; hand <= lastHand; ++hand) {
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left") ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right") ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        const bool handSetActive = std::any_of(session->activeActionSets.begin(),
            session->activeActionSets.end(), [&](const XrActiveActionSet& active) {
                return active.actionSet == action->owner &&
                    (active.subactionPath == XR_NULL_PATH || active.subactionPath == handPath);
            });
        if (!handSetActive) continue;
        const XrPath profile = currentProfileForHand(session, hand);
        const auto profileBindings = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || profileBindings == instance->suggestedBindings.end()) continue;
        const auto& input = session->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        for (const auto& binding : profileBindings->second) {
            if (binding.action != reinterpret_cast<XrAction>(action)) continue;
            std::string path;
            if (!pathBelongsToInstance(session->owner, binding.binding, &path) ||
                handIndexFromPath(path) != hand) continue;
            const auto marker = path.find("/input/");
            if (marker == std::string::npos) continue;
            const std::string component = path.substr(marker + 7);
            float scalar = 0.0f;
            bool pressed = false, recognized = true;
            XrVector2f vector{input.stickX, input.stickY};
            if (component == "select/click" || component == "trigger/click" ||
                component == "select/value" || component == "trigger/value") {
                scalar = input.trigger; pressed = scalar >= 0.5f;
            } else if (component == "squeeze/click" || component == "grip/click" ||
                       component == "squeeze/value" || component == "grip/value") {
                scalar = input.grip; pressed = scalar >= 0.5f;
            } else if (component == "thumbstick" || component == "thumbstick/x" ||
                       component == "thumbstick/y") {
                scalar = component == "thumbstick/x" ? input.stickX :
                    component == "thumbstick/y" ? input.stickY : 0.0f;
                pressed = std::abs(scalar) > 0.5f;
            } else {
                const std::uint32_t bit = component == "a/click" ? 0u :
                    component == "b/click" ? 1u : component == "x/click" ? 2u :
                    component == "y/click" ? 3u : component == "menu/click" ? 4u : 31u;
                recognized = bit < 5;
                pressed = recognized && (input.buttons & (1u << bit)) != 0;
                scalar = pressed ? 1.0f : 0.0f;
            }
            if (!recognized) continue;
            result.active = true;
            result.booleanValue = result.booleanValue || pressed;
            result.scalarValue = std::max(result.scalarValue, scalar);
            result.vectorValue = vector;
        }
    }
    return result;
}

struct ResolvedActionPose {
    bool active = false;
    XrPosef pose{{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
};

XrQuaternionf normalizedQuaternion(const XrQuaternionf& value) {
    const double magnitude = std::sqrt(
        static_cast<double>(value.x) * value.x +
        static_cast<double>(value.y) * value.y +
        static_cast<double>(value.z) * value.z +
        static_cast<double>(value.w) * value.w
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5) {
        return {0.0f, 0.0f, 0.0f, 1.0f};
    }
    return {
        static_cast<float>(value.x / magnitude),
        static_cast<float>(value.y / magnitude),
        static_cast<float>(value.z / magnitude),
        static_cast<float>(value.w / magnitude)
    };
}

XrQuaternionf multiplyQuaternion(const XrQuaternionf& a, const XrQuaternionf& b) {
    return normalizedQuaternion({
        a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
        a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
        a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z
    });
}

XrVector3f rotateVector(const XrQuaternionf& orientation, const XrVector3f& value) {
    const XrQuaternionf q = normalizedQuaternion(orientation);
    const XrVector3f qv{q.x, q.y, q.z};
    const XrVector3f t{
        2.0f * (qv.y * value.z - qv.z * value.y),
        2.0f * (qv.z * value.x - qv.x * value.z),
        2.0f * (qv.x * value.y - qv.y * value.x)
    };
    const XrVector3f cross{
        qv.y * t.z - qv.z * t.y,
        qv.z * t.x - qv.x * t.z,
        qv.x * t.y - qv.y * t.x
    };
    return {
        value.x + q.w * t.x + cross.x,
        value.y + q.w * t.y + cross.y,
        value.z + q.w * t.z + cross.z
    };
}

XrPosef composePose(const XrPosef& parent, const XrPosef& child) {
    const XrQuaternionf orientation = normalizedQuaternion(parent.orientation);
    const XrVector3f offset = rotateVector(orientation, child.position);
    return {
        multiplyQuaternion(orientation, child.orientation),
        {
            parent.position.x + offset.x,
            parent.position.y + offset.y,
            parent.position.z + offset.z
        }
    };
}

XrPosef inversePose(const XrPosef& value) {
    const XrQuaternionf q = normalizedQuaternion(value.orientation);
    const XrQuaternionf inverse{-q.x, -q.y, -q.z, q.w};
    const XrVector3f negative{-value.position.x, -value.position.y, -value.position.z};
    return {inverse, rotateVector(inverse, negative)};
}

ResolvedActionPose resolveActionPose(
    XrSession_T* session, XrAction_T* action, XrPath requestedHand
) {
    ResolvedActionPose result{};
    if (session == nullptr || action == nullptr) return result;

    const bool actionSetActive = std::any_of(
        session->activeActionSets.begin(), session->activeActionSets.end(),
        [&](const XrActiveActionSet& active) {
            return active.actionSet == action->owner &&
                (active.subactionPath == XR_NULL_PATH || requestedHand == XR_NULL_PATH ||
                 active.subactionPath == requestedHand);
        });
    if (!actionSetActive) return result;

    XrInstance_T* instance = getInstance(session->owner);
    if (instance == nullptr) return result;
    const auto requested = instance->pathStrings.find(requestedHand);
    const int firstHand = requestedHand == XR_NULL_PATH ? 0 :
        (requested == instance->pathStrings.end()
            ? -1 : handIndexFromPath(requested->second));
    const int lastHand = requestedHand == XR_NULL_PATH ? 1 : firstHand;
    if (firstHand < 0) return result;

    for (int hand = firstHand; hand <= lastHand; ++hand) {
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left")
                ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right")
                ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        const bool handSetActive = std::any_of(
            session->activeActionSets.begin(), session->activeActionSets.end(),
            [&](const XrActiveActionSet& active) {
                return active.actionSet == action->owner &&
                    (active.subactionPath == XR_NULL_PATH || active.subactionPath == handPath);
            });
        if (!handSetActive) continue;

        const auto& input =
            session->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        if (!input.active || !input.poseActive) continue;

        const XrPath profile = currentProfileForHand(session, hand);
        const auto profileBindings = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || profileBindings == instance->suggestedBindings.end()) {
            continue;
        }
        for (const auto& binding : profileBindings->second) {
            if (binding.action != reinterpret_cast<XrAction>(action)) continue;
            std::string path;
            if (!pathBelongsToInstance(session->owner, binding.binding, &path) ||
                handIndexFromPath(path) != hand) continue;
            const auto marker = path.find("/input/");
            if (marker == std::string::npos) continue;
            const std::string component = path.substr(marker + 7);
            if (component != "grip/pose" && component != "aim/pose") continue;
            result.active = true;
            result.pose = input.gripPose;
            return result;
        }
    }
    return result;
}

bool locateEstimatedHandPoseImpl(
    XrSpace_T* object, XrSpace_T* base, XrPosef* poseOutput
) {
    if (object == nullptr || base == nullptr || poseOutput == nullptr ||
        !object->isActionSpace || base->isActionSpace) return false;
    XrSession_T* session = getSession(object->owner);
    if (session == nullptr) return false;
    XrAction_T* action = getAction(object->action);
    const ResolvedActionPose resolved =
        resolveActionPose(session, action, object->actionSubactionPath);
    if (!resolved.active) return false;

    XrPosef pose = composePose(resolved.pose, object->poseInReferenceSpace);
    if (base->type == XR_REFERENCE_SPACE_TYPE_LOCAL) {
        XrQuaternionf headOrientation{};
        bool headOrientationActive = false;
        {
            std::lock_guard<std::mutex> lock(g_headPoseMutex);
            headOrientation = g_headOrientation;
            headOrientationActive = g_hasHeadOrientation;
        }
        if (!headOrientationActive) return false;
        XrPosef headPose{};
        headPose.orientation = headOrientation;
        headPose.position = {0.0f, 0.0f, 0.0f};
        pose = composePose(headPose, pose);
    } else if (base->type != XR_REFERENCE_SPACE_TYPE_VIEW) {
        return false;
    }

    *poseOutput = composePose(inversePose(base->poseInReferenceSpace), pose);
    return true;
}

XrActionStateSnapshot getActionStateSnapshot(
    XrSession_T* session, XrAction action, XrPath subactionPath
) {
    if (session == nullptr) return {};
    const XrActionStateKey key{action, subactionPath};
    const auto found = session->actionStateSnapshots.find(key);
    return found == session->actionStateSnapshots.end()
        ? XrActionStateSnapshot{} : found->second;
}

void rebuildActionStateSnapshots(XrSession_T* session) {
    if (session == nullptr) return;
    const auto previous = session->actionStateSnapshots;
    session->actionStateSnapshots.clear();
    const XrTime syncTime = monotonicTimeNow();

    for (const XrActionSet actionSetHandle : session->attachedActionSets) {
        XrActionSet_T* actionSet = getActionSet(actionSetHandle);
        if (actionSet == nullptr) continue;
        for (const XrAction actionHandle : actionSet->actions) {
            XrAction_T* action = getAction(actionHandle);
            if (action == nullptr) continue;

            std::vector<XrPath> subactionPaths{XR_NULL_PATH};
            subactionPaths.insert(
                subactionPaths.end(),
                action->subactionPaths.begin(),
                action->subactionPaths.end());
            std::sort(subactionPaths.begin(), subactionPaths.end());
            subactionPaths.erase(
                std::unique(subactionPaths.begin(), subactionPaths.end()),
                subactionPaths.end());

            for (const XrPath subactionPath : subactionPaths) {
                const ResolvedActionInput current = resolveActionInput(
                    session, action, subactionPath);
                const XrActionStateKey key{actionHandle, subactionPath};
                const auto previousState = previous.find(key);
                const XrActionStateSnapshot oldState = previousState == previous.end()
                    ? XrActionStateSnapshot{} : previousState->second;

                XrActionStateSnapshot snapshot{};
                snapshot.active = current.active;
                snapshot.booleanValue = current.booleanValue;
                snapshot.scalarValue = current.scalarValue;
                snapshot.vectorValue = current.vectorValue;
                snapshot.booleanChanged = current.active != oldState.active ||
                    current.booleanValue != oldState.booleanValue;
                snapshot.scalarChanged = current.active != oldState.active ||
                    current.scalarValue != oldState.scalarValue;
                snapshot.vectorChanged = current.active != oldState.active ||
                    current.vectorValue.x != oldState.vectorValue.x ||
                    current.vectorValue.y != oldState.vectorValue.y;
                snapshot.booleanLastChangeTime = snapshot.booleanChanged
                    ? syncTime : oldState.booleanLastChangeTime;
                snapshot.scalarLastChangeTime = snapshot.scalarChanged
                    ? syncTime : oldState.scalarLastChangeTime;
                snapshot.vectorLastChangeTime = snapshot.vectorChanged
                    ? syncTime : oldState.vectorLastChangeTime;
                session->actionStateSnapshots.emplace(key, snapshot);
            }
        }
    }
}

XrResult validateActionForSession(
    XrSession_T* session,
    const XrActionStateGetInfo* info,
    XrActionType expectedType,
    XrAction_T** actionOutput
) {
    if (session == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (info == nullptr || info->type != XR_TYPE_ACTION_STATE_GET_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(info->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != expectedType) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionSet->owner != session->owner || actionSet->attachedSession !=
            reinterpret_cast<XrSession>(session)) {
#if XR_PTR_SIZE != 8
        XrSession sessionHandle = XR_NULL_HANDLE;
        {
            std::lock_guard<std::mutex> lock(g_sessionMutex);
            for (const auto& entry : g_sessions) {
                if (entry.second == session) sessionHandle = entry.first;
            }
        }
        if (actionSet->owner != session->owner || actionSet->attachedSession != sessionHandle) {
            return XR_ERROR_ACTIONSET_NOT_ATTACHED;
        }
#else
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
#endif
    }
    if (!validActionSubactionPath(action, info->subactionPath)) return XR_ERROR_PATH_INVALID;
    if (!session->actionSyncCompleted) return XR_ERROR_CALL_ORDER_INVALID;
    if (!session->running || session->state != XR_SESSION_STATE_FOCUSED) {
        return XR_SESSION_NOT_FOCUSED;
    }
    if (actionOutput != nullptr) *actionOutput = action;
    return XR_SUCCESS;
}

} // namespace

#if defined(__ANDROID__)
bool refreshExternalControllerInput(XrInstance_T* instance) {
    return refreshExternalControllerInputImpl(instance);
}
#endif
bool locateEstimatedHandPose(XrSpace_T* object, XrSpace_T* base, XrPosef* pose) {
    return locateEstimatedHandPoseImpl(object, base, pose);
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStringToPath(
    XrInstance instance, const char* pathString, XrPath* path
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (pathString == nullptr || path == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *path = XR_NULL_PATH;
    const std::size_t length = ::strnlen(pathString, XR_MAX_PATH_LENGTH);
    if (length >= XR_MAX_PATH_LENGTH) return XR_ERROR_PATH_FORMAT_INVALID;
    const std::string value(pathString, length);
    if (!validOpenXrPath(value)) return XR_ERROR_PATH_FORMAT_INVALID;
    const auto existing = owner->pathHandles.find(value);
    if (existing != owner->pathHandles.end()) {
        *path = existing->second;
        return XR_SUCCESS;
    }
    const XrPath created = owner->nextPath++;
    if (created == XR_NULL_PATH) return XR_ERROR_LIMIT_REACHED;
    owner->pathHandles.emplace(value, created);
    owner->pathStrings.emplace(created, value);
    *path = created;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrPathToString(
    XrInstance instance, XrPath path, std::uint32_t capacity,
    std::uint32_t* count, char* buffer
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    const auto found = owner->pathStrings.find(path);
    if (found == owner->pathStrings.end()) return XR_ERROR_PATH_INVALID;
    const std::uint32_t required = static_cast<std::uint32_t>(found->second.size() + 1);
    *count = required;
    if (capacity == 0) return XR_SUCCESS;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < required) return XR_ERROR_SIZE_INSUFFICIENT;
    std::memcpy(buffer, found->second.c_str(), required);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateActionSet(
    XrInstance instance, const XrActionSetCreateInfo* createInfo, XrActionSet* actionSet
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || actionSet == nullptr ||
        createInfo->type != XR_TYPE_ACTION_SET_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *actionSet = XR_NULL_HANDLE;
    std::string name;
    std::string localizedName;
    if (!readFixedName(createInfo->actionSetName, sizeof(createInfo->actionSetName), &name) ||
        !readFixedName(createInfo->localizedActionSetName,
                       sizeof(createInfo->localizedActionSetName), &localizedName)) {
        return XR_ERROR_NAME_INVALID;
    }
    if (!validActionIdentifier(name)) return XR_ERROR_NAME_INVALID;

    auto* created = new (std::nothrow) XrActionSet_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = instance;
    created->name = name;
    created->localizedName = localizedName;
    created->priority = createInfo->priority;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        for (const auto& entry : g_actionSets) {
            if (entry.second->owner == instance && entry.second->name == name) {
                delete created;
                return XR_ERROR_NAME_DUPLICATED;
            }
        }
        *actionSet = makeActionSetHandle(created);
        g_actionSets.emplace(*actionSet, created);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyActionSet(XrActionSet actionSet) {
    XrActionSet_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actionSets.find(actionSet);
        if (found == g_actionSets.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (!object->actions.empty() || object->attachedSession != XR_NULL_HANDLE) {
            return XR_ERROR_CALL_ORDER_INVALID;
        }
        g_actionSets.erase(found);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateAction(
    XrActionSet actionSet, const XrActionCreateInfo* createInfo, XrAction* action
) {
    XrActionSet_T* owner = getActionSet(actionSet);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || action == nullptr ||
        createInfo->type != XR_TYPE_ACTION_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *action = XR_NULL_HANDLE;
    if (owner->attachedSession != XR_NULL_HANDLE) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;

    std::string name;
    std::string localizedName;
    if (!readFixedName(createInfo->actionName, sizeof(createInfo->actionName), &name) ||
        !readFixedName(createInfo->localizedActionName,
                       sizeof(createInfo->localizedActionName), &localizedName)) {
        return XR_ERROR_NAME_INVALID;
    }
    if (!validActionIdentifier(name)) return XR_ERROR_NAME_INVALID;
    switch (createInfo->actionType) {
        case XR_ACTION_TYPE_BOOLEAN_INPUT:
        case XR_ACTION_TYPE_FLOAT_INPUT:
        case XR_ACTION_TYPE_VECTOR2F_INPUT:
        case XR_ACTION_TYPE_POSE_INPUT:
        case XR_ACTION_TYPE_VIBRATION_OUTPUT:
            break;
        default:
            return XR_ERROR_ACTION_TYPE_MISMATCH;
    }
    if (createInfo->countSubactionPaths > 0 && createInfo->subactionPaths == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    auto* created = new (std::nothrow) XrAction_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = actionSet;
    created->name = name;
    created->localizedName = localizedName;
    created->type = createInfo->actionType;
    created->subactionPaths.reserve(createInfo->countSubactionPaths);
    for (std::uint32_t index = 0; index < createInfo->countSubactionPaths; ++index) {
        const XrPath path = createInfo->subactionPaths[index];
        if (!validUserPath(owner->owner, path)) {
            delete created;
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        if (std::find(created->subactionPaths.begin(), created->subactionPaths.end(), path) !=
            created->subactionPaths.end()) {
            delete created;
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        created->subactionPaths.push_back(path);
    }

    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto currentSet = g_actionSets.find(actionSet);
        if (currentSet == g_actionSets.end()) {
            delete created;
            return XR_ERROR_HANDLE_INVALID;
        }
        if (currentSet->second->attachedSession != XR_NULL_HANDLE) {
            delete created;
            return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        }
        for (XrAction existing : currentSet->second->actions) {
            const auto found = g_actions.find(existing);
            if (found != g_actions.end() && found->second->name == name) {
                delete created;
                return XR_ERROR_NAME_DUPLICATED;
            }
        }
        *action = makeActionHandle(created);
        g_actions.emplace(*action, created);
        currentSet->second->actions.insert(*action);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrDestroyAction(XrAction action) {
    XrAction_T* object = nullptr;
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actions.find(action);
        if (found == g_actions.end()) return XR_ERROR_HANDLE_INVALID;
        object = found->second;
        if (!object->spaces.empty()) return XR_ERROR_CALL_ORDER_INVALID;
        const auto owner = g_actionSets.find(object->owner);
        if (owner != g_actionSets.end()) owner->second->actions.erase(action);
        g_actions.erase(found);
    }
    delete object;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSuggestInteractionProfileBindings(
    XrInstance instance, const XrInteractionProfileSuggestedBinding* suggestedBindings
) {
    XrInstance_T* owner = getInstance(instance);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (suggestedBindings == nullptr ||
        suggestedBindings->type != XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::string profile;
    if (!pathBelongsToInstance(instance, suggestedBindings->interactionProfile, &profile)) {
        return XR_ERROR_PATH_INVALID;
    }
    if (profile.compare(0, 22, "/interaction_profiles/") != 0) {
        return XR_ERROR_PATH_UNSUPPORTED;
    }
    if (suggestedBindings->countSuggestedBindings > 0 &&
        suggestedBindings->suggestedBindings == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::vector<XrActionSuggestedBinding> bindings;
    bindings.reserve(suggestedBindings->countSuggestedBindings);
    for (std::uint32_t index = 0; index < suggestedBindings->countSuggestedBindings; ++index) {
        const auto& binding = suggestedBindings->suggestedBindings[index];
        XrAction_T* action = getAction(binding.action);
        if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
        XrActionSet_T* actionSet = getActionSet(action->owner);
        if (actionSet == nullptr || actionSet->owner != instance) return XR_ERROR_HANDLE_INVALID;
        if (actionSet->attachedSession != XR_NULL_HANDLE) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        if (!pathBelongsToInstance(instance, binding.binding)) return XR_ERROR_PATH_INVALID;
        if (std::any_of(bindings.begin(), bindings.end(), [&](const auto& item) {
                return item.action == binding.action && item.binding == binding.binding;
            })) {
            return XR_ERROR_PATH_UNSUPPORTED;
        }
        bindings.push_back(binding);
    }
    owner->suggestedBindings[suggestedBindings->interactionProfile] = std::move(bindings);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrAttachSessionActionSets(
    XrSession session, const XrSessionActionSetsAttachInfo* attachInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (attachInfo == nullptr || attachInfo->type != XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (owner->actionSetsAttached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (attachInfo->countActionSets == 0 || attachInfo->actionSets == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::unordered_set<XrActionSet> unique;
    std::lock_guard<std::mutex> lock(g_actionMutex);
    for (std::uint32_t index = 0; index < attachInfo->countActionSets; ++index) {
        const XrActionSet handle = attachInfo->actionSets[index];
        const auto found = g_actionSets.find(handle);
        if (found == g_actionSets.end()) return XR_ERROR_HANDLE_INVALID;
        if (found->second->owner != owner->owner) return XR_ERROR_HANDLE_INVALID;
        if (found->second->attachedSession != XR_NULL_HANDLE) {
            return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        }
        if (!unique.insert(handle).second) return XR_ERROR_VALIDATION_FAILURE;
    }
    for (XrActionSet handle : unique) {
        g_actionSets.at(handle)->attachedSession = session;
        owner->attachedActionSets.insert(handle);
    }
    owner->actionSetsAttached = true;
    owner->actionSyncCompleted = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetCurrentInteractionProfile(
    XrSession session, XrPath topLevelUserPath, XrInteractionProfileState* interactionProfile
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (interactionProfile == nullptr ||
        interactionProfile->type != XR_TYPE_INTERACTION_PROFILE_STATE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!validUserPath(owner->owner, topLevelUserPath)) return XR_ERROR_PATH_UNSUPPORTED;
    std::string userPath;
    if (!pathBelongsToInstance(owner->owner, topLevelUserPath, &userPath)) return XR_ERROR_PATH_UNSUPPORTED;
    const int hand = handIndexFromPath(userPath);
    interactionProfile->interactionProfile =
        hand < 0 ? XR_NULL_PATH : currentProfileForHand(owner, hand);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrSyncActions(
    XrSession session, const XrActionsSyncInfo* syncInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (syncInfo == nullptr || syncInfo->type != XR_TYPE_ACTIONS_SYNC_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (!owner->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (owner->state != XR_SESSION_STATE_FOCUSED) return XR_SESSION_NOT_FOCUSED;
    if (!owner->actionSetsAttached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (syncInfo->countActiveActionSets > 0 && syncInfo->activeActionSets == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }

    std::vector<XrActiveActionSet> active;
    active.reserve(syncInfo->countActiveActionSets);
    std::unordered_set<XrActionSet> unique;
    for (std::uint32_t index = 0; index < syncInfo->countActiveActionSets; ++index) {
        const auto& item = syncInfo->activeActionSets[index];
        XrActionSet_T* actionSet = getActionSet(item.actionSet);
        if (actionSet == nullptr) return XR_ERROR_HANDLE_INVALID;
        if (actionSet->attachedSession != session ||
            owner->attachedActionSets.find(item.actionSet) == owner->attachedActionSets.end()) {
            return XR_ERROR_ACTIONSET_NOT_ATTACHED;
        }
        if (!unique.insert(item.actionSet).second) return XR_ERROR_VALIDATION_FAILURE;
        if (item.subactionPath != XR_NULL_PATH &&
            !validUserPath(owner->owner, item.subactionPath)) return XR_ERROR_PATH_INVALID;
        active.push_back(item);
    }
#if defined(__ANDROID__)
    // The runtime is loaded into the game process, while QuestPhoneXR publishes
    // controller snapshots from its own process. Pull one fresh snapshot at
    // the action synchronization boundary, not once per individual action.
    refreshExternalControllerInput(getInstance(owner->owner));
#endif
    owner->activeActionSets = std::move(active);
    for (int hand = 0; hand < 2; ++hand) {
        const EndXrControllerInput input = readControllerInput(hand);
        auto& synchronized = owner->synchronizedControllerInputs[static_cast<std::size_t>(hand)];
        synchronized.active = input.active;
        synchronized.trigger = input.trigger;
        synchronized.grip = input.grip;
        synchronized.stickX = input.stickX;
        synchronized.stickY = input.stickY;
        synchronized.buttons = input.buttons;
        synchronized.poseActive = input.poseActive;
        synchronized.gripPose = input.gripPose;
    }
    rebuildActionStateSnapshots(owner);
    owner->actionSyncCompleted = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateBoolean(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateBoolean* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_BOOLEAN) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_BOOLEAN_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.booleanValue ? XR_TRUE : XR_FALSE;
    state->changedSinceLastSync = input.booleanChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.booleanLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateFloat(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateFloat* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_FLOAT) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_FLOAT_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.scalarValue;
    state->changedSinceLastSync = input.scalarChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.scalarLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStateVector2f(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStateVector2f* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_VECTOR2F) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_VECTOR2F_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    const auto input = getActionStateSnapshot(owner, getInfo->action, getInfo->subactionPath);
    state->currentState = input.vectorValue;
    state->changedSinceLastSync = input.vectorChanged ? XR_TRUE : XR_FALSE;
    state->lastChangeTime = input.vectorLastChangeTime;
    state->isActive = input.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetActionStatePose(
    XrSession session, const XrActionStateGetInfo* getInfo, XrActionStatePose* state
) {
    XrSession_T* owner = getSession(session);
    if (state == nullptr || state->type != XR_TYPE_ACTION_STATE_POSE) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = nullptr;
    XrResult result = validateActionForSession(
        owner, getInfo, XR_ACTION_TYPE_POSE_INPUT, &action
    );
    if (XR_FAILED(result)) return result;
    state->isActive = resolveActionPose(owner, action, getInfo->subactionPath).active
        ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrCreateActionSpace(
    XrSession session, const XrActionSpaceCreateInfo* createInfo, XrSpace* space
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (createInfo == nullptr || space == nullptr ||
        createInfo->type != XR_TYPE_ACTION_SPACE_CREATE_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    *space = XR_NULL_HANDLE;
    XrAction_T* action = getAction(createInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_POSE_INPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session ||
        owner->attachedActionSets.find(action->owner) == owner->attachedActionSets.end()) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, createInfo->subactionPath)) {
        return XR_ERROR_PATH_INVALID;
    }
    auto* created = new (std::nothrow) XrSpace_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->owner = session;
    created->isActionSpace = true;
    created->action = createInfo->action;
    created->actionSubactionPath = createInfo->subactionPath;
    created->poseInReferenceSpace = createInfo->poseInActionSpace;
    const XrSpace handle = makeSpaceHandle(created);
    {
        std::lock_guard<std::mutex> lock(g_spaceMutex);
        g_spaces.emplace(handle, created);
    }
    owner->spaces.insert(handle);
    {
        std::lock_guard<std::mutex> lock(g_actionMutex);
        const auto found = g_actions.find(createInfo->action);
        if (found != g_actions.end()) found->second->spaces.insert(handle);
    }
    *space = handle;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrEnumerateBoundSourcesForAction(
    XrSession session, const XrBoundSourcesForActionEnumerateInfo* enumerateInfo,
    std::uint32_t capacity, std::uint32_t* count, XrPath* sources
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (enumerateInfo == nullptr ||
        enumerateInfo->type != XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO ||
        count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    XrAction_T* action = getAction(enumerateInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    *count = 0;
    if (capacity > 0 && sources == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    XrInstance_T* instance = getInstance(owner->owner);
    if (instance == nullptr) return XR_ERROR_HANDLE_INVALID;
    std::vector<XrPath> activeSources;
    for (int hand = 0; hand < 2; ++hand) {
        const XrPath profile = currentProfileForHand(owner, hand);
        const auto found = instance->suggestedBindings.find(profile);
        if (profile == XR_NULL_PATH || found == instance->suggestedBindings.end()) continue;
        const XrPath handPath = hand == 0
            ? (instance->pathHandles.count("/user/hand/left") ? instance->pathHandles.at("/user/hand/left") : XR_NULL_PATH)
            : (instance->pathHandles.count("/user/hand/right") ? instance->pathHandles.at("/user/hand/right") : XR_NULL_PATH);
        for (const auto& binding : found->second) {
            if (binding.action != enumerateInfo->action) continue;
            std::string path;
            if (pathBelongsToInstance(owner->owner, binding.binding, &path) &&
                handIndexFromPath(path) == hand && handPath != XR_NULL_PATH &&
                std::find(activeSources.begin(), activeSources.end(), binding.binding) == activeSources.end()) {
                activeSources.push_back(binding.binding);
            }
        }
    }
    *count = static_cast<std::uint32_t>(activeSources.size());
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < activeSources.size()) return XR_ERROR_SIZE_INSUFFICIENT;
    for (std::uint32_t i = 0; i < *count; ++i) sources[i] = activeSources[i];
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInputSourceLocalizedName(
    XrSession session, const XrInputSourceLocalizedNameGetInfo* getInfo,
    std::uint32_t capacity, std::uint32_t* count, char* buffer
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (getInfo == nullptr ||
        getInfo->type != XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO ||
        count == nullptr || getInfo->whichComponents == 0 ||
        (getInfo->whichComponents & ~(XR_INPUT_SOURCE_LOCALIZED_NAME_USER_PATH_BIT |
                                     XR_INPUT_SOURCE_LOCALIZED_NAME_INTERACTION_PROFILE_BIT |
                                     XR_INPUT_SOURCE_LOCALIZED_NAME_COMPONENT_BIT)) != 0) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::string path;
    if (!pathBelongsToInstance(owner->owner, getInfo->sourcePath, &path)) {
        return XR_ERROR_PATH_INVALID;
    }
    const std::uint32_t required = static_cast<std::uint32_t>(path.size() + 1);
    *count = required;
    if (capacity == 0) return XR_SUCCESS;
    if (buffer == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < required) return XR_ERROR_SIZE_INSUFFICIENT;
    std::memcpy(buffer, path.c_str(), required);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrApplyHapticFeedback(
    XrSession session, const XrHapticActionInfo* actionInfo,
    const XrHapticBaseHeader* feedback
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionInfo == nullptr || actionInfo->type != XR_TYPE_HAPTIC_ACTION_INFO ||
        feedback == nullptr || feedback->type != XR_TYPE_HAPTIC_VIBRATION) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(actionInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, actionInfo->subactionPath)) return XR_ERROR_PATH_INVALID;
    return XR_ERROR_FEATURE_UNSUPPORTED;
}

XRAPI_ATTR XrResult XRAPI_CALL endxrStopHapticFeedback(
    XrSession session, const XrHapticActionInfo* actionInfo
) {
    XrSession_T* owner = getSession(session);
    if (owner == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (actionInfo == nullptr || actionInfo->type != XR_TYPE_HAPTIC_ACTION_INFO) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    XrAction_T* action = getAction(actionInfo->action);
    if (action == nullptr) return XR_ERROR_HANDLE_INVALID;
    if (action->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    XrActionSet_T* actionSet = getActionSet(action->owner);
    if (actionSet == nullptr || actionSet->attachedSession != session) {
        return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    if (!validActionSubactionPath(action, actionInfo->subactionPath)) return XR_ERROR_PATH_INVALID;
    return XR_ERROR_FEATURE_UNSUPPORTED;
}

#define ENDXR_PROC(name, fn) {name, reinterpret_cast<PFN_xrVoidFunction>(fn)}
struct ProcEntry { const char* name; PFN_xrVoidFunction function; };
const std::array<ProcEntry, 54> kProcTable{{
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
    ENDXR_PROC("xrWaitSwapchainImage", endxrWaitSwapchainImage),
    ENDXR_PROC("xrStringToPath", endxrStringToPath),
    ENDXR_PROC("xrPathToString", endxrPathToString),
    ENDXR_PROC("xrCreateActionSet", endxrCreateActionSet),
    ENDXR_PROC("xrDestroyActionSet", endxrDestroyActionSet),
    ENDXR_PROC("xrCreateAction", endxrCreateAction),
    ENDXR_PROC("xrDestroyAction", endxrDestroyAction),
    ENDXR_PROC("xrSuggestInteractionProfileBindings", endxrSuggestInteractionProfileBindings),
    ENDXR_PROC("xrAttachSessionActionSets", endxrAttachSessionActionSets),
    ENDXR_PROC("xrGetCurrentInteractionProfile", endxrGetCurrentInteractionProfile),
    ENDXR_PROC("xrGetActionStateBoolean", endxrGetActionStateBoolean),
    ENDXR_PROC("xrGetActionStateFloat", endxrGetActionStateFloat),
    ENDXR_PROC("xrGetActionStateVector2f", endxrGetActionStateVector2f),
    ENDXR_PROC("xrGetActionStatePose", endxrGetActionStatePose),
    ENDXR_PROC("xrSyncActions", endxrSyncActions),
    ENDXR_PROC("xrEnumerateBoundSourcesForAction", endxrEnumerateBoundSourcesForAction),
    ENDXR_PROC("xrGetInputSourceLocalizedName", endxrGetInputSourceLocalizedName),
    ENDXR_PROC("xrCreateActionSpace", endxrCreateActionSpace),
    ENDXR_PROC("xrApplyHapticFeedback", endxrApplyHapticFeedback),
    ENDXR_PROC("xrStopHapticFeedback", endxrStopHapticFeedback)
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

extern "C" __attribute__((visibility("default"))) void endxrSetControllerInput(
    std::uint32_t hand, std::uint32_t active, float trigger, float grip,
    float stickX, float stickY, std::uint32_t buttons
) {
    if (hand > 1) return;
    auto clean = [](float value) { return std::isfinite(value) ? std::clamp(value, -1.0f, 1.0f) : 0.0f; };
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto& input = g_controllerInputs[hand];
    input.active = active != 0;
    input.trigger = std::clamp(clean(trigger), 0.0f, 1.0f);
    input.grip = std::clamp(clean(grip), 0.0f, 1.0f);
    input.stickX = clean(stickX);
    input.stickY = clean(stickY);
    input.buttons = buttons;
    input.updatedAt = std::chrono::steady_clock::now();
}
extern "C" __attribute__((visibility("default"))) void endxrClearControllerInput() {
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    g_controllerInputs = {};
}
extern "C" __attribute__((visibility("default"))) void endxrSetHandPose(
    std::uint32_t hand, std::uint32_t active,
    float x, float y, float z, float qx, float qy, float qz, float qw
) {
    if (hand > 1) return;
    const float values[] = {x, y, z, qx, qy, qz, qw};
    for (float value : values) if (!std::isfinite(value)) active = 0;
    const double magnitude = std::sqrt(
        static_cast<double>(qx) * qx + static_cast<double>(qy) * qy +
        static_cast<double>(qz) * qz + static_cast<double>(qw) * qw
    );
    if (!std::isfinite(magnitude) || magnitude < 0.5 || magnitude > 1.5 ||
        std::abs(x) > 8.0f || std::abs(y) > 6.0f || z > -0.25f || z < -6.0f) {
        active = 0;
    }
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    auto& input = g_controllerInputs[hand];
    input.poseActive = active != 0;
    if (input.poseActive) {
        input.gripPose.orientation = {
            static_cast<float>(qx / magnitude),
            static_cast<float>(qy / magnitude),
            static_cast<float>(qz / magnitude),
            static_cast<float>(qw / magnitude)
        };
        input.gripPose.position = {x, y, z};
    } else {
        input.gripPose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    }
}
extern "C" __attribute__((visibility("default"))) void endxrClearHandPoses() {
    std::lock_guard<std::mutex> lock(g_controllerInputMutex);
    for (auto& input : g_controllerInputs) {
        input.poseActive = false;
        input.gripPose = {{0.0f, 0.0f, 0.0f, 1.0f}, {0.0f, 0.0f, 0.0f}};
    }
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

extern "C" __attribute__((visibility("default"))) void endxrClearHeadOrientation() {
    std::lock_guard<std::mutex> lock(g_headPoseMutex);
    g_headOrientation = {0.0f, 0.0f, 0.0f, 1.0f};
    g_hasHeadOrientation = false;
}

extern "C" __attribute__((visibility("default"))) std::uint32_t
endxrGetLastGlesError() { return g_lastGlesError; }
