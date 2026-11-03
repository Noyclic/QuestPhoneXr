#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>

#include <algorithm>
#include <chrono>
#include <dlfcn.h>
#include <cstdio>
#include <cmath>
#include <iomanip>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {
std::mutex loaderMutex;
bool loaderInitialized = false;
XrResult cachedLoaderInitResult = XR_ERROR_INITIALIZATION_FAILED;

const char* xrResultName(XrResult result) {
    switch (result) {
        case XR_SUCCESS: return "XR_SUCCESS";
        case XR_TIMEOUT_EXPIRED: return "XR_TIMEOUT_EXPIRED";
        case XR_SESSION_LOSS_PENDING: return "XR_SESSION_LOSS_PENDING";
        case XR_ERROR_VALIDATION_FAILURE: return "XR_ERROR_VALIDATION_FAILURE";
        case XR_ERROR_RUNTIME_FAILURE: return "XR_ERROR_RUNTIME_FAILURE";
        case XR_ERROR_RUNTIME_UNAVAILABLE: return "XR_ERROR_RUNTIME_UNAVAILABLE";
        case XR_ERROR_OUT_OF_MEMORY: return "XR_ERROR_OUT_OF_MEMORY";
        case XR_ERROR_API_VERSION_UNSUPPORTED: return "XR_ERROR_API_VERSION_UNSUPPORTED";
        case XR_ERROR_INITIALIZATION_FAILED: return "XR_ERROR_INITIALIZATION_FAILED";
        case XR_ERROR_FUNCTION_UNSUPPORTED: return "XR_ERROR_FUNCTION_UNSUPPORTED";
        case XR_ERROR_FEATURE_UNSUPPORTED: return "XR_ERROR_FEATURE_UNSUPPORTED";
        case XR_ERROR_EXTENSION_NOT_PRESENT: return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case XR_ERROR_LIMIT_REACHED: return "XR_ERROR_LIMIT_REACHED";
        case XR_ERROR_SIZE_INSUFFICIENT: return "XR_ERROR_SIZE_INSUFFICIENT";
        case XR_ERROR_HANDLE_INVALID: return "XR_ERROR_HANDLE_INVALID";
        case XR_ERROR_INSTANCE_LOST: return "XR_ERROR_INSTANCE_LOST";
        case XR_ERROR_SESSION_LOST: return "XR_ERROR_SESSION_LOST";
        case XR_ERROR_SYSTEM_INVALID: return "XR_ERROR_SYSTEM_INVALID";
        case XR_ERROR_PATH_INVALID: return "XR_ERROR_PATH_INVALID";
        case XR_ERROR_CALL_ORDER_INVALID: return "XR_ERROR_CALL_ORDER_INVALID";
        case XR_ERROR_GRAPHICS_DEVICE_INVALID: return "XR_ERROR_GRAPHICS_DEVICE_INVALID";
        default: return "XR_RESULT_UNKNOWN";
    }
}

