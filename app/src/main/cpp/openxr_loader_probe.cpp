#include <jni.h>
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
#include <chrono>
#include <cstdio>
#include <mutex>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {
std::mutex loaderMutex;
bool loaderInitialized = false;
XrResult cachedLoaderInitResult = XR_ERROR_INITIALIZATION_FAILED;

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

struct OneFrameSubmission {
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

OneFrameSubmission submitOneGlesFrame(
    XrInstance instance,
    XrSession session,
    XrSwapchain swapchain,
    const std::vector<XrSwapchainImageOpenGLESKHR>& swapchainImages,
    uint32_t viewCount,
    uint32_t width,
    uint32_t height
) {
    XrSpace space = XR_NULL_HANDLE;
    bool sessionBegun = false;
    bool frameBegun = false;
    bool imageAcquired = false;
    uint32_t imageIndex = 0;
    XrFrameState frameState{};
    frameState.type = XR_TYPE_FRAME_STATE;

    auto stopSession = [&]() {
        if (sessionBegun) {
            (void)xrRequestExitSession(session);
            const XrResult stopping = waitForSessionState(
                instance, session, XR_SESSION_STATE_STOPPING, std::chrono::milliseconds(1500)
            );
            if (stopping == XR_SUCCESS) (void)xrEndSession(session);
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
        return OneFrameSubmission{status, result, message, false};
    };

    const XrResult readyResult = waitForSessionState(
        instance, session, XR_SESSION_STATE_READY, std::chrono::milliseconds(5000)
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

    XrFrameWaitInfo waitInfo{};
    waitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
    result = xrWaitFrame(session, &waitInfo, &frameState);
    if (result != XR_SUCCESS) {
        return fail("WAIT_FRAME_FAILED", result, "xrWaitFrame failed.");
    }

    XrFrameBeginInfo frameBeginInfo{};
    frameBeginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
    result = xrBeginFrame(session, &frameBeginInfo);
    if (result != XR_SUCCESS) {
        return fail("BEGIN_FRAME_FAILED", result, "xrBeginFrame failed.");
    }
    frameBegun = true;

    if (frameState.shouldRender == XR_FALSE) {
        return fail(
            "FRAME_NOT_RENDERED", XR_SUCCESS,
            "The runtime accepted the session but marked the first frame as not renderable."
        );
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

    XrSwapchainImageAcquireInfo acquireInfo{};
    acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
    result = xrAcquireSwapchainImage(swapchain, &acquireInfo, &imageIndex);
    if (result != XR_SUCCESS) {
        return fail("ACQUIRE_IMAGE_FAILED", result, "xrAcquireSwapchainImage failed.");
    }
    imageAcquired = true;

    XrSwapchainImageWaitInfo imageWaitInfo{};
    imageWaitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
    imageWaitInfo.timeout = XR_INFINITE_DURATION;
    result = xrWaitSwapchainImage(swapchain, &imageWaitInfo);
    if (result != XR_SUCCESS) {
        return fail("WAIT_IMAGE_FAILED", result, "xrWaitSwapchainImage failed.");
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
            GL_FRAMEBUFFER,
            GL_COLOR_ATTACHMENT0,
            swapchainImages[imageIndex].image,
            0,
            static_cast<GLint>(eye)
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
        return fail("END_FRAME_FAILED", result, "xrEndFrame rejected the stereo projection layer.");
    }

    stopSession();
    return OneFrameSubmission{
        "FIRST_FRAME_SUBMITTED_GLES",
        XR_SUCCESS,
        "A complete stereo GLES frame was submitted with xrEndFrame.",
        true
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
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
    createInfo.enabledExtensionCount = 1;
    createInfo.enabledExtensionNames = enabledExtensions;

    XrInstance instance = XR_NULL_HANDLE;
    const XrResult createResult = xrCreateInstance(&createInfo, &instance);
    if (createResult != XR_SUCCESS) {
        if (applicationContext != activityContext) env->DeleteLocalRef(applicationContext);
        return env->NewStringUTF(makeReport(
            "INSTANCE_CREATION_FAILED", initResult, enumResult, createResult,
            XR_ERROR_INITIALIZATION_FAILED, "", extensions,
            "Runtime был найден и перечислил расширения, но xrCreateInstance завершился ошибкой."
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
    createInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;
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

    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext previousContext = eglGetCurrentContext();
    EGLSurface previousDraw = eglGetCurrentSurface(EGL_DRAW);
    EGLSurface previousRead = eglGetCurrentSurface(EGL_READ);
    if (display == EGL_NO_DISPLAY || previousContext == EGL_NO_CONTEXT) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("EGL_CONTEXT_UNAVAILABLE", "The sandbox GL thread has no current EGL display/context.", XR_ERROR_GRAPHICS_DEVICE_INVALID).c_str());
    }

    const EGLint configAttributes[] = {
        EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
        EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8,
        EGL_NONE
    };
    EGLConfig config = nullptr;
    EGLint configCount = 0;
    if (eglChooseConfig(display, configAttributes, &config, 1, &configCount) != EGL_TRUE || configCount < 1) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("GLES3_EGL_CONFIG_UNAVAILABLE", "No EGL config supports an OpenGL ES 3 pbuffer.", XR_ERROR_GRAPHICS_DEVICE_INVALID).c_str());
    }

    const EGLint pbufferAttributes[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    EGLSurface pbuffer = eglCreatePbufferSurface(display, config, pbufferAttributes);
    if (pbuffer == EGL_NO_SURFACE) {
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("EGL_PBUFFER_FAILED", "Could not create a temporary EGL pbuffer.", XR_ERROR_GRAPHICS_DEVICE_INVALID).c_str());
    }
    const EGLint contextAttributes[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    EGLContext probeContext = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
    if (probeContext == EGL_NO_CONTEXT || eglMakeCurrent(display, pbuffer, pbuffer, probeContext) != EGL_TRUE) {
        if (probeContext != EGL_NO_CONTEXT) eglDestroyContext(display, probeContext);
        eglDestroySurface(display, pbuffer);
        xrDestroyInstance(instance);
        return env->NewStringUTF(report("GLES3_CONTEXT_FAILED", "Could not create or activate a temporary OpenGL ES 3 context.", XR_ERROR_GRAPHICS_DEVICE_INVALID).c_str());
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
        eglMakeCurrent(display, previousDraw, previousRead, previousContext);
        eglDestroyContext(display, probeContext);
        eglDestroySurface(display, pbuffer);
        xrDestroyInstance(instance);
        std::ostringstream message;
        message << "Temporary GLES context " << glVersion
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
    graphicsBinding.context = probeContext;
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
    OneFrameSubmission frameSubmission{
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
            frameSubmission = submitOneGlesFrame(
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
    eglMakeCurrent(display, previousDraw, previousRead, previousContext);
    eglDestroyContext(display, probeContext);
    eglDestroySurface(display, pbuffer);
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
            "FIRST_FRAME_SUBMITTED_GLES",
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