std::string jsonEscape(const std::string& value) {
    std::ostringstream out;
    for (unsigned char ch : value) {
        switch (ch) {
            case '"': out << "\\\""; break;
            case '\\': out << "\\\\"; break;
            case '\n': out << "\\n"; break;
            case '\r': out << "\\r"; break;
            case '\t': out << "\\t"; break;
            default:
                if (ch < 0x20) {
                    static const char* hex = "0123456789abcdef";
                    out << "\\u00" << hex[(ch >> 4) & 0x0f] << hex[ch & 0x0f];
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

std::string makeReport(
    const char* status,
    XrResult loaderInit,
    XrResult enumeration,
    XrResult instanceCreate,
    XrResult getSystem,
    const std::string& systemName,
    const std::vector<XrExtensionProperties>& extensions,
    const std::string& message
) {
    std::ostringstream out;
    out << R"({"status":")" << status
        << R"(","loaderInitResult":)" << static_cast<int>(loaderInit)
        << R"(,"extensionEnumerationResult":)" << static_cast<int>(enumeration)
        << R"(,"instanceCreateResult":)" << static_cast<int>(instanceCreate)
        << R"(,"getSystemResult":)" << static_cast<int>(getSystem)
        << R"(,"systemName":")" << jsonEscape(systemName)
        << R"(","message":")" << jsonEscape(message)
        << R"(","extensions":[)";
    for (size_t i = 0; i < extensions.size(); ++i) {
        if (i != 0) out << ",";
        out << "\"" << jsonEscape(extensions[i].extensionName) << "\"";
    }
    out << "]}";
    return out.str();
}

struct FrameLoopSubmission {
    const char* status;
    XrResult result;
    std::string message;
    bool submitted;
};

XrResult waitForSessionState(
    XrInstance instance,
    XrSession session,
    XrSessionState target,
    std::chrono::milliseconds timeout
) {
    const auto deadline = std::chrono::steady_clock::now() + timeout;
    while (std::chrono::steady_clock::now() < deadline) {
        XrEventDataBuffer event{};
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        const XrResult pollResult = xrPollEvent(instance, &event);
        if (pollResult == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto* changed =
                    reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
                if (changed->session == session) {
                    if (changed->state == target) return XR_SUCCESS;
                    if (changed->state == XR_SESSION_STATE_EXITING ||
                        changed->state == XR_SESSION_STATE_LOSS_PENDING) {
                        return XR_ERROR_SESSION_LOST;
                    }
                }
            }
        } else if (pollResult != XR_EVENT_UNAVAILABLE) {
            return pollResult;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }
    return XR_TIMEOUT_EXPIRED;
}

FrameLoopSubmission submitGlesFrameLoop(
    XrInstance instance,
    XrSession session,
    XrSwapchain swapchain,
    const std::vector<XrSwapchainImageOpenGLESKHR>& swapchainImages,
    uint32_t viewCount,
    uint32_t width,
    uint32_t height
) {
    constexpr uint32_t kTargetFrames = 30;
    constexpr uint32_t kMaxFrameAttempts = 90;
    constexpr auto kSessionReadyTimeout = std::chrono::milliseconds(5000);
    constexpr auto kOverallFrameTimeout = std::chrono::seconds(8);

    XrSpace space = XR_NULL_HANDLE;
    bool sessionBegun = false;
    bool frameBegun = false;
    bool imageAcquired = false;
    uint32_t imageIndex = 0;
    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;
    uint32_t submittedFrames = 0;
    std::chrono::steady_clock::time_point frameLoopStarted{};

    auto stopSession = [&]() {
        if (sessionBegun) {
            (void)xrRequestExitSession(session);
            const XrResult stopping = waitForSessionState(
                instance, session, XR_SESSION_STATE_STOPPING, std::chrono::milliseconds(1500)
            );
            if (stopping == XR_SUCCESS) (void)xrEndSession(session);
            sessionBegun = false;
        }
        if (space != XR_NULL_HANDLE) {
            (void)xrDestroySpace(space);
            space = XR_NULL_HANDLE;
        }
    };

    auto fail = [&](const char* status, XrResult result, const std::string& message) {
        if (imageAcquired) {
            XrSwapchainImageReleaseInfo releaseInfo{};
            releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
            (void)xrReleaseSwapchainImage(swapchain, &releaseInfo);
            imageAcquired = false;
        }
        if (frameBegun) {
            XrFrameEndInfo endInfo{};
            endInfo.type = XR_TYPE_FRAME_END_INFO;
            endInfo.displayTime = frameState.predictedDisplayTime;
            endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            (void)xrEndFrame(session, &endInfo);
            frameBegun = false;
        }
        stopSession();
        std::ostringstream details;
        details << message << " Frames submitted: " << submittedFrames << "/" << kTargetFrames << ".";
        return FrameLoopSubmission{status, result, details.str(), false};
    };

    const XrResult readyResult = waitForSessionState(
        instance, session, XR_SESSION_STATE_READY, kSessionReadyTimeout
    );
    if (readyResult != XR_SUCCESS) {
        return fail(
            "SESSION_READY_TIMEOUT", readyResult,
            "Monado did not move the session to READY within 5 seconds."
        );
    }

    XrSessionBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
    beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    XrResult result = xrBeginSession(session, &beginInfo);
    if (result != XR_SUCCESS) {
        return fail("SESSION_BEGIN_FAILED", result, "xrBeginSession failed.");
    }
    sessionBegun = true;

    XrReferenceSpaceCreateInfo spaceInfo{};
    spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    result = xrCreateReferenceSpace(session, &spaceInfo, &space);
    if (result != XR_SUCCESS) {
        return fail("REFERENCE_SPACE_FAILED", result, "Could not create the LOCAL reference space.");
    }

    // Session startup and READY waits are excluded from the frame-loop deadline.
    frameLoopStarted = std::chrono::steady_clock::now();
    uint32_t attempts = 0;
    uint32_t validTrackingSamples = 0;
    bool haveTrackingBaseline = false;
    XrPosef firstTrackedPose{};
    double maxHeadPositionDeltaMeters = 0.0;
    double maxHeadRotationDeltaDegrees = 0.0;
    while (submittedFrames < kTargetFrames && attempts < kMaxFrameAttempts) {
        ++attempts;
        if (std::chrono::steady_clock::now() - frameLoopStarted > kOverallFrameTimeout) {
            return fail(
                "SUSTAINED_FRAME_TIMEOUT", XR_TIMEOUT_EXPIRED,
                "The compositor did not accept the target frame batch within 8 seconds."
            );
        }

        XrEventDataBuffer event{};
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        XrResult pollResult = xrPollEvent(instance, &event);
        while (pollResult == XR_SUCCESS) {
            if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto* changed =
                    reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
                if (changed->session == session &&
                    (changed->state == XR_SESSION_STATE_EXITING ||
                     changed->state == XR_SESSION_STATE_LOSS_PENDING ||
                     changed->state == XR_SESSION_STATE_STOPPING)) {
                    return fail(
                        "SESSION_STOPPED_DURING_FRAME_LOOP", XR_ERROR_SESSION_LOST,
                        "The runtime stopped the session before the sustained frame batch completed."
                    );
                }
            }
            event = {};
            event.type = XR_TYPE_EVENT_DATA_BUFFER;
            pollResult = xrPollEvent(instance, &event);
        }
        if (pollResult != XR_EVENT_UNAVAILABLE) {
            return fail("POLL_EVENTS_FAILED", pollResult, "xrPollEvent failed during the frame loop.");
        }

        XrFrameWaitInfo waitInfo{};
        waitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
        result = xrWaitFrame(session, &waitInfo, &frameState);
        if (result != XR_SUCCESS) {
            return fail("WAIT_FRAME_FAILED", result, "xrWaitFrame failed during the sustained frame loop.");
        }

        XrFrameBeginInfo frameBeginInfo{};
        frameBeginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
        result = xrBeginFrame(session, &frameBeginInfo);
        if (result != XR_SUCCESS) {
            return fail("BEGIN_FRAME_FAILED", result, "xrBeginFrame failed during the sustained frame loop.");
        }
        frameBegun = true;

        if (frameState.shouldRender == XR_FALSE) {
            XrFrameEndInfo emptyEndInfo{};
            emptyEndInfo.type = XR_TYPE_FRAME_END_INFO;
            emptyEndInfo.displayTime = frameState.predictedDisplayTime;
            emptyEndInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            result = xrEndFrame(session, &emptyEndInfo);
            frameBegun = false;
            if (result != XR_SUCCESS) {
                return fail("END_EMPTY_FRAME_FAILED", result, "Runtime rejected a non-rendered frame.");
            }
            continue;
        }

        std::vector<XrView> locatedViews(viewCount);
        for (auto& view : locatedViews) {
            view.type = XR_TYPE_VIEW;
            view.next = nullptr;
        }
        XrViewState viewState{};
        viewState.type = XR_TYPE_VIEW_STATE;
        XrViewLocateInfo locateInfo{};
        locateInfo.type = XR_TYPE_VIEW_LOCATE_INFO;
        locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        locateInfo.displayTime = frameState.predictedDisplayTime;
        locateInfo.space = space;
        uint32_t locatedViewCount = viewCount;
        result = xrLocateViews(
            session, &locateInfo, &viewState, viewCount, &locatedViewCount, locatedViews.data()
        );
        if (result != XR_SUCCESS || locatedViewCount != viewCount) {
            return fail(
                "LOCATE_VIEWS_FAILED", result,
                "xrLocateViews did not return the expected stereo eye poses."
            );
        }

        const bool orientationValid =
            (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0;
        const bool positionValid =
            (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0;
        if (orientationValid && positionValid) {
            const XrPosef& pose = locatedViews[0].pose;
            ++validTrackingSamples;
            if (!haveTrackingBaseline) {
                firstTrackedPose = pose;
                haveTrackingBaseline = true;
            } else {
                const double dx = static_cast<double>(pose.position.x - firstTrackedPose.position.x);
                const double dy = static_cast<double>(pose.position.y - firstTrackedPose.position.y);
                const double dz = static_cast<double>(pose.position.z - firstTrackedPose.position.z);
                maxHeadPositionDeltaMeters = std::max(
                    maxHeadPositionDeltaMeters, std::sqrt(dx * dx + dy * dy + dz * dz)
                );

                const auto& a = firstTrackedPose.orientation;
                const auto& b = pose.orientation;
                const double dot = std::abs(
                    static_cast<double>(a.x) * b.x +
                    static_cast<double>(a.y) * b.y +
                    static_cast<double>(a.z) * b.z +
                    static_cast<double>(a.w) * b.w
                );
                const double clampedDot = std::clamp(dot, 0.0, 1.0);
                const double angleDegrees = 2.0 * std::acos(clampedDot) * 180.0 / 3.14159265358979323846;
                maxHeadRotationDeltaDegrees = std::max(maxHeadRotationDeltaDegrees, angleDegrees);
            }
        }

        XrSwapchainImageAcquireInfo acquireInfo{};
        acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
        result = xrAcquireSwapchainImage(swapchain, &acquireInfo, &imageIndex);
        if (result != XR_SUCCESS) {
            return fail("ACQUIRE_IMAGE_FAILED", result, "xrAcquireSwapchainImage failed.");
        }
        imageAcquired = true;

        XrSwapchainImageWaitInfo imageWaitInfo{};
        imageWaitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
        imageWaitInfo.timeout = 250'000'000;
        result = xrWaitSwapchainImage(swapchain, &imageWaitInfo);
        if (result != XR_SUCCESS) {
            return fail("WAIT_IMAGE_FAILED", result, "xrWaitSwapchainImage did not become ready within 250 ms.");
        }
        if (imageIndex >= swapchainImages.size()) {
            return fail(
                "SWAPCHAIN_IMAGE_INDEX_INVALID", XR_ERROR_INDEX_OUT_OF_RANGE,
                "Runtime returned a swapchain image index outside the enumerated image list."
            );
        }

        GLuint framebuffer = 0;
        glGenFramebuffers(1, &framebuffer);
        glBindFramebuffer(GL_FRAMEBUFFER, framebuffer);
        for (uint32_t eye = 0; eye < viewCount; ++eye) {
            glFramebufferTextureLayer(
                GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
                swapchainImages[imageIndex].image, 0, static_cast<GLint>(eye)
            );
            if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                glDeleteFramebuffers(1, &framebuffer);
                return fail(
                    "GLES_FRAMEBUFFER_INCOMPLETE", XR_ERROR_GRAPHICS_DEVICE_INVALID,
                    "The runtime swapchain texture array could not be attached for both eyes."
                );
            }
            glViewport(0, 0, static_cast<GLsizei>(width), static_cast<GLsizei>(height));
            glClearColor(eye == 0 ? 0.06f : 0.02f, 0.18f, eye == 0 ? 0.32f : 0.24f, 1.0f);
            glClear(GL_COLOR_BUFFER_BIT);
        }
        glBindFramebuffer(GL_FRAMEBUFFER, 0);
        glDeleteFramebuffers(1, &framebuffer);
        glFinish();
        const GLenum glError = glGetError();
        if (glError != GL_NO_ERROR) {
            return fail(
                "GLES_RENDER_FAILED", XR_ERROR_GRAPHICS_DEVICE_INVALID,
                "OpenGL ES reported an error while clearing the two swapchain array layers."
            );
        }

        XrSwapchainImageReleaseInfo releaseInfo{};
        releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
        result = xrReleaseSwapchainImage(swapchain, &releaseInfo);
        imageAcquired = false;
        if (result != XR_SUCCESS) {
            return fail("RELEASE_IMAGE_FAILED", result, "xrReleaseSwapchainImage failed.");
        }

        std::vector<XrCompositionLayerProjectionView> projectionViews(viewCount);
        for (uint32_t eye = 0; eye < viewCount; ++eye) {
            auto& projectionView = projectionViews[eye];
            projectionView.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
            projectionView.pose = locatedViews[eye].pose;
            projectionView.fov = locatedViews[eye].fov;
            projectionView.subImage.swapchain = swapchain;
            projectionView.subImage.imageRect.offset = {0, 0};
            projectionView.subImage.imageRect.extent = {
                static_cast<int32_t>(width), static_cast<int32_t>(height)
            };
            projectionView.subImage.imageArrayIndex = eye;
        }

        XrCompositionLayerProjection projectionLayer{};
        projectionLayer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
        projectionLayer.space = space;
        projectionLayer.viewCount = viewCount;
        projectionLayer.views = projectionViews.data();
        const XrCompositionLayerBaseHeader* layers[] = {
            reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projectionLayer)
        };
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        endInfo.layerCount = 1;
        endInfo.layers = layers;
        result = xrEndFrame(session, &endInfo);
        frameBegun = false;
        if (result != XR_SUCCESS) {
            return fail("END_FRAME_FAILED", result, "xrEndFrame rejected a stereo projection frame.");
        }
        ++submittedFrames;
    }

    stopSession();
    if (submittedFrames != kTargetFrames) {
        std::ostringstream details;
        details << "Only " << submittedFrames << " of " << kTargetFrames
                << " stereo frames were submitted before the attempt limit.";
        return FrameLoopSubmission{"SUSTAINED_FRAME_LIMIT_REACHED", XR_TIMEOUT_EXPIRED, details.str(), false};
    }

    const auto elapsedMs = std::chrono::duration_cast<std::chrono::milliseconds>(
        std::chrono::steady_clock::now() - frameLoopStarted
    ).count();
    std::ostringstream details;
    details << "A continuous OpenXR session submitted " << submittedFrames
            << " complete stereo GLES frames through xrEndFrame in "
            << elapsedMs << " ms. Valid head-pose samples: " << validTrackingSamples
            << "/" << submittedFrames << "; max delta from initial pose: "
            << std::fixed << std::setprecision(3) << maxHeadPositionDeltaMeters
            << " m / " << std::setprecision(1) << maxHeadRotationDeltaDegrees
            << " deg. This validates runtime pose delivery during a short frame loop, not yet the app's permanent renderer.";
    return FrameLoopSubmission{
        "SUSTAINED_FRAMES_SUBMITTED_GLES", XR_SUCCESS, details.str(), true
    };
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrLoaderProbe_nativeProbe(
    JNIEnv* env,
    jobject /* thiz */,
    jobject activityContext
) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr || activityContext == nullptr) {
        return env->NewStringUTF(R"({"status":"ANDROID_CONTEXT_UNAVAILABLE","message":"JNI JavaVM or Android Activity is unavailable","extensions":[]})");
    }

    jobject applicationContext = activityContext;
    jclass contextClass = env->GetObjectClass(activityContext);
    jmethodID getApplicationContext = env->GetMethodID(
        contextClass, "getApplicationContext", "()Landroid/content/Context;"
    );
    if (getApplicationContext != nullptr) {
        jobject app = env->CallObjectMethod(activityContext, getApplicationContext);
        if (app != nullptr) applicationContext = app;
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    XrResult initResult = XR_SUCCESS;
    {
        std::lock_guard<std::mutex> lock(loaderMutex);
        if (!loaderInitialized) {
            PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
            const XrResult procResult = xrGetInstanceProcAddr(
                XR_NULL_HANDLE,
                "xrInitializeLoaderKHR",
                reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)
            );
            if (procResult != XR_SUCCESS || initializeLoader == nullptr) {
                cachedLoaderInitResult = procResult;
            } else {
                XrLoaderInitInfoAndroidKHR initInfo{};
                initInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
                initInfo.next = nullptr;
                initInfo.applicationVM = vm;
                initInfo.applicationContext = applicationContext;
                cachedLoaderInitResult = initializeLoader(
                    reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&initInfo)
                );
                loaderInitialized = cachedLoaderInitResult == XR_SUCCESS;
            }
        }
        initResult = cachedLoaderInitResult;
    }

    if (initResult != XR_SUCCESS) {
        if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
        return env->NewStringUTF(makeReport(
            "LOADER_INITIALIZATION_FAILED", initResult, XR_ERROR_INITIALIZATION_FAILED,
            XR_ERROR_INITIALIZATION_FAILED, XR_ERROR_INITIALIZATION_FAILED, "", {},
            "xrInitializeLoaderKHR did not complete successfully."
        ).c_str());
    }

    uint32_t extensionCount = 0;
    XrResult enumResult = xrEnumerateInstanceExtensionProperties(nullptr, 0, &extensionCount, nullptr);
    if (enumResult != XR_SUCCESS) {
        if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
        return env->NewStringUTF(makeReport(
            "RUNTIME_ENUMERATION_FAILED", initResult, enumResult,
            XR_ERROR_INITIALIZATION_FAILED, XR_ERROR_INITIALIZATION_FAILED, "", {},
            "OpenXR loader initialized, but the active runtime did not provide extension enumeration."
        ).c_str());
    }

    std::vector<XrExtensionProperties> extensions(extensionCount);
    for (auto& extension : extensions) {
        extension.type = XR_TYPE_EXTENSION_PROPERTIES;
        extension.next = nullptr;
    }
    if (extensionCount > 0) {
        enumResult = xrEnumerateInstanceExtensionProperties(nullptr, extensionCount, &extensionCount, extensions.data());
        if (enumResult != XR_SUCCESS) {
            if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
            return env->NewStringUTF(makeReport(
                "RUNTIME_ENUMERATION_FAILED", initResult, enumResult,
                XR_ERROR_INITIALIZATION_FAILED, XR_ERROR_INITIALIZATION_FAILED, "", {},
                "The runtime extension list could not be read."
            ).c_str());
        }
        extensions.resize(extensionCount);
    }

    const bool androidCreateAvailable = std::any_of(
        extensions.begin(), extensions.end(), [](const XrExtensionProperties& extension) {
            return std::string(extension.extensionName) == XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME;
        }
    );
    if (!androidCreateAvailable) {
        if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
        return env->NewStringUTF(makeReport(
            "ANDROID_INSTANCE_EXTENSION_UNAVAILABLE", initResult, enumResult,
            XR_ERROR_EXTENSION_NOT_PRESENT, XR_ERROR_INITIALIZATION_FAILED, "", extensions,
            "Runtime отвечает на перечисление расширений, но не объявляет XR_KHR_android_create_instance; создать Android OpenXR instance нельзя."
        ).c_str());
    }

    const char* enabledExtensions[] = { XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME };
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.next = nullptr;
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = activityContext;

    XrInstanceCreateInfo createInfo{};
    createInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    createInfo.next = &androidInfo;
    const char* appName = "QuestPhoneXR";
    const char* engineName = "QuestPhoneXR";
    std::copy(appName, appName + std::char_traits<char>::length(appName),
              createInfo.applicationInfo.applicationName);
    std::copy(engineName, engineName + std::char_traits<char>::length(engineName),
              createInfo.applicationInfo.engineName);
    createInfo.applicationInfo.applicationVersion = 1;
    createInfo.applicationInfo.engineVersion = 1;
    createInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    createInfo.enabledExtensionCount = 1;
    createInfo.enabledExtensionNames = enabledExtensions;

    XrInstance instance = XR_NULL_HANDLE;
    const XrResult createResult = xrCreateInstance(&createInfo, &instance);
    if (createResult != XR_SUCCESS || instance == XR_NULL_HANDLE) {
        if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
        std::ostringstream details;
        details << "xrCreateInstance did not create an Instance"
                << "; result=" << static_cast<int>(createResult)
                << " (" << xrResultName(createResult) << ")"
                << "; instanceHandle=" << (instance == XR_NULL_HANDLE ? "NULL" : "valid")
                << "; requestedApiVersion=1.0.0"
                << "; enabledExtensions=[" << XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME << "]"
                << "; note=XR_ERROR_RUNTIME_UNAVAILABLE means the Khronos loader could not find or load an active OpenXR runtime; successful extension enumeration alone does not prove that Monado was loaded";
        if (createResult == XR_SUCCESS) {
            details << "; runtime returned XR_SUCCESS but left XrInstance null";
        }
        return env->NewStringUTF(makeReport(
            "INSTANCE_CREATION_FAILED", initResult, enumResult, createResult,
            XR_ERROR_INITIALIZATION_FAILED, "", extensions, details.str()
        ).c_str());
    }

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    const XrResult systemResult = xrGetSystem(instance, &systemInfo, &systemId);
    std::string systemName;
    if (systemResult == XR_SUCCESS) {
        XrSystemProperties properties{};
        properties.type = XR_TYPE_SYSTEM_PROPERTIES;
        if (xrGetSystemProperties(instance, systemId, &properties) == XR_SUCCESS) {
            systemName = properties.systemName;
        }
    }
    xrDestroyInstance(instance);
    if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);

    const char* status = systemResult == XR_SUCCESS
        ? "INSTANCE_CREATED_SYSTEM_FOUND"
        : "INSTANCE_CREATED_NO_HMD_SYSTEM";
    const std::string message = systemResult == XR_SUCCESS
        ? "xrCreateInstance и xrGetSystem прошли успешно. Сессия, графический binding, swapchain и compositor пока не проверены."
        : "xrCreateInstance прошёл, но xrGetSystem не нашёл HMD system; полноценный XR запуск не подтверждён.";
    return env->NewStringUTF(makeReport(
        status, initResult, enumResult, createResult, systemResult, systemName, extensions, message
    ).c_str());
}


extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrGlesSessionProbe_nativeProbeSession(
    JNIEnv* env,
    jobject /* thiz */,
    jobject activityContext
) {
    auto report = [](const char* status, const std::string& message, int xrResult = XR_SUCCESS) {
        std::ostringstream out;
        out << R"({"status":")" << status
            << R"(","xrResult":)" << xrResult
            << R"(","message":")" << jsonEscape(message) << R"("})";
        return out.str();
    };

    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr || activityContext == nullptr) {
        return env->NewStringUTF(report("ANDROID_CONTEXT_UNAVAILABLE", "Android Activity is unavailable.").c_str());
    }

    jobject applicationContext = activityContext;
    jclass contextClass = env->GetObjectClass(activityContext);
    jmethodID getApplicationContext = env->GetMethodID(
        contextClass, "getApplicationContext", "()Landroid/content/Context;"
    );
    if (getApplicationContext != nullptr) {
        jobject app = env->CallObjectMethod(activityContext, getApplicationContext);
        if (app != nullptr) applicationContext = app;
        if (env->ExceptionCheck()) env->ExceptionClear();
    }

    XrResult initResult = XR_SUCCESS;
    {
        std::lock_guard<std::mutex> lock(loaderMutex);
        if (!loaderInitialized) {
            PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
            const XrResult procResult = xrGetInstanceProcAddr(
                XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)
            );
            if (procResult != XR_SUCCESS || initializeLoader == nullptr) {
                cachedLoaderInitResult = procResult;
            } else {
                XrLoaderInitInfoAndroidKHR initInfo{};
                initInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
                initInfo.applicationVM = vm;
                initInfo.applicationContext = applicationContext;
                cachedLoaderInitResult = initializeLoader(
                    reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&initInfo)
                );
                loaderInitialized = cachedLoaderInitResult == XR_SUCCESS;
            }
        }
        initResult = cachedLoaderInitResult;
    }
    if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
    if (initResult != XR_SUCCESS) {
        return env->NewStringUTF(report("LOADER_INITIALIZATION_FAILED", "xrInitializeLoaderKHR failed.", initResult).c_str());
    }

    uint32_t extensionCount = 0;
    XrResult result = xrEnumerateInstanceExtensionProperties(nullptr, 0, &extensionCount, nullptr);
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(report("RUNTIME_ENUMERATION_FAILED", "Runtime extension enumeration failed.", result).c_str());
    }
    std::vector<XrExtensionProperties> extensions(extensionCount);
    for (auto& extension : extensions) {
        extension.type = XR_TYPE_EXTENSION_PROPERTIES;
        extension.next = nullptr;
    }
    if (extensionCount > 0) {
        result = xrEnumerateInstanceExtensionProperties(nullptr, extensionCount, &extensionCount, extensions.data());
        if (result != XR_SUCCESS) {
            return env->NewStringUTF(report("RUNTIME_ENUMERATION_FAILED", "Runtime extension list could not be read.", result).c_str());
        }
        extensions.resize(extensionCount);
    }

    const auto hasExtension = [&extensions](const char* name) {
        return std::any_of(extensions.begin(), extensions.end(), [name](const XrExtensionProperties& extension) {
            return std::string(extension.extensionName) == name;
        });
    };
    if (!hasExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) {
        return env->NewStringUTF(report("ANDROID_INSTANCE_EXTENSION_UNAVAILABLE", "Runtime does not advertise XR_KHR_android_create_instance.", XR_ERROR_EXTENSION_NOT_PRESENT).c_str());
    }
    if (!hasExtension(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) {
        return env->NewStringUTF(report("GLES_EXTENSION_UNAVAILABLE", "Runtime does not advertise XR_KHR_opengl_es_enable.", XR_ERROR_GRAPHICS_DEVICE_INVALID).c_str());
    }

    const char* enabledExtensions[] = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME
    };
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = activityContext;
    XrInstanceCreateInfo createInfo{};
    createInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    createInfo.next = &androidInfo;
    const char* appName = "QuestPhoneXR";
    const char* engineName = "QuestPhoneXR";
    std::copy(appName, appName + std::char_traits<char>::length(appName), createInfo.applicationInfo.applicationName);
    std::copy(engineName, engineName + std::char_traits<char>::length(engineName), createInfo.applicationInfo.engineName);
    createInfo.applicationInfo.applicationVersion = 1;
    createInfo.applicationInfo.engineVersion = 1;
    createInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    createInfo.enabledExtensionCount = 2;
    createInfo.enabledExtensionNames = enabledExtensions;

    XrInstance instance = XR_NULL_HANDLE;
    result = xrCreateInstance(&createInfo, &instance);
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(report("INSTANCE_CREATION_FAILED", "xrCreateInstance with GLES extension failed.", result).c_str());
    }

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    result = xrGetSystem(instance, &systemInfo, &systemId);
    if (result != XR_SUCCESS) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("HMD_SYSTEM_UNAVAILABLE", "xrGetSystem did not return an HMD system.", result).c_str());
    }

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
    result = xrGetInstanceProcAddr(
        instance, "xrGetOpenGLESGraphicsRequirementsKHR",
        reinterpret_cast<PFN_xrVoidFunction*>(&getRequirements)
    );
    if (result != XR_SUCCESS || getRequirements == nullptr) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("GLES_REQUIREMENTS_UNAVAILABLE", "Could not load xrGetOpenGLESGraphicsRequirementsKHR.", result).c_str());
    }
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    result = getRequirements(instance, systemId, &requirements);
    if (result != XR_SUCCESS) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("GLES_REQUIREMENTS_FAILED", "Runtime rejected the OpenGL ES requirements query.", result).c_str());
    }

    // Bind OpenXR to the EGL context that is already drawing this GLSurfaceView.
    // A second context would validate a different context than the app renderer.
    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext currentContext = eglGetCurrentContext();
    EGLSurface currentDrawSurface = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface currentReadSurface = eglGetCurrentSurface(EGL_READ);
    if (display == EGL_NO_DISPLAY || currentContext == EGL_NO_CONTEXT ||
        currentDrawSurface == EGL_NO_SURFACE || currentReadSurface == EGL_NO_SURFACE) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report(
            "EGL_CONTEXT_UNAVAILABLE",
            "The XR GL thread has no current EGL display, context, and window surfaces.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        ).c_str());
    }

    EGLint contextConfigId = 0;
    if (eglQueryContext(display, currentContext, EGL_CONFIG_ID, &contextConfigId) != EGL_TRUE ||
        contextConfigId == 0) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report(
            "EGL_CONFIG_QUERY_FAILED",
            "Could not read the EGL config ID belonging to the active renderer context.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        ).c_str());
    }

    const EGLint configAttributes[] = { EGL_CONFIG_ID, contextConfigId, EGL_NONE };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(display, configAttributes, &config, 1, &configCount) != EGL_TRUE ||
        configCount != 1) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report(
            "EGL_CONFIG_QUERY_FAILED",
            "Could not resolve the exact EGL config belonging to the active renderer context.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        ).c_str());
    }

    const char* glVersionValue = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    const std::string glVersion = glVersionValue ? glVersionValue : "unknown";
    int glMajor = 0;
    int glMinor = 0;
    if (glVersionValue != nullptr) {
        std::sscanf(glVersionValue, "%*[^0-9]%d.%d", &glMajor, &glMinor);
    }
    const XrVersion actualVersion = XR_MAKE_VERSION(glMajor, glMinor, 0);
    if (glMajor == 0 || actualVersion < requirements.minApiVersionSupported ||
        actualVersion > requirements.maxApiVersionSupported) {
        xrDestroyInstance(instance);
        std::ostringstream message;
        message << "Active renderer GLES context " << glVersion
                << " is outside Runtime range "
                << XR_VERSION_MAJOR(requirements.minApiVersionSupported) << "."
                << XR_VERSION_MINOR(requirements.minApiVersionSupported) << " - "
                << XR_VERSION_MAJOR(requirements.maxApiVersionSupported) << "."
                << XR_VERSION_MINOR(requirements.maxApiVersionSupported) << ".";
        return env->NewStringUTF(report("GLES_VERSION_UNSUPPORTED", message.str(), XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING).c_str());
    }

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.display = display;
    graphicsBinding.config = config;
    graphicsBinding.context = currentContext;
    XrSessionCreateInfo sessionInfo{};
    sessionInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionInfo.next = &graphicsBinding;
    sessionInfo.systemId = systemId;
    XrSession session = XR_NULL_HANDLE;
    const XrResult sessionResult = xrCreateSession(instance, &sessionInfo, &session);

    XrResult swapchainResult = XR_SUCCESS;
    std::string swapchainMessage = "Stereo swapchain was not attempted.";
    XrSwapchain swapchain = XR_NULL_HANDLE;
    XrSpace referenceSpace = XR_NULL_HANDLE;
    uint32_t imageCount = 0;
    int64_t selectedFormat = 0;
    uint32_t swapchainWidth = 0;
    uint32_t swapchainHeight = 0;
    uint32_t viewCount = 0;
    std::vector<XrViewConfigurationView> viewConfigurationViews;
    std::vector<XrSwapchainImageOpenGLESKHR> swapchainImages;
    FrameLoopSubmission frameSubmission{
        "SESSION_CREATION_FAILED", sessionResult,
        "Runtime did not create an OpenXR session.", false
    };

    if (sessionResult == XR_SUCCESS) {
        swapchainResult = xrEnumerateViewConfigurationViews(
            instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
            0, &viewCount, nullptr
        );
        if (swapchainResult == XR_SUCCESS && viewCount != 2) {
            swapchainResult = XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
        }
        if (swapchainResult == XR_SUCCESS) {
            viewConfigurationViews.resize(viewCount);
            for (auto& view : viewConfigurationViews) {
                view.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
                view.next = nullptr;
            }
            swapchainResult = xrEnumerateViewConfigurationViews(
                instance, systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                viewCount, &viewCount, viewConfigurationViews.data()
            );
        }
        if (swapchainResult == XR_SUCCESS) {
            swapchainWidth = 1;
            swapchainHeight = 1;
            for (const auto& view : viewConfigurationViews) {
                swapchainWidth = std::max(swapchainWidth, view.recommendedImageRectWidth);
                swapchainHeight = std::max(swapchainHeight, view.recommendedImageRectHeight);
            }

            uint32_t formatCount = 0;
            swapchainResult = xrEnumerateSwapchainFormats(session, 0, &formatCount, nullptr);
            if (swapchainResult == XR_SUCCESS && formatCount > 0) {
                std::vector<int64_t> formats(formatCount);
                swapchainResult = xrEnumerateSwapchainFormats(
                    session, formatCount, &formatCount, formats.data()
                );
                if (swapchainResult == XR_SUCCESS && formatCount > 0) {
                    const auto rgba = std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_RGBA8));
                    const auto srgb = std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_SRGB8_ALPHA8));
                    selectedFormat = rgba != formats.end() ? *rgba :
                        (srgb != formats.end() ? *srgb : formats.front());

                    XrSwapchainCreateInfo swapchainInfo{};
                    swapchainInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
                    swapchainInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
                    swapchainInfo.format = selectedFormat;
                    swapchainInfo.sampleCount = 1;
                    swapchainInfo.width = swapchainWidth;
                    swapchainInfo.height = swapchainHeight;
                    swapchainInfo.faceCount = 1;
                    swapchainInfo.arraySize = viewCount;
                    swapchainInfo.mipCount = 1;
                    swapchainResult = xrCreateSwapchain(session, &swapchainInfo, &swapchain);
                    if (swapchainResult == XR_SUCCESS) {
                        swapchainResult = xrEnumerateSwapchainImages(swapchain, 0, &imageCount, nullptr);
                        if (swapchainResult == XR_SUCCESS && imageCount > 0) {
                            swapchainImages.resize(imageCount);
                            for (auto& image : swapchainImages) {
                                image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
                                image.next = nullptr;
                            }
                            swapchainResult = xrEnumerateSwapchainImages(
                                swapchain, imageCount, &imageCount,
                                reinterpret_cast<XrSwapchainImageBaseHeader*>(swapchainImages.data())
                            );
                        } else if (swapchainResult == XR_SUCCESS) {
                            swapchainResult = XR_ERROR_RUNTIME_FAILURE;
                        }
                    }
                } else if (swapchainResult == XR_SUCCESS) {
                    swapchainResult = XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
                }
            } else if (swapchainResult == XR_SUCCESS) {
                swapchainResult = XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
            }
        }

        if (swapchainResult == XR_SUCCESS) {
            frameSubmission = submitGlesFrameLoop(
                instance, session, swapchain, swapchainImages,
                viewCount, swapchainWidth, swapchainHeight
            );
            swapchainMessage = frameSubmission.message;
        } else {
            swapchainMessage = "Stereo swapchain setup failed before the first frame.";
        }
    }

    if (swapchain != XR_NULL_HANDLE) xrDestroySwapchain(swapchain);
    if (session != XR_NULL_HANDLE) xrDestroySession(session);
    // GLSurfaceView owns the EGL context and window surface.
    xrDestroyInstance(instance);

    if (sessionResult != XR_SUCCESS) {
        return env->NewStringUTF(report(
            "SESSION_CREATION_FAILED",
            "Runtime accepted the GLES extension and graphics requirements, but xrCreateSession failed.",
            sessionResult
        ).c_str());
    }
    if (swapchainResult != XR_SUCCESS) {
        return env->NewStringUTF(report(
            "SWAPCHAIN_CREATION_FAILED_GLES",
            "xrCreateSession succeeded, but stereo swapchain setup failed: " + swapchainMessage,
            swapchainResult
        ).c_str());
    }
    if (frameSubmission.submitted) {
        std::ostringstream details;
        details << frameSubmission.message << " GLES " << glVersion
                << "; stereo swapchain " << swapchainWidth << "x" << swapchainHeight
                << " with " << imageCount << " images.";
        return env->NewStringUTF(report(
            "SUSTAINED_FRAMES_SUBMITTED_GLES",
            details.str(),
            frameSubmission.result
        ).c_str());
    }
    return env->NewStringUTF(report(
        frameSubmission.status,
        "XR session or first-frame submission did not complete: " + frameSubmission.message,
        frameSubmission.result
    ).c_str());
}


// Persistent GLES session used by the isolated runtime Activity. Unlike the
// bounded diagnostic above, this state survives individual GLSurfaceView frames.
namespace {
struct PersistentGlesSessionState {
    JavaVM* vm = nullptr;
    jobject activityGlobal = nullptr;
    jobject applicationContextGlobal = nullptr;
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSwapchain swapchain = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    uint32_t viewCount = 0;
    uint32_t width = 0;
    uint32_t height = 0;
    EGLDisplay display = EGL_NO_DISPLAY;
    EGLContext context = EGL_NO_CONTEXT;
    EGLSurface drawSurface = EGL_NO_SURFACE;
    EGLSurface readSurface = EGL_NO_SURFACE;
    GLuint framebuffer = 0;
    XrSessionState sessionState = XR_SESSION_STATE_UNKNOWN;
    bool sessionBegun = false;
    bool paused = false;
    bool created = false;
    uint64_t submittedFrames = 0;
    uint64_t poseSamples = 0;
    uint64_t orientationValidSamples = 0;
    uint64_t positionValidSamples = 0;
    uint64_t fullTrackingSamples = 0;
    uint64_t orientationTrackedSamples = 0;
    uint64_t positionTrackedSamples = 0;
    uint64_t fullTrackedSamples = 0;
    XrViewStateFlags lastViewStateFlags = 0;
    bool hasInitialHeadPosition = false;
    bool hasInitialHeadOrientation = false;
    XrVector3f initialHeadPosition{};
    XrQuaternionf initialHeadOrientation{};
    double maxHeadPositionDeltaMeters = 0.0;
    double maxHeadRotationDeltaDegrees = 0.0;
    std::string glVersion;
};

std::mutex persistentSessionMutex;
PersistentGlesSessionState persistentSession;

std::string persistentReport(
    const char* status,
    const std::string& message,
    XrResult result = XR_SUCCESS,
    bool overrideFrameCount = false,
    uint64_t frameCount = 0
) {
    const uint64_t displayedFrameCount =
        overrideFrameCount ? frameCount : persistentSession.submittedFrames;
    std::ostringstream out;
    out << std::fixed << std::setprecision(6);
    out << R"({"status":")" << status
        << R"(","xrResult":)" << static_cast<int>(result)
        << R"(","framesSubmitted":)" << displayedFrameCount
        << R"(,"sessionState":)" << static_cast<int>(persistentSession.sessionState)
        << R"(,"poseSamples":)" << persistentSession.poseSamples
        << R"(,"orientationValidSamples":)" << persistentSession.orientationValidSamples
        << R"(,"positionValidSamples":)" << persistentSession.positionValidSamples
        << R"(,"fullTrackingSamples":)" << persistentSession.fullTrackingSamples
        << R"(,"orientationTrackedSamples":)" << persistentSession.orientationTrackedSamples
        << R"(,"positionTrackedSamples":)" << persistentSession.positionTrackedSamples
        << R"(,"fullTrackedSamples":)" << persistentSession.fullTrackedSamples
        << R"(,"lastViewStateFlags":)" << persistentSession.lastViewStateFlags
        << R"(,"maxHeadPositionDeltaMeters":)" << persistentSession.maxHeadPositionDeltaMeters
        << R"(,"maxHeadRotationDeltaDegrees":)" << persistentSession.maxHeadRotationDeltaDegrees
        << R"(,"message":")" << jsonEscape(message) << R"("})";
    return out.str();
}

void destroyPersistentGlesSession(JNIEnv* env) {
    auto& state = persistentSession;

    if (state.session != XR_NULL_HANDLE && state.sessionBegun) {
        (void)xrRequestExitSession(state.session);
        const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1200);
        bool stopped = false;
        while (std::chrono::steady_clock::now() < deadline) {
            XrEventDataBuffer event{};
            event.type = XR_TYPE_EVENT_DATA_BUFFER;
            const XrResult pollResult = xrPollEvent(state.instance, &event);
            if (pollResult == XR_SUCCESS &&
                event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                const auto* changed =
                    reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
                if (changed->session == state.session) {
                    state.sessionState = changed->state;
                    if (changed->state == XR_SESSION_STATE_STOPPING) {
                        const XrResult endResult = xrEndSession(state.session);
                        stopped = endResult == XR_SUCCESS ||
                            endResult == XR_SESSION_LOSS_PENDING;
                        break;
                    }
                    if (changed->state == XR_SESSION_STATE_EXITING ||
                        changed->state == XR_SESSION_STATE_LOSS_PENDING) {
                        break;
                    }
                }
            } else if (pollResult != XR_SUCCESS && pollResult != XR_EVENT_UNAVAILABLE) {
                break;
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        state.sessionBegun = !stopped;
    }

    if (state.framebuffer != 0 && eglGetCurrentContext() == state.context) {
        glDeleteFramebuffers(1, &state.framebuffer);
    }
    state.framebuffer = 0;

    if (state.swapchain != XR_NULL_HANDLE) {
        (void)xrDestroySwapchain(state.swapchain);
        state.swapchain = XR_NULL_HANDLE;
    }
    if (state.space != XR_NULL_HANDLE) {
        (void)xrDestroySpace(state.space);
        state.space = XR_NULL_HANDLE;
    }
    if (state.session != XR_NULL_HANDLE) {
        (void)xrDestroySession(state.session);
        state.session = XR_NULL_HANDLE;
    }
    if (state.instance != XR_NULL_HANDLE) {
        (void)xrDestroyInstance(state.instance);
        state.instance = XR_NULL_HANDLE;
    }

    if (env != nullptr) {
        if (state.activityGlobal != nullptr) env->DeleteGlobalRef(state.activityGlobal);
        if (state.applicationContextGlobal != nullptr) {
            env->DeleteGlobalRef(state.applicationContextGlobal);
        }
    }
    state = PersistentGlesSessionState{};
}

bool persistentPollEvents(std::string& errorMessage, XrResult& errorResult) {
    auto& state = persistentSession;
    XrEventDataBuffer event{};
    event.type = XR_TYPE_EVENT_DATA_BUFFER;
    XrResult result = xrPollEvent(state.instance, &event);
    while (result == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            const auto* changed =
                reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
            if (changed->session == state.session) {
                state.sessionState = changed->state;
                if (changed->state == XR_SESSION_STATE_EXITING ||
                    changed->state == XR_SESSION_STATE_LOSS_PENDING) {
                    state.sessionBegun = false;
                    errorMessage = "The runtime is exiting or has lost the OpenXR session.";
                    errorResult = XR_ERROR_SESSION_LOST;
                    return false;
                }
                if (changed->state == XR_SESSION_STATE_STOPPING && state.sessionBegun) {
                    const XrResult endResult = xrEndSession(state.session);
                    if (endResult != XR_SUCCESS && endResult != XR_SESSION_LOSS_PENDING) {
                        errorMessage = "xrEndSession failed while processing STOPPING.";
                        errorResult = endResult;
                        return false;
                    }
                    state.sessionBegun = false;
                    if (endResult == XR_SESSION_LOSS_PENDING) {
                        state.sessionState = XR_SESSION_STATE_LOSS_PENDING;
                        errorMessage = "The runtime reported session loss while ending the session.";
                        errorResult = XR_SESSION_LOSS_PENDING;
                        return false;
                    }
                }
            }
        }

        event = {};
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        result = xrPollEvent(state.instance, &event);
    }

    if (result != XR_EVENT_UNAVAILABLE) {
        errorMessage = "xrPollEvent failed while servicing the persistent session.";
        errorResult = result;
        return false;
    }

    // Keep terminal states sticky across pause/resume calls. The event may have
    // been consumed by nativePauseSession, so do not rely on seeing it again.
    if (state.sessionState == XR_SESSION_STATE_EXITING ||
        state.sessionState == XR_SESSION_STATE_LOSS_PENDING) {
        errorMessage = state.sessionState == XR_SESSION_STATE_EXITING
            ? "The runtime moved the session to EXITING."
            : "The runtime moved the session to LOSS_PENDING.";
        errorResult = XR_ERROR_SESSION_LOST;
        return false;
    }

    if (!state.paused && !state.sessionBegun &&
        state.sessionState == XR_SESSION_STATE_READY) {
        XrSessionBeginInfo beginInfo{};
        beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
        beginInfo.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
        result = xrBeginSession(state.session, &beginInfo);
        if (result != XR_SUCCESS) {
            errorMessage = "xrBeginSession failed after the runtime entered READY.";
            errorResult = result;
            return false;
        }
        state.sessionBegun = true;
    }
    return true;
}

jstring persistentFailure(
    JNIEnv* env,
    const char* status,
    const std::string& message,
    XrResult result
) {
    const std::string value = persistentReport(status, message, result);
    destroyPersistentGlesSession(env);
    return env->NewStringUTF(value.c_str());
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrPersistentGlesSession_nativeCreateSession(
    JNIEnv* env,
    jobject /* thiz */,
    jobject activityContext
) {
    std::lock_guard<std::mutex> lock(persistentSessionMutex);
    if (activityContext == nullptr) {
        return env->NewStringUTF(persistentReport(
            "ANDROID_CONTEXT_UNAVAILABLE", "Android Activity is unavailable.",
            XR_ERROR_INITIALIZATION_FAILED
        ).c_str());
    }
    if (persistentSession.created) {
        return env->NewStringUTF(persistentReport(
            "SESSION_ALREADY_CREATED", "The persistent OpenXR session already exists."
        ).c_str());
    }

    auto& state = persistentSession;
    if (env->GetJavaVM(&state.vm) != JNI_OK || state.vm == nullptr) {
        return env->NewStringUTF(persistentReport(
            "ANDROID_CONTEXT_UNAVAILABLE", "Could not obtain the Android JavaVM.",
            XR_ERROR_INITIALIZATION_FAILED
        ).c_str());
    }

    state.activityGlobal = env->NewGlobalRef(activityContext);
    if (state.activityGlobal == nullptr) {
        return env->NewStringUTF(persistentReport(
            "ANDROID_CONTEXT_UNAVAILABLE", "Could not retain the live Activity for the runtime handoff.",
            XR_ERROR_OUT_OF_MEMORY
        ).c_str());
    }

    jobject applicationContext = activityContext;
    jclass contextClass = env->GetObjectClass(activityContext);
    jmethodID getApplicationContext = env->GetMethodID(
        contextClass, "getApplicationContext", "()Landroid/content/Context;"
    );
    if (getApplicationContext != nullptr) {
        jobject app = env->CallObjectMethod(activityContext, getApplicationContext);
        if (app != nullptr) applicationContext = app;
        if (env->ExceptionCheck()) env->ExceptionClear();
    }
    state.applicationContextGlobal = env->NewGlobalRef(applicationContext);
    if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
    if (state.applicationContextGlobal == nullptr) {
        return persistentFailure(
            env, "ANDROID_CONTEXT_UNAVAILABLE",
            "Could not retain the Android application context for the loader.", XR_ERROR_OUT_OF_MEMORY
        );
    }

    XrResult initResult = XR_SUCCESS;
    {
        std::lock_guard<std::mutex> loaderLock(loaderMutex);
        if (!loaderInitialized) {
            PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
            const XrResult procResult = xrGetInstanceProcAddr(
                XR_NULL_HANDLE, "xrInitializeLoaderKHR",
                reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)
            );
            if (procResult != XR_SUCCESS || initializeLoader == nullptr) {
                cachedLoaderInitResult = procResult;
            } else {
                XrLoaderInitInfoAndroidKHR initInfo{};
                initInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
                initInfo.next = nullptr;
                initInfo.applicationVM = state.vm;
                initInfo.applicationContext =
                    reinterpret_cast<jobject>(state.applicationContextGlobal);
                cachedLoaderInitResult = initializeLoader(
                    reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&initInfo)
                );
                loaderInitialized = cachedLoaderInitResult == XR_SUCCESS;
            }
        }
        initResult = cachedLoaderInitResult;
    }
    if (initResult != XR_SUCCESS) {
        return persistentFailure(
            env, "LOADER_INITIALIZATION_FAILED", "xrInitializeLoaderKHR failed.",
            initResult
        );
    }

    uint32_t extensionCount = 0;
    XrResult result = xrEnumerateInstanceExtensionProperties(nullptr, 0, &extensionCount, nullptr);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "RUNTIME_ENUMERATION_FAILED", "Runtime extension enumeration failed.", result
        );
    }
    std::vector<XrExtensionProperties> extensions(extensionCount);
    for (auto& extension : extensions) {
        extension.type = XR_TYPE_EXTENSION_PROPERTIES;
        extension.next = nullptr;
    }
    if (extensionCount > 0) {
        result = xrEnumerateInstanceExtensionProperties(
            nullptr, extensionCount, &extensionCount, extensions.data()
        );
        if (result != XR_SUCCESS) {
            return persistentFailure(
                env, "RUNTIME_ENUMERATION_FAILED", "Runtime extension list could not be read.", result
            );
        }
        extensions.resize(extensionCount);
    }

    const auto hasExtension = [&extensions](const char* name) {
        return std::any_of(
            extensions.begin(), extensions.end(),
            [name](const XrExtensionProperties& extension) {
                return std::string(extension.extensionName) == name;
            }
        );
    };
    if (!hasExtension(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) {
        return persistentFailure(
            env, "ANDROID_INSTANCE_EXTENSION_UNAVAILABLE",
            "Runtime does not advertise XR_KHR_android_create_instance.",
            XR_ERROR_EXTENSION_NOT_PRESENT
        );
    }
    if (!hasExtension(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) {
        return persistentFailure(
            env, "GLES_EXTENSION_UNAVAILABLE",
            "Runtime does not advertise XR_KHR_opengl_es_enable.",
            XR_ERROR_EXTENSION_NOT_PRESENT
        );
    }

    const char* enabledExtensions[] = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME
    };
    XrInstanceCreateInfoAndroidKHR androidInfo{};
    androidInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidInfo.next = nullptr;
    androidInfo.applicationVM = state.vm;
    androidInfo.applicationActivity = state.activityGlobal;

    XrInstanceCreateInfo instanceInfo{};
    instanceInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    instanceInfo.next = &androidInfo;
    const char* appName = "QuestPhoneXR";
    const char* engineName = "QuestPhoneXR";
    std::copy(appName, appName + std::char_traits<char>::length(appName),
              instanceInfo.applicationInfo.applicationName);
    std::copy(engineName, engineName + std::char_traits<char>::length(engineName),
              instanceInfo.applicationInfo.engineName);
    instanceInfo.applicationInfo.applicationVersion = 1;
    instanceInfo.applicationInfo.engineVersion = 1;
    instanceInfo.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
    instanceInfo.enabledExtensionCount = 2;
    instanceInfo.enabledExtensionNames = enabledExtensions;
    result = xrCreateInstance(&instanceInfo, &state.instance);
    if (result != XR_SUCCESS || state.instance == XR_NULL_HANDLE) {
        std::ostringstream details;
        details << "xrCreateInstance failed for the persistent session"
                << "; result=" << static_cast<int>(result)
                << " (" << xrResultName(result) << ")"
                << "; instanceHandle=" << (state.instance == XR_NULL_HANDLE ? "NULL" : "valid")
                << "; requestedApiVersion=1.0.0"
                << "; enabledExtensions=["
                << XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME << ", "
                << XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME << "]"
                << "; advertisedExtensionCount=" << extensions.size()
                << "; loaderInitialized=" << (loaderInitialized ? "true" : "false");
        if (result == XR_ERROR_RUNTIME_UNAVAILABLE) {
            details << "; diagnosis=Khronos loader could not find or load the selected runtime manifest/runtime library. Check Android runtime selection/manifest registration and Monado service startup; this failure occurs before graphics binding or session creation.";
        }
        if (result == XR_SUCCESS) {
            details << "; runtime returned XR_SUCCESS but left XrInstance null";
            result = XR_ERROR_RUNTIME_FAILURE;
        }
        return persistentFailure(
            env, "INSTANCE_CREATION_FAILED", details.str(), result
        );
    }

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    result = xrGetSystem(state.instance, &systemInfo, &state.systemId);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "HMD_SYSTEM_UNAVAILABLE", "xrGetSystem did not return an HMD system.", result
        );
    }

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
    result = xrGetInstanceProcAddr(
        state.instance, "xrGetOpenGLESGraphicsRequirementsKHR",
        reinterpret_cast<PFN_xrVoidFunction*>(&getRequirements)
    );
    if (result != XR_SUCCESS || getRequirements == nullptr) {
        return persistentFailure(
            env, "GLES_REQUIREMENTS_UNAVAILABLE",
            "Could not load xrGetOpenGLESGraphicsRequirementsKHR.", result
        );
    }
    XrGraphicsRequirementsOpenGLESKHR requirements{};
    requirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
    result = getRequirements(state.instance, state.systemId, &requirements);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "GLES_REQUIREMENTS_FAILED", "Runtime rejected the GLES requirements query.", result
        );
    }

    state.display = eglGetCurrentDisplay();
    state.context = eglGetCurrentContext();
    state.drawSurface = eglGetCurrentSurface(EGL_DRAW);
    state.readSurface = eglGetCurrentSurface(EGL_READ);
    if (state.display == EGL_NO_DISPLAY || state.context == EGL_NO_CONTEXT ||
        state.drawSurface == EGL_NO_SURFACE || state.readSurface == EGL_NO_SURFACE) {
        return persistentFailure(
            env, "EGL_CONTEXT_UNAVAILABLE",
            "The calling GL thread has no current EGL display, context and window surfaces.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        );
    }

    EGLint configId = 0;
    if (eglQueryContext(state.display, state.context, EGL_CONFIG_ID, &configId) != EGL_TRUE ||
        configId == 0) {
        return persistentFailure(
            env, "EGL_CONFIG_QUERY_FAILED",
            "Could not read the EGL config ID for the active renderer context.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        );
    }
    const EGLint configAttributes[] = { EGL_CONFIG_ID, configId, EGL_NONE };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(state.display, configAttributes, &config, 1, &configCount) != EGL_TRUE ||
        configCount != 1) {
        return persistentFailure(
            env, "EGL_CONFIG_QUERY_FAILED",
            "Could not resolve the exact EGL config for the active context.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        );
    }

    const char* version = reinterpret_cast<const char*>(glGetString(GL_VERSION));
    state.glVersion = version != nullptr ? version : "unknown";
    int glMajor = 0;
    int glMinor = 0;
    if (version != nullptr) std::sscanf(version, "%*[^0-9]%d.%d", &glMajor, &glMinor);
    const XrVersion actualVersion = XR_MAKE_VERSION(glMajor, glMinor, 0);
    if (glMajor == 0 || actualVersion < requirements.minApiVersionSupported ||
        actualVersion > requirements.maxApiVersionSupported) {
        std::ostringstream message;
        message << "Active GLES context " << state.glVersion << " is outside runtime range "
                << XR_VERSION_MAJOR(requirements.minApiVersionSupported) << "."
                << XR_VERSION_MINOR(requirements.minApiVersionSupported) << " - "
                << XR_VERSION_MAJOR(requirements.maxApiVersionSupported) << "."
                << XR_VERSION_MINOR(requirements.maxApiVersionSupported) << ".";
        return persistentFailure(
            env, "GLES_VERSION_UNSUPPORTED", message.str(),
            XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING
        );
    }

    XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
    graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
    graphicsBinding.display = state.display;
    graphicsBinding.config = config;
    graphicsBinding.context = state.context;
    XrSessionCreateInfo sessionInfo{};
    sessionInfo.type = XR_TYPE_SESSION_CREATE_INFO;
    sessionInfo.next = &graphicsBinding;
    sessionInfo.systemId = state.systemId;
    result = xrCreateSession(state.instance, &sessionInfo, &state.session);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "SESSION_CREATION_FAILED", "xrCreateSession failed for the persistent GLES session.", result
        );
    }

    result = xrEnumerateViewConfigurationViews(
        state.instance, state.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        0, &state.viewCount, nullptr
    );
    if (result != XR_SUCCESS || state.viewCount != 2) {
        return persistentFailure(
            env, "STEREO_VIEW_CONFIGURATION_UNAVAILABLE",
            "Runtime did not expose exactly two primary stereo views.",
            result == XR_SUCCESS ? XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED : result
        );
    }

    std::vector<XrViewConfigurationView> viewConfigurationViews(state.viewCount);
    for (auto& view : viewConfigurationViews) {
        view.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
        view.next = nullptr;
    }
    result = xrEnumerateViewConfigurationViews(
        state.instance, state.systemId, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
        state.viewCount, &state.viewCount, viewConfigurationViews.data()
    );
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "STEREO_VIEW_CONFIGURATION_FAILED",
            "Could not read the primary stereo view configuration.", result
        );
    }

    state.width = 1;
    state.height = 1;
    for (const auto& view : viewConfigurationViews) {
        state.width = std::max(state.width, view.recommendedImageRectWidth);
        state.height = std::max(state.height, view.recommendedImageRectHeight);
    }

    uint32_t formatCount = 0;
    result = xrEnumerateSwapchainFormats(state.session, 0, &formatCount, nullptr);
    if (result != XR_SUCCESS || formatCount == 0) {
        return persistentFailure(
            env, "SWAPCHAIN_FORMATS_UNAVAILABLE",
            "Runtime did not return any swapchain formats.",
            result == XR_SUCCESS ? XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED : result
        );
    }
    std::vector<int64_t> formats(formatCount);
    result = xrEnumerateSwapchainFormats(
        state.session, formatCount, &formatCount, formats.data()
    );
    if (result != XR_SUCCESS || formatCount == 0) {
        return persistentFailure(
            env, "SWAPCHAIN_FORMATS_UNAVAILABLE",
            "Could not read the runtime swapchain formats.",
            result == XR_SUCCESS ? XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED : result
        );
    }
    auto rgba = std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_RGBA8));
    auto srgb = std::find(formats.begin(), formats.end(), static_cast<int64_t>(GL_SRGB8_ALPHA8));
    const int64_t selectedFormat = rgba != formats.end() ? *rgba :
        (srgb != formats.end() ? *srgb : formats.front());

    XrSwapchainCreateInfo swapchainInfo{};
    swapchainInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
    swapchainInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
    swapchainInfo.format = selectedFormat;
    swapchainInfo.sampleCount = 1;
    swapchainInfo.width = state.width;
    swapchainInfo.height = state.height;
    swapchainInfo.faceCount = 1;
    swapchainInfo.arraySize = state.viewCount;
    swapchainInfo.mipCount = 1;
    result = xrCreateSwapchain(state.session, &swapchainInfo, &state.swapchain);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "SWAPCHAIN_CREATION_FAILED", "xrCreateSwapchain failed.", result
        );
    }

    uint32_t imageCount = 0;
    result = xrEnumerateSwapchainImages(state.swapchain, 0, &imageCount, nullptr);
    if (result != XR_SUCCESS || imageCount == 0) {
        return persistentFailure(
            env, "SWAPCHAIN_IMAGES_UNAVAILABLE",
            "Runtime returned no swapchain images.",
            result == XR_SUCCESS ? XR_ERROR_RUNTIME_FAILURE : result
        );
    }
    state.images.resize(imageCount);
    for (auto& image : state.images) {
        image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
        image.next = nullptr;
    }
    result = xrEnumerateSwapchainImages(
        state.swapchain, imageCount, &imageCount,
        reinterpret_cast<XrSwapchainImageBaseHeader*>(state.images.data())
    );
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "SWAPCHAIN_IMAGES_FAILED", "Could not retrieve the swapchain texture array.", result
        );
    }
    state.images.resize(imageCount);

    XrReferenceSpaceCreateInfo spaceInfo{};
    spaceInfo.type = XR_TYPE_REFERENCE_SPACE_CREATE_INFO;
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    result = xrCreateReferenceSpace(state.session, &spaceInfo, &state.space);
    if (result != XR_SUCCESS) {
        return persistentFailure(
            env, "REFERENCE_SPACE_FAILED", "Could not create the LOCAL reference space.", result
        );
    }

    state.sessionState = XR_SESSION_STATE_UNKNOWN;
    state.sessionBegun = false;
    state.paused = false;
    state.created = true;
    state.submittedFrames = 0;
    state.poseSamples = 0;
    state.orientationValidSamples = 0;
    state.positionValidSamples = 0;
    state.fullTrackingSamples = 0;
    state.orientationTrackedSamples = 0;
    state.positionTrackedSamples = 0;
    state.fullTrackedSamples = 0;
    state.lastViewStateFlags = 0;
    state.hasInitialHeadPosition = false;
    state.hasInitialHeadOrientation = false;
    state.initialHeadPosition = {};
    state.initialHeadOrientation = {};
    state.maxHeadPositionDeltaMeters = 0.0;
    state.maxHeadRotationDeltaDegrees = 0.0;
    std::ostringstream message;
    message << "Persistent OpenXR GLES session and stereo swapchain created on the isolated GL thread. "
            << "Waiting for READY; " << state.glVersion << ", " << state.width
            << "x" << state.height << ", " << imageCount << " swapchain images. "
            << "The session will remain allocated across individual frame callbacks.";
    return env->NewStringUTF(persistentReport(
        "SESSION_CREATED_WAITING_FOR_READY", message.str()
    ).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrPersistentGlesSession_nativeRenderFrame(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(persistentSessionMutex);
    auto& state = persistentSession;
    if (!state.created || state.instance == XR_NULL_HANDLE || state.session == XR_NULL_HANDLE) {
        return env->NewStringUTF(persistentReport(
            "SESSION_NOT_CREATED", "Create the persistent OpenXR session before rendering.",
            XR_ERROR_HANDLE_INVALID
        ).c_str());
    }
    if (state.paused) {
        return env->NewStringUTF(persistentReport(
            "SESSION_PAUSED", "The Android Activity is paused; frame submission is suspended."
        ).c_str());
    }
    if (eglGetCurrentContext() != state.context || eglGetCurrentDisplay() != state.display) {
        return env->NewStringUTF(persistentReport(
            "EGL_CONTEXT_CHANGED",
            "The GLSurfaceView EGL context changed. Destroy and recreate the persistent session on the new GL context.",
            XR_ERROR_GRAPHICS_DEVICE_INVALID
        ).c_str());
    }

    std::string eventError;
    XrResult eventResult = XR_SUCCESS;
    if (!persistentPollEvents(eventError, eventResult)) {
        return env->NewStringUTF(persistentReport(
            "SESSION_EVENT_FAILED", eventError, eventResult
        ).c_str());
    }
    if (!state.sessionBegun) {
        return env->NewStringUTF(persistentReport(
            "WAITING_FOR_SESSION_READY",
            "OpenXR session is allocated and waiting for the runtime READY state."
        ).c_str());
    }

    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;
    XrFrameWaitInfo waitInfo{};
    waitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
    XrResult result = xrWaitFrame(state.session, &waitInfo, &frameState);
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(persistentReport(
            "WAIT_FRAME_FAILED", "xrWaitFrame failed in the persistent render callback.", result
        ).c_str());
    }

    XrFrameBeginInfo beginInfo{};
    beginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    result = xrBeginFrame(state.session, &beginInfo);
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(persistentReport(
            "BEGIN_FRAME_FAILED", "xrBeginFrame failed in the persistent render callback.", result
        ).c_str());
    }

    if (frameState.shouldRender == XR_FALSE) {
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        result = xrEndFrame(state.session, &endInfo);
        if (result != XR_SUCCESS) {
            return env->NewStringUTF(persistentReport(
                "END_EMPTY_FRAME_FAILED", "Runtime rejected a non-rendered frame.", result
            ).c_str());
        }
        return env->NewStringUTF(persistentReport(
            "FRAME_NOT_RENDERED", "Runtime requested an empty frame; the persistent session remains active."
        ).c_str());
    }

    std::vector<XrView> locatedViews(state.viewCount);
    for (auto& view : locatedViews) {
        view.type = XR_TYPE_VIEW;
        view.next = nullptr;
    }
    XrViewState viewState{};
    viewState.type = XR_TYPE_VIEW_STATE;
    XrViewLocateInfo locateInfo{};
    locateInfo.type = XR_TYPE_VIEW_LOCATE_INFO;
    locateInfo.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locateInfo.displayTime = frameState.predictedDisplayTime;
    locateInfo.space = state.space;
    uint32_t locatedCount = state.viewCount;
    result = xrLocateViews(
        state.session, &locateInfo, &viewState, state.viewCount,
        &locatedCount, locatedViews.data()
    );
    if (result != XR_SUCCESS || locatedCount != state.viewCount) {
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        (void)xrEndFrame(state.session, &endInfo);
        return env->NewStringUTF(persistentReport(
            "LOCATE_VIEWS_FAILED", "xrLocateViews did not return both stereo eye poses.",
            result == XR_SUCCESS ? XR_ERROR_VALIDATION_FAILURE : result
        ).c_str());
    }

    // Track validity independently: runtimes may report orientation without
    // positional tracking, which is still useful 3DoF data for this phone.
    state.lastViewStateFlags = viewState.viewStateFlags;
    ++state.poseSamples;
    const bool orientationValid =
        (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0;
    const bool positionValid =
        (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_VALID_BIT) != 0;
    const bool orientationTracked =
        (viewState.viewStateFlags & XR_VIEW_STATE_ORIENTATION_TRACKED_BIT) != 0;
    const bool positionTracked =
        (viewState.viewStateFlags & XR_VIEW_STATE_POSITION_TRACKED_BIT) != 0;
    if (orientationValid) ++state.orientationValidSamples;
    if (positionValid) ++state.positionValidSamples;
    if (orientationValid && positionValid) ++state.fullTrackingSamples;
    if (orientationTracked) ++state.orientationTrackedSamples;
    if (positionTracked) ++state.positionTrackedSamples;
    if (orientationTracked && positionTracked) ++state.fullTrackedSamples;

    const XrPosef& headPose = locatedViews[0].pose;
    if (positionValid) {
        if (!state.hasInitialHeadPosition) {
            state.initialHeadPosition = headPose.position;
            state.hasInitialHeadPosition = true;
        } else {
            const double dx = static_cast<double>(headPose.position.x - state.initialHeadPosition.x);
            const double dy = static_cast<double>(headPose.position.y - state.initialHeadPosition.y);
            const double dz = static_cast<double>(headPose.position.z - state.initialHeadPosition.z);
            state.maxHeadPositionDeltaMeters = std::max(
                state.maxHeadPositionDeltaMeters, std::sqrt(dx * dx + dy * dy + dz * dz)
            );
        }
    }
    if (orientationValid) {
        if (!state.hasInitialHeadOrientation) {
            state.initialHeadOrientation = headPose.orientation;
            state.hasInitialHeadOrientation = true;
        } else {
            const auto& first = state.initialHeadOrientation;
            const auto& current = headPose.orientation;
            const double dot = std::abs(
                static_cast<double>(first.x) * current.x +
                static_cast<double>(first.y) * current.y +
                static_cast<double>(first.z) * current.z +
                static_cast<double>(first.w) * current.w
            );
            const double clampedDot = std::clamp(dot, 0.0, 1.0);
            const double angleDegrees =
                2.0 * std::acos(clampedDot) * 180.0 / 3.14159265358979323846;
            state.maxHeadRotationDeltaDegrees = std::max(
                state.maxHeadRotationDeltaDegrees, angleDegrees
            );
        }
    }

    XrSwapchainImageAcquireInfo acquireInfo{};
    acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
    uint32_t imageIndex = 0;
    result = xrAcquireSwapchainImage(state.swapchain, &acquireInfo, &imageIndex);
    if (result != XR_SUCCESS) {
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        (void)xrEndFrame(state.session, &endInfo);
        return env->NewStringUTF(persistentReport(
            "ACQUIRE_IMAGE_FAILED", "xrAcquireSwapchainImage failed.", result
        ).c_str());
    }

    XrSwapchainImageWaitInfo imageWaitInfo{};
    imageWaitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    imageWaitInfo.timeout = 250'000'000;
    result = xrWaitSwapchainImage(state.swapchain, &imageWaitInfo);
    if (result != XR_SUCCESS || imageIndex >= state.images.size()) {
        XrSwapchainImageReleaseInfo releaseInfo{};
        releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
        (void)xrReleaseSwapchainImage(state.swapchain, &releaseInfo);
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        (void)xrEndFrame(state.session, &endInfo);
        return env->NewStringUTF(persistentReport(
            "WAIT_IMAGE_FAILED",
            imageIndex >= state.images.size()
                ? "Runtime returned a swapchain image index outside the image list."
                : "xrWaitSwapchainImage did not become ready within 250 ms.",
            imageIndex >= state.images.size() ? XR_ERROR_INDEX_OUT_OF_RANGE : result
        ).c_str());
    }

    for (int staleError = 0; staleError < 8 && glGetError() != GL_NO_ERROR; ++staleError) {}
    if (state.framebuffer == 0) glGenFramebuffers(1, &state.framebuffer);
    glBindFramebuffer(GL_FRAMEBUFFER, state.framebuffer);
    bool framebufferComplete = true;
    for (uint32_t eye = 0; eye < state.viewCount; ++eye) {
        glFramebufferTextureLayer(
            GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            state.images[imageIndex].image, 0, static_cast<GLint>(eye)
        );
        if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) {
            framebufferComplete = false;
            break;
        }
        glViewport(0, 0, static_cast<GLsizei>(state.width), static_cast<GLsizei>(state.height));
        glClearColor(eye == 0 ? 0.04f : 0.02f, 0.16f, eye == 0 ? 0.30f : 0.22f, 1.0f);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glFlush();
    const GLenum glError = glGetError();

    XrSwapchainImageReleaseInfo releaseInfo{};
    releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
    const XrResult releaseResult = xrReleaseSwapchainImage(state.swapchain, &releaseInfo);

    if (!framebufferComplete || glError != GL_NO_ERROR || releaseResult != XR_SUCCESS) {
        XrFrameEndInfo endInfo{};
        endInfo.type = XR_TYPE_FRAME_END_INFO;
        endInfo.displayTime = frameState.predictedDisplayTime;
        endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
        (void)xrEndFrame(state.session, &endInfo);
        const XrResult failure = releaseResult != XR_SUCCESS ? releaseResult :
            (glError != GL_NO_ERROR ? XR_ERROR_GRAPHICS_DEVICE_INVALID : XR_ERROR_GRAPHICS_DEVICE_INVALID);
        return env->NewStringUTF(persistentReport(
            "GLES_SWAPCHAIN_RENDER_FAILED",
            !framebufferComplete
                ? "The runtime swapchain image could not be attached as a GLES framebuffer."
                : "GLES drawing or swapchain image release failed.",
            failure
        ).c_str());
    }

    std::vector<XrCompositionLayerProjectionView> projectionViews(state.viewCount);
    for (uint32_t eye = 0; eye < state.viewCount; ++eye) {
        auto& projectionView = projectionViews[eye];
        projectionView.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW;
        projectionView.pose = locatedViews[eye].pose;
        projectionView.fov = locatedViews[eye].fov;
        projectionView.subImage.swapchain = state.swapchain;
        projectionView.subImage.imageRect.offset = {0, 0};
        projectionView.subImage.imageRect.extent = {
            static_cast<int32_t>(state.width), static_cast<int32_t>(state.height)
        };
        projectionView.subImage.imageArrayIndex = eye;
    }

    XrCompositionLayerProjection projectionLayer{};
    projectionLayer.type = XR_TYPE_COMPOSITION_LAYER_PROJECTION;
    projectionLayer.space = state.space;
    projectionLayer.viewCount = state.viewCount;
    projectionLayer.views = projectionViews.data();
    const XrCompositionLayerBaseHeader* layers[] = {
        reinterpret_cast<const XrCompositionLayerBaseHeader*>(&projectionLayer)
    };
    XrFrameEndInfo endInfo{};
    endInfo.type = XR_TYPE_FRAME_END_INFO;
    endInfo.displayTime = frameState.predictedDisplayTime;
    endInfo.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    endInfo.layerCount = 1;
    endInfo.layers = layers;
    result = xrEndFrame(state.session, &endInfo);
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(persistentReport(
            "END_FRAME_FAILED", "xrEndFrame rejected the persistent stereo projection layer.", result
        ).c_str());
    }

    ++state.submittedFrames;
    std::ostringstream message;
    message << "Persistent OpenXR session active; submitted stereo GLES frame "
            << state.submittedFrames << ". Session and swapchain were not recreated.";
    return env->NewStringUTF(persistentReport("FRAME_SUBMITTED", message.str()).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrPersistentGlesSession_nativePauseSession(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(persistentSessionMutex);
    auto& state = persistentSession;
    if (!state.created) {
        return env->NewStringUTF(persistentReport(
            "SESSION_NOT_CREATED", "No persistent OpenXR session needs pausing.",
            XR_ERROR_HANDLE_INVALID
        ).c_str());
    }
    state.paused = true;
    if (!state.sessionBegun) {
        return env->NewStringUTF(persistentReport(
            "SESSION_PAUSED", "Session rendering paused; no running session needed an exit request."
        ).c_str());
    }

    XrResult result = xrRequestExitSession(state.session);
    if (result == XR_SESSION_LOSS_PENDING) {
        state.sessionState = XR_SESSION_STATE_LOSS_PENDING;
        state.sessionBegun = false;
        return env->NewStringUTF(persistentReport(
            "SESSION_PAUSED_RUNTIME_EXIT",
            "xrRequestExitSession reported that the runtime session is being lost.",
            result
        ).c_str());
    }
    if (result != XR_SUCCESS) {
        return env->NewStringUTF(persistentReport(
            "PAUSE_REQUEST_FAILED", "xrRequestExitSession failed during Activity pause.", result
        ).c_str());
    }

    const auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(1500);
    while (std::chrono::steady_clock::now() < deadline) {
        XrEventDataBuffer event{};
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        result = xrPollEvent(state.instance, &event);
        if (result == XR_SUCCESS &&
            event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            const auto* changed =
                reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
            if (changed->session == state.session) {
                state.sessionState = changed->state;
                if (changed->state == XR_SESSION_STATE_STOPPING) {
                    result = xrEndSession(state.session);
                    if (result == XR_SUCCESS || result == XR_SESSION_LOSS_PENDING) {
                        state.sessionBegun = false;
                        if (result == XR_SESSION_LOSS_PENDING) {
                            state.sessionState = XR_SESSION_STATE_LOSS_PENDING;
                            return env->NewStringUTF(persistentReport(
                                "SESSION_PAUSED_RUNTIME_EXIT",
                                "Runtime reported session loss while ending the paused session.",
                                result
                            ).c_str());
                        }
                        return env->NewStringUTF(persistentReport(
                            "SESSION_PAUSED", "Runtime reached STOPPING and xrEndSession completed."
                        ).c_str());
                    }
                    return env->NewStringUTF(persistentReport(
                        "PAUSE_END_FAILED", "xrEndSession failed while pausing the Activity.", result
                    ).c_str());
                }
                if (changed->state == XR_SESSION_STATE_EXITING ||
                    changed->state == XR_SESSION_STATE_LOSS_PENDING) {
                    state.sessionBegun = false;
                    return env->NewStringUTF(persistentReport(
                        "SESSION_PAUSED_RUNTIME_EXIT", "Runtime exited the session during Activity pause.",
                        XR_ERROR_SESSION_LOST
                    ).c_str());
                }
            }
        } else if (result != XR_SUCCESS && result != XR_EVENT_UNAVAILABLE) {
            return env->NewStringUTF(persistentReport(
                "PAUSE_EVENT_FAILED", "Could not poll the runtime while pausing.", result
            ).c_str());
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(5));
    }

    return env->NewStringUTF(persistentReport(
        "PAUSE_PENDING_STOPPING",
        "Exit was requested; STOPPING was not observed before the bounded pause deadline."
    ).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrPersistentGlesSession_nativeResumeSession(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(persistentSessionMutex);
    if (!persistentSession.created) {
        return env->NewStringUTF(persistentReport(
            "SESSION_NOT_CREATED", "Create the persistent OpenXR session after surface recreation.",
            XR_ERROR_HANDLE_INVALID
        ).c_str());
    }
    persistentSession.paused = false;
    return env->NewStringUTF(persistentReport(
        "SESSION_RESUME_PENDING_READY",
        "Rendering resumed; the next GL callback will process runtime events and begin again after READY."
    ).c_str());
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrPersistentGlesSession_nativeDestroySession(
    JNIEnv* env,
    jobject /* thiz */
) {
    std::lock_guard<std::mutex> lock(persistentSessionMutex);
    if (!persistentSession.created &&
        persistentSession.instance == XR_NULL_HANDLE &&
        persistentSession.activityGlobal == nullptr) {
        return env->NewStringUTF(persistentReport(
            "SESSION_ALREADY_DESTROYED", "No persistent OpenXR resources remain."
        ).c_str());
    }
    const uint64_t frames = persistentSession.submittedFrames;
    std::ostringstream message;
    message << "Persistent OpenXR instance, session, space and swapchain destroyed. "
            << "Stereo frames submitted during this run: " << frames << ".";
    // Snapshot the report before teardown resets the native counters and state.
    const std::string finalReport = persistentReport(
        "SESSION_DESTROYED", message.str(), XR_SUCCESS, true, frames
    );
    destroyPersistentGlesSession(env);
    return env->NewStringUTF(finalReport.c_str());
}

// Diagnostic only: mirrors the Khronos Android loader's runtime load/negotiation step.
// It does not create an OpenXR instance or start Monado's Binder client.
extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrLoaderProbe_nativeProbeRuntimeLibrary(
    JNIEnv* env,
    jobject /* thiz */,
    jstring javaPath
) {
    if (javaPath == nullptr) {
        return env->NewStringUTF("{\"status\":\"PATH_MISSING\",\"message\":\"Runtime library path is empty.\"}");
    }

    const char* rawPath = env->GetStringUTFChars(javaPath, nullptr);
    if (rawPath == nullptr) {
        return env->NewStringUTF("{\"status\":\"PATH_READ_FAILED\",\"message\":\"Could not read runtime library path from Java.\"}");
    }
    const std::string path(rawPath);
    env->ReleaseStringUTFChars(javaPath, rawPath);

    dlerror();
    void* handle = dlopen(path.c_str(), RTLD_LAZY | RTLD_LOCAL);
    if (handle == nullptr) {
        const char* error = dlerror();
        const std::string message = error != nullptr ? error : "dlopen returned NULL without linker error text";
        std::ostringstream out;
        out << "{\"status\":\"DLOPEN_FAILED\",\"path\":\""
            << jsonEscape(path) << "\",\"message\":\"" << jsonEscape(message) << "\"}";
        return env->NewStringUTF(out.str().c_str());
    }

    dlerror();
    auto negotiate = reinterpret_cast<PFN_xrNegotiateLoaderRuntimeInterface>(
        dlsym(handle, "xrNegotiateLoaderRuntimeInterface")
    );
    const char* symbolError = dlerror();
    if (negotiate == nullptr || symbolError != nullptr) {
        const std::string message = symbolError != nullptr ? symbolError : "Negotiation export is missing.";
        dlclose(handle);
        std::ostringstream out;
        out << "{\"status\":\"NEGOTIATION_EXPORT_MISSING\",\"path\":\""
            << jsonEscape(path) << "\",\"negotiationExportFound\":false,\"message\":\""
            << jsonEscape(message) << "\"}";
        return env->NewStringUTF(out.str().c_str());
    }

    XrNegotiateLoaderInfo loaderInfo{};
    loaderInfo.structType = XR_LOADER_INTERFACE_STRUCT_LOADER_INFO;
    loaderInfo.structVersion = XR_LOADER_INFO_STRUCT_VERSION;
    loaderInfo.structSize = sizeof(XrNegotiateLoaderInfo);
    loaderInfo.minInterfaceVersion = 1;
    loaderInfo.maxInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    loaderInfo.minApiVersion = XR_MAKE_VERSION(1, 0, 0);
    loaderInfo.maxApiVersion = XR_CURRENT_API_VERSION;

    XrNegotiateRuntimeRequest runtimeRequest{};
    runtimeRequest.structType = XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST;
    runtimeRequest.structVersion = XR_RUNTIME_INFO_STRUCT_VERSION;
    runtimeRequest.structSize = sizeof(XrNegotiateRuntimeRequest);

    const XrResult negotiationResult = negotiate(&loaderInfo, &runtimeRequest);
    const bool hasGetInstanceProcAddr = runtimeRequest.getInstanceProcAddr != nullptr;
    const bool interfaceVersionValid =
        runtimeRequest.runtimeInterfaceVersion >= loaderInfo.minInterfaceVersion &&
        runtimeRequest.runtimeInterfaceVersion <= loaderInfo.maxInterfaceVersion;
    const bool apiVersionValid =
        runtimeRequest.runtimeApiVersion >= loaderInfo.minApiVersion &&
        runtimeRequest.runtimeApiVersion <= loaderInfo.maxApiVersion;

    XrResult createInstanceProcResult = XR_ERROR_FUNCTION_UNSUPPORTED;
    bool createInstanceProcAvailable = false;
    if (negotiationResult == XR_SUCCESS && hasGetInstanceProcAddr) {
        PFN_xrVoidFunction createInstanceProc = nullptr;
        createInstanceProcResult = runtimeRequest.getInstanceProcAddr(
            XR_NULL_HANDLE, "xrCreateInstance", &createInstanceProc
        );
        createInstanceProcAvailable =
            createInstanceProcResult == XR_SUCCESS && createInstanceProc != nullptr;
    }

    const bool negotiationSucceeded =
        negotiationResult == XR_SUCCESS && hasGetInstanceProcAddr &&
        interfaceVersionValid && apiVersionValid && createInstanceProcAvailable;

    std::ostringstream out;
    const char* status = negotiationSucceeded
        ? "RUNTIME_NEGOTIATION_OK"
        : (negotiationResult != XR_SUCCESS
            ? "RUNTIME_NEGOTIATION_FAILED"
            : "RUNTIME_NEGOTIATION_INVALID_RESPONSE");
    out << "{\"status\":\"" << status
        << "\",\"path\":\"" << jsonEscape(path)
        << "\",\"negotiationExportFound\":true"
        << ",\"negotiationResult\":" << static_cast<int>(negotiationResult)
        << ",\"negotiationResultName\":\"" << xrResultName(negotiationResult) << "\""
        << ",\"runtimeInterfaceVersion\":" << runtimeRequest.runtimeInterfaceVersion
        << ",\"runtimeApiVersionName\":\""
        << XR_VERSION_MAJOR(runtimeRequest.runtimeApiVersion) << "."
        << XR_VERSION_MINOR(runtimeRequest.runtimeApiVersion) << "."
        << XR_VERSION_PATCH(runtimeRequest.runtimeApiVersion) << "\""
        << ",\"getInstanceProcAddrAvailable\":" << (hasGetInstanceProcAddr ? "true" : "false")
        << ",\"interfaceVersionValid\":" << (interfaceVersionValid ? "true" : "false")
        << ",\"apiVersionValid\":" << (apiVersionValid ? "true" : "false")
        << ",\"xrCreateInstanceProcResult\":" << static_cast<int>(createInstanceProcResult)
        << ",\"xrCreateInstanceProcAvailable\":" << (createInstanceProcAvailable ? "true" : "false")
        << ",\"message\":\""
        << (negotiationSucceeded
            ? "Runtime negotiation succeeded; xrGetInstanceProcAddr returned xrCreateInstance. No XrInstance was created."
            : "Runtime negotiation or the global xrCreateInstance function lookup failed.")
        << "\"}";
    dlclose(handle);
    return env->NewStringUTF(out.str().c_str());
}

