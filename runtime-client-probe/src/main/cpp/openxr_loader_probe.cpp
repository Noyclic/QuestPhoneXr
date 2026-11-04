#include <jni.h>

#if defined(__ANDROID__)
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES3/gl3.h>
#endif

// openxr_platform.h refers to EGL types when the OpenGL ES extension is
// enabled, so the Android graphics headers must be visible first.
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <chrono>
#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
#include <thread>
#include <vector>

namespace {

const char* resultName(XrResult result) {
    switch (result) {
        case XR_SUCCESS: return "XR_SUCCESS";
        case XR_EVENT_UNAVAILABLE: return "XR_EVENT_UNAVAILABLE";
        case XR_ERROR_VALIDATION_FAILURE: return "XR_ERROR_VALIDATION_FAILURE";
        case XR_ERROR_RUNTIME_FAILURE: return "XR_ERROR_RUNTIME_FAILURE";
        case XR_ERROR_OUT_OF_MEMORY: return "XR_ERROR_OUT_OF_MEMORY";
        case XR_ERROR_API_VERSION_UNSUPPORTED: return "XR_ERROR_API_VERSION_UNSUPPORTED";
        case XR_ERROR_INITIALIZATION_FAILED: return "XR_ERROR_INITIALIZATION_FAILED";
        case XR_ERROR_FUNCTION_UNSUPPORTED: return "XR_ERROR_FUNCTION_UNSUPPORTED";
        case XR_ERROR_EXTENSION_NOT_PRESENT: return "XR_ERROR_EXTENSION_NOT_PRESENT";
        case XR_ERROR_LIMIT_REACHED: return "XR_ERROR_LIMIT_REACHED";
        case XR_ERROR_SIZE_INSUFFICIENT: return "XR_ERROR_SIZE_INSUFFICIENT";
        case XR_ERROR_HANDLE_INVALID: return "XR_ERROR_HANDLE_INVALID";
        case XR_ERROR_INSTANCE_LOST: return "XR_ERROR_INSTANCE_LOST";
        case XR_ERROR_SESSION_LOST: return "XR_ERROR_SESSION_LOST";
        case XR_ERROR_RUNTIME_UNAVAILABLE: return "XR_ERROR_RUNTIME_UNAVAILABLE";
        case XR_ERROR_FORM_FACTOR_UNSUPPORTED: return "XR_ERROR_FORM_FACTOR_UNSUPPORTED";
        case XR_ERROR_FORM_FACTOR_UNAVAILABLE: return "XR_ERROR_FORM_FACTOR_UNAVAILABLE";
        case XR_ERROR_API_LAYER_NOT_PRESENT: return "XR_ERROR_API_LAYER_NOT_PRESENT";
        case XR_ERROR_CALL_ORDER_INVALID: return "XR_ERROR_CALL_ORDER_INVALID";
        case XR_ERROR_GRAPHICS_DEVICE_INVALID: return "XR_ERROR_GRAPHICS_DEVICE_INVALID";
        case XR_ERROR_SYSTEM_INVALID: return "XR_ERROR_SYSTEM_INVALID";
        case XR_ERROR_PATH_INVALID: return "XR_ERROR_PATH_INVALID";
        case XR_ERROR_PATH_COUNT_EXCEEDED: return "XR_ERROR_PATH_COUNT_EXCEEDED";
        case XR_ERROR_PATH_FORMAT_INVALID: return "XR_ERROR_PATH_FORMAT_INVALID";
        case XR_ERROR_PATH_UNSUPPORTED: return "XR_ERROR_PATH_UNSUPPORTED";
        case XR_ERROR_LAYER_INVALID: return "XR_ERROR_LAYER_INVALID";
        case XR_ERROR_LAYER_LIMIT_EXCEEDED: return "XR_ERROR_LAYER_LIMIT_EXCEEDED";
        case XR_ERROR_SWAPCHAIN_RECT_INVALID: return "XR_ERROR_SWAPCHAIN_RECT_INVALID";
        case XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED: return "XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED";
        default: return "XR_RESULT_OTHER";
    }
}

std::string resultText(XrResult result) {
    std::ostringstream stream;
    stream << resultName(result) << " (" << static_cast<std::int32_t>(result) << ")";
    return stream.str();
}

const char* sessionStateName(XrSessionState state) {
    switch (state) {
        case XR_SESSION_STATE_UNKNOWN: return "UNKNOWN";
        case XR_SESSION_STATE_IDLE: return "IDLE";
        case XR_SESSION_STATE_READY: return "READY";
        case XR_SESSION_STATE_SYNCHRONIZED: return "SYNCHRONIZED";
        case XR_SESSION_STATE_VISIBLE: return "VISIBLE";
        case XR_SESSION_STATE_FOCUSED: return "FOCUSED";
        case XR_SESSION_STATE_STOPPING: return "STOPPING";
        case XR_SESSION_STATE_LOSS_PENDING: return "LOSS_PENDING";
        case XR_SESSION_STATE_EXITING: return "EXITING";
        default: return "SESSION_STATE_OTHER";
    }
}

bool waitForSessionState(
    XrInstance instance,
    XrSession session,
    XrSessionState desiredState,
    const char* stage,
    std::ostringstream& report
) {
    const auto deadline = std::chrono::steady_clock::now() +
        std::chrono::seconds(2);
    while (std::chrono::steady_clock::now() < deadline) {
        XrEventDataBuffer event{};
        event.type = XR_TYPE_EVENT_DATA_BUFFER;
        event.next = nullptr;
        const XrResult pollResult = xrPollEvent(instance, &event);
        if (pollResult == XR_EVENT_UNAVAILABLE) {
            std::this_thread::sleep_for(std::chrono::milliseconds(1));
            continue;
        }
        if (XR_FAILED(pollResult)) {
            report << stage << "_poll=" << resultText(pollResult) << "\n";
            return false;
        }
        if (event.type != XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            continue;
        }
        const auto* changed =
            reinterpret_cast<const XrEventDataSessionStateChanged*>(&event);
        report << stage << "_event_state=" << sessionStateName(changed->state) << "\n";
        if (changed->session != session) {
            continue;
        }
        if (changed->state == desiredState) {
            return true;
        }
        if (changed->state == XR_SESSION_STATE_EXITING ||
            changed->state == XR_SESSION_STATE_LOSS_PENDING) {
            return false;
        }
    }
    report << stage << "_timeout waiting_for="
           << sessionStateName(desiredState) << "\n";
    return false;
}

void copyName(char* destination, std::size_t destinationSize, const char* source) {
    if (destinationSize == 0) return;
    std::strncpy(destination, source, destinationSize - 1);
    destination[destinationSize - 1] = '\0';
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_xrclientprobe_OpenXrLoaderProbe_nativeRun(
    JNIEnv* environment,
    jobject /* probe */,
    jobject activity
) {
    std::ostringstream report;
    report << "Khronos OpenXR Android Loader probe\n";
    report << "probe_package=com.noyclic.xrclientprobe\n";
    report << "probe_scope=official Khronos Android loader in a separate application process\n";
    report << "loader_api_version=" << XR_CURRENT_API_VERSION << "\n";

    if (activity == nullptr) {
        report << "jni_activity=missing\n";
        const std::string value = report.str();
        return environment->NewStringUTF(value.c_str());
    }

    JavaVM* virtualMachine = nullptr;
    const jint vmStatus = environment->GetJavaVM(&virtualMachine);
    report << "jni_get_java_vm=" << (vmStatus == JNI_OK ? "PASS" : "FAIL") << "\n";
    if (vmStatus != JNI_OK || virtualMachine == nullptr) {
        report << "probe_result=cannot obtain JavaVM\n";
        const std::string value = report.str();
        return environment->NewStringUTF(value.c_str());
    }

    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    XrResult result = xrGetInstanceProcAddr(
        XR_NULL_HANDLE,
        "xrInitializeLoaderKHR",
        reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader)
    );
    report << "xrInitializeLoaderKHR lookup=" << resultText(result) << "\n";
    if (XR_FAILED(result) || initializeLoader == nullptr) {
        report << "probe_result=Khronos loader initialization entry point unavailable\n";
        const std::string value = report.str();
        return environment->NewStringUTF(value.c_str());
    }

    XrLoaderInitInfoAndroidKHR loaderInfo{};
    loaderInfo.type = XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR;
    loaderInfo.next = nullptr;
    loaderInfo.applicationVM = virtualMachine;
    loaderInfo.applicationContext = activity;
    result = initializeLoader(
        reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)
    );
    report << "xrInitializeLoaderKHR call=" << resultText(result) << "\n";
    if (XR_FAILED(result)) {
        report << "probe_result=loader initialization failed before runtime discovery\n";
        const std::string value = report.str();
        return environment->NewStringUTF(value.c_str());
    }

    std::uint32_t extensionCount = 0;
    const XrResult extensionCountResult = xrEnumerateInstanceExtensionProperties(
        nullptr, 0, &extensionCount, nullptr
    );
    report << "xrEnumerateInstanceExtensionProperties(count)="
           << resultText(extensionCountResult) << "\n";

    bool androidCreateInstanceExtensionFound = false;
    bool openGlesExtensionFound = false;
    if (XR_SUCCEEDED(extensionCountResult) && extensionCount > 0) {
        std::vector<XrExtensionProperties> extensions(extensionCount);
        for (auto& extension : extensions) {
            extension.type = XR_TYPE_EXTENSION_PROPERTIES;
            extension.next = nullptr;
        }
        std::uint32_t returnedCount = 0;
        const XrResult extensionListResult = xrEnumerateInstanceExtensionProperties(
            nullptr,
            static_cast<std::uint32_t>(extensions.size()),
            &returnedCount,
            extensions.data()
        );
        report << "xrEnumerateInstanceExtensionProperties(list)="
               << resultText(extensionListResult) << "\n";
        if (XR_SUCCEEDED(extensionListResult)) {
            if (returnedCount < extensions.size()) extensions.resize(returnedCount);
            for (const auto& extension : extensions) {
                report << "runtime_extension=" << extension.extensionName
                       << " spec=" << extension.extensionVersion << "\n";
                if (std::strcmp(
                        extension.extensionName,
                        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME
                    ) == 0) {
                    androidCreateInstanceExtensionFound = true;
                } else if (std::strcmp(
                               extension.extensionName,
                               XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME
                           ) == 0) {
                    openGlesExtensionFound = true;
                }
            }
        }
    } else {
        report << "runtime_extension_list=unavailable (loader may not have an active runtime)\n";
    }
    report << "runtime_advertises_XR_KHR_android_create_instance="
           << (androidCreateInstanceExtensionFound ? "YES" : "NO_OR_UNAVAILABLE")
           << "\n";
    report << "runtime_advertises_XR_KHR_opengl_es_enable="
           << (openGlesExtensionFound ? "YES" : "NO_OR_UNAVAILABLE")
           << "\n";

    XrInstanceCreateInfoAndroidKHR androidCreateInfo{};
    androidCreateInfo.type = XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR;
    androidCreateInfo.next = nullptr;
    androidCreateInfo.applicationVM = virtualMachine;
    androidCreateInfo.applicationActivity = activity;

    XrInstanceCreateInfo instanceCreateInfo{};
    instanceCreateInfo.type = XR_TYPE_INSTANCE_CREATE_INFO;
    instanceCreateInfo.next = &androidCreateInfo;
    copyName(
        instanceCreateInfo.applicationInfo.applicationName,
        XR_MAX_APPLICATION_NAME_SIZE,
        "QuestPhoneXR External Loader Probe"
    );
    instanceCreateInfo.applicationInfo.applicationVersion = 1;
    copyName(
        instanceCreateInfo.applicationInfo.engineName,
        XR_MAX_ENGINE_NAME_SIZE,
        "QuestPhoneXR"
    );
    instanceCreateInfo.applicationInfo.engineVersion = 1;
    instanceCreateInfo.applicationInfo.apiVersion = XR_CURRENT_API_VERSION;

    const char* enabledExtensions[] = {
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME
    };
    instanceCreateInfo.enabledExtensionCount = openGlesExtensionFound ? 2u : 1u;
    instanceCreateInfo.enabledExtensionNames = enabledExtensions;

    XrInstance instance = XR_NULL_HANDLE;
    const XrResult createInstanceResult = xrCreateInstance(
        &instanceCreateInfo,
        &instance
    );
    report << "xrCreateInstance=" << resultText(createInstanceResult) << "\n";

    if (XR_FAILED(createInstanceResult) || instance == XR_NULL_HANDLE) {
        report << "runtime_loaded=NO\n";
        report << "runtime_instance_created=NO\n";
        report << "interpretation=check the active Android OpenXR RuntimeBroker; "
                  "service visibility alone does not select a runtime\n";
        const std::string value = report.str();
        return environment->NewStringUTF(value.c_str());
    }

    report << "runtime_loaded=YES\n";
    report << "runtime_instance_created=YES\n";

    XrInstanceProperties instanceProperties{};
    instanceProperties.type = XR_TYPE_INSTANCE_PROPERTIES;
    instanceProperties.next = nullptr;
    const XrResult propertiesResult = xrGetInstanceProperties(
        instance, &instanceProperties
    );
    report << "xrGetInstanceProperties=" << resultText(propertiesResult) << "\n";
    if (XR_SUCCEEDED(propertiesResult)) {
        report << "runtime_name=" << instanceProperties.runtimeName << "\n";
        report << "runtime_version=" << XR_VERSION_MAJOR(instanceProperties.runtimeVersion)
               << "." << XR_VERSION_MINOR(instanceProperties.runtimeVersion)
               << "." << XR_VERSION_PATCH(instanceProperties.runtimeVersion) << "\n";
    }

    XrSystemGetInfo systemInfo{};
    systemInfo.type = XR_TYPE_SYSTEM_GET_INFO;
    systemInfo.next = nullptr;
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    const XrResult getSystemResult = xrGetSystem(
        instance, &systemInfo, &systemId
    );
    report << "xrGetSystem(HMD)=" << resultText(getSystemResult) << "\n";

    if (XR_SUCCEEDED(getSystemResult)) {
        XrSystemProperties systemProperties{};
        systemProperties.type = XR_TYPE_SYSTEM_PROPERTIES;
        systemProperties.next = nullptr;
        const XrResult systemPropertiesResult = xrGetSystemProperties(
            instance, systemId, &systemProperties
        );
        report << "xrGetSystemProperties=" << resultText(systemPropertiesResult) << "\n";
        if (XR_SUCCEEDED(systemPropertiesResult)) {
            report << "xr_system_name=" << systemProperties.systemName << "\n";
            report << "xr_system_vendor_id=" << systemProperties.vendorId << "\n";
            report << "xr_system_max_swapchain_width="
                   << systemProperties.graphicsProperties.maxSwapchainImageWidth << "\n";
            report << "xr_system_max_swapchain_height="
                   << systemProperties.graphicsProperties.maxSwapchainImageHeight << "\n";
        }

        std::uint32_t viewConfigurationCount = 0;
        const XrResult viewCountResult = xrEnumerateViewConfigurations(
            instance, systemId, 0, &viewConfigurationCount, nullptr
        );
        report << "xrEnumerateViewConfigurations(count)="
               << resultText(viewCountResult) << "\n";
        if (XR_SUCCEEDED(viewCountResult) && viewConfigurationCount > 0) {
            std::vector<XrViewConfigurationType> viewConfigurations(
                viewConfigurationCount
            );
            std::uint32_t returnedViewCount = 0;
            const XrResult viewListResult = xrEnumerateViewConfigurations(
                instance,
                systemId,
                static_cast<std::uint32_t>(viewConfigurations.size()),
                &returnedViewCount,
                viewConfigurations.data()
            );
            report << "xrEnumerateViewConfigurations(list)="
                   << resultText(viewListResult) << "\n";

            bool stereoFound = false;
            if (XR_SUCCEEDED(viewListResult)) {
                for (std::uint32_t index = 0;
                     index < returnedViewCount && index < viewConfigurations.size();
                     ++index) {
                    if (viewConfigurations[index] ==
                        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) {
                        stereoFound = true;
                        break;
                    }
                }
            }
            report << "primary_stereo_supported="
                   << (stereoFound ? "YES" : "NO") << "\n";

            if (stereoFound) {
                std::uint32_t viewCount = 0;
                const XrResult stereoCountResult = xrEnumerateViewConfigurationViews(
                    instance,
                    systemId,
                    XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                    0,
                    &viewCount,
                    nullptr
                );
                report << "xrEnumerateViewConfigurationViews(count)="
                       << resultText(stereoCountResult) << "\n";
                if (XR_SUCCEEDED(stereoCountResult) && viewCount > 0) {
                    std::vector<XrViewConfigurationView> views(viewCount);
                    for (auto& view : views) {
                        view.type = XR_TYPE_VIEW_CONFIGURATION_VIEW;
                        view.next = nullptr;
                    }
                    std::uint32_t returnedViews = 0;
                    const XrResult stereoListResult = xrEnumerateViewConfigurationViews(
                        instance,
                        systemId,
                        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO,
                        static_cast<std::uint32_t>(views.size()),
                        &returnedViews,
                        views.data()
                    );
                    report << "xrEnumerateViewConfigurationViews(list)="
                           << resultText(stereoListResult) << "\n";
                    if (XR_SUCCEEDED(stereoListResult)) {
                        for (std::uint32_t index = 0;
                             index < returnedViews && index < views.size();
                             ++index) {
                            report << "stereo_view_" << index
                                   << "_recommended="
                                   << views[index].recommendedImageRectWidth
                                   << "x"
                                   << views[index].recommendedImageRectHeight
                                   << "\n";
                        }
                    }
                }
            }
        }
    }

    // Exercise a real GLES-bound session from this independent Android process.
    // This is intentionally separate from the in-app Sandbox EGL context.
    bool externalGlesSessionAttempted = false;
    bool externalFrameLifecycleAttempted = false;
#if defined(__ANDROID__)
    if (!openGlesExtensionFound) {
        report << "external_gles_session=SKIPPED (runtime did not advertise XR_KHR_opengl_es_enable)\n";
    } else {
        externalGlesSessionAttempted = true;
        EGLDisplay display = eglGetDisplay(EGL_DEFAULT_DISPLAY);
        EGLint eglMajor = 0;
        EGLint eglMinor = 0;
        const EGLBoolean initialized = display != EGL_NO_DISPLAY
            ? eglInitialize(display, &eglMajor, &eglMinor)
            : EGL_FALSE;
        report << "external_egl_initialize="
               << (initialized == EGL_TRUE ? "PASS" : "FAIL")
               << " version=" << eglMajor << "." << eglMinor << "\n";

        EGLConfig config = nullptr;
        EGLint configCount = 0;
        const EGLint configAttributes[] = {
            EGL_SURFACE_TYPE, EGL_PBUFFER_BIT,
            EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR,
            EGL_RED_SIZE, 8,
            EGL_GREEN_SIZE, 8,
            EGL_BLUE_SIZE, 8,
            EGL_ALPHA_SIZE, 8,
            EGL_NONE
        };
        const EGLBoolean choseConfig = initialized == EGL_TRUE
            ? eglChooseConfig(display, configAttributes, &config, 1, &configCount)
            : EGL_FALSE;
        EGLSurface surface = EGL_NO_SURFACE;
        EGLContext context = EGL_NO_CONTEXT;
        if (choseConfig == EGL_TRUE && configCount > 0) {
            const EGLint surfaceAttributes[] = {
                EGL_WIDTH, 64, EGL_HEIGHT, 64, EGL_NONE
            };
            surface = eglCreatePbufferSurface(display, config, surfaceAttributes);
            const EGLint contextAttributes[] = {
                EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE
            };
            context = eglCreateContext(display, config, EGL_NO_CONTEXT, contextAttributes);
        }
        const EGLBoolean madeCurrent =
            surface != EGL_NO_SURFACE && context != EGL_NO_CONTEXT
                ? eglMakeCurrent(display, surface, surface, context)
                : EGL_FALSE;
        report << "external_egl_context="
               << (madeCurrent == EGL_TRUE ? "PASS" : "FAIL")
               << " egl_error=0x" << std::hex << eglGetError() << std::dec << "\n";

        if (madeCurrent == EGL_TRUE) {
            PFN_xrGetOpenGLESGraphicsRequirementsKHR getGlesRequirements = nullptr;
            XrResult lookupResult = xrGetInstanceProcAddr(
                instance,
                "xrGetOpenGLESGraphicsRequirementsKHR",
                reinterpret_cast<PFN_xrVoidFunction*>(&getGlesRequirements)
            );
            report << "xrGetOpenGLESGraphicsRequirementsKHR lookup="
                   << resultText(lookupResult) << "\n";

            XrGraphicsRequirementsOpenGLESKHR graphicsRequirements{};
            graphicsRequirements.type = XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR;
            XrResult graphicsResult = XR_ERROR_FUNCTION_UNSUPPORTED;
            if (XR_SUCCEEDED(lookupResult) && getGlesRequirements != nullptr) {
                graphicsResult = getGlesRequirements(instance, systemId, &graphicsRequirements);
            }
            report << "xrGetOpenGLESGraphicsRequirementsKHR="
                   << resultText(graphicsResult) << "\n";

            XrSession session = XR_NULL_HANDLE;
            bool sessionRunning = false;
            XrSwapchain swapchain = XR_NULL_HANDLE;
            if (XR_SUCCEEDED(graphicsResult)) {
                XrGraphicsBindingOpenGLESAndroidKHR graphicsBinding{};
                graphicsBinding.type = XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR;
                graphicsBinding.next = nullptr;
                graphicsBinding.display = display;
                graphicsBinding.config = config;
                graphicsBinding.context = context;

                XrSessionCreateInfo sessionCreateInfo{};
                sessionCreateInfo.type = XR_TYPE_SESSION_CREATE_INFO;
                sessionCreateInfo.next = &graphicsBinding;
                sessionCreateInfo.systemId = systemId;
                const XrResult sessionResult = xrCreateSession(
                    instance, &sessionCreateInfo, &session
                );
                report << "external_xrCreateSession=" << resultText(sessionResult) << "\n";

                if (XR_SUCCEEDED(sessionResult) && session != XR_NULL_HANDLE) {
                    const bool readyEventObserved = waitForSessionState(
                        instance,
                        session,
                        XR_SESSION_STATE_READY,
                        "external_wait_ready",
                        report
                    );
                    report << "external_session_ready_event="
                           << (readyEventObserved ? "OBSERVED" : "NOT_OBSERVED") << "\n";
                    if (readyEventObserved) {
                    XrSessionBeginInfo beginInfo{};
                    beginInfo.type = XR_TYPE_SESSION_BEGIN_INFO;
                    beginInfo.primaryViewConfigurationType =
                        XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                    const XrResult beginResult = xrBeginSession(session, &beginInfo);
                    sessionRunning = XR_SUCCEEDED(beginResult);
                    report << "external_xrBeginSession=" << resultText(beginResult) << "\n";

                    if (sessionRunning) {
                        XrSwapchainCreateInfo swapchainInfo{};
                        swapchainInfo.type = XR_TYPE_SWAPCHAIN_CREATE_INFO;
                        swapchainInfo.next = nullptr;
                        swapchainInfo.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT;
                        swapchainInfo.format = static_cast<std::int64_t>(GL_RGBA8);
                        swapchainInfo.sampleCount = 1;
                        swapchainInfo.width = 64;
                        swapchainInfo.height = 64;
                        swapchainInfo.faceCount = 1;
                        swapchainInfo.arraySize = 1;
                        swapchainInfo.mipCount = 1;
                        const XrResult swapchainResult = xrCreateSwapchain(
                            session, &swapchainInfo, &swapchain
                        );
                        report << "external_xrCreateSwapchain="
                               << resultText(swapchainResult) << "\n";

                        if (XR_SUCCEEDED(swapchainResult) && swapchain != XR_NULL_HANDLE) {
                            std::uint32_t imageCount = 0;
                            XrResult imageResult = xrEnumerateSwapchainImages(
                                swapchain, 0, &imageCount, nullptr
                            );
                            std::vector<XrSwapchainImageOpenGLESKHR> images(imageCount);
                            for (auto& image : images) {
                                image.type = XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR;
                                image.next = nullptr;
                            }
                            if (XR_SUCCEEDED(imageResult) && imageCount > 0) {
                                imageResult = xrEnumerateSwapchainImages(
                                    swapchain, imageCount, &imageCount,
                                    reinterpret_cast<XrSwapchainImageBaseHeader*>(images.data())
                                );
                            }
                            report << "external_xrEnumerateSwapchainImages="
                                   << resultText(imageResult)
                                   << " count=" << imageCount << "\n";

                            std::uint32_t imageIndex = 0;
                            XrSwapchainImageAcquireInfo acquireInfo{};
                            acquireInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO;
                            const XrResult acquireResult = xrAcquireSwapchainImage(
                                swapchain, &acquireInfo, &imageIndex
                            );
                            report << "external_xrAcquireSwapchainImage="
                                   << resultText(acquireResult)
                                   << " index=" << imageIndex << "\n";
                            if (XR_SUCCEEDED(acquireResult)) {
                                XrSwapchainImageWaitInfo imageWaitInfo{};
                                imageWaitInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO;
                                imageWaitInfo.timeout = XR_INFINITE_DURATION;
                                const XrResult waitImageResult = xrWaitSwapchainImage(
                                    swapchain, &imageWaitInfo
                                );
                                report << "external_xrWaitSwapchainImage="
                                       << resultText(waitImageResult) << "\n";
                                if (XR_SUCCEEDED(waitImageResult)) {
                                    XrSwapchainImageReleaseInfo releaseInfo{};
                                    releaseInfo.type = XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO;
                                    const XrResult releaseResult = xrReleaseSwapchainImage(
                                        swapchain, &releaseInfo
                                    );
                                    report << "external_xrReleaseSwapchainImage="
                                           << resultText(releaseResult) << "\n";
                                }
                            }
                        }

                        externalFrameLifecycleAttempted = true;
                        XrFrameWaitInfo frameWaitInfo{};
                        frameWaitInfo.type = XR_TYPE_FRAME_WAIT_INFO;
                        XrFrameState frameState{};
                        frameState.type = XR_TYPE_FRAME_STATE;
                        const XrResult waitFrameResult = xrWaitFrame(
                            session, &frameWaitInfo, &frameState
                        );
                        report << "external_xrWaitFrame="
                               << resultText(waitFrameResult) << "\n";
                        if (XR_SUCCEEDED(waitFrameResult)) {
                            XrFrameBeginInfo frameBeginInfo{};
                            frameBeginInfo.type = XR_TYPE_FRAME_BEGIN_INFO;
                            const XrResult beginFrameResult = xrBeginFrame(
                                session, &frameBeginInfo
                            );
                            report << "external_xrBeginFrame="
                                   << resultText(beginFrameResult) << "\n";
                            if (XR_SUCCEEDED(beginFrameResult)) {
                                XrFrameEndInfo frameEndInfo{};
                                frameEndInfo.type = XR_TYPE_FRAME_END_INFO;
                                frameEndInfo.displayTime = frameState.predictedDisplayTime;
                                frameEndInfo.environmentBlendMode =
                                    XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
                                frameEndInfo.layerCount = 0;
                                frameEndInfo.layers = nullptr;
                                const XrResult endFrameResult = xrEndFrame(
                                    session, &frameEndInfo
                                );
                                report << "external_xrEndFrame="
                                       << resultText(endFrameResult) << "\n";
                            }
                        }
                    }
                    }

                    if (swapchain != XR_NULL_HANDLE) {
                        report << "external_xrDestroySwapchain="
                               << resultText(xrDestroySwapchain(swapchain)) << "\n";
                    }
                    if (sessionRunning) {
                        const XrResult exitResult = xrRequestExitSession(session);
                        report << "external_xrRequestExitSession="
                               << resultText(exitResult) << "\n";
                        if (XR_SUCCEEDED(exitResult)) {
                            const bool stoppingEventObserved = waitForSessionState(
                                instance,
                                session,
                                XR_SESSION_STATE_STOPPING,
                                "external_wait_stopping",
                                report
                            );
                            report << "external_session_stopping_event="
                                   << (stoppingEventObserved ? "OBSERVED" : "NOT_OBSERVED")
                                   << "\n";
                            if (stoppingEventObserved) {
                                report << "external_xrEndSession="
                                       << resultText(xrEndSession(session)) << "\n";
                            } else {
                                report << "external_xrEndSession=SKIPPED (STOPPING event not observed)\n";
                            }
                        }
                    }
                    report << "external_xrDestroySession="
                           << resultText(xrDestroySession(session)) << "\n";
                }
            }
        } else {
            report << "external_gles_session=SKIPPED (could not create a current GLES 3 context)\n";
        }

        if (display != EGL_NO_DISPLAY) {
            eglMakeCurrent(display, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
            if (context != EGL_NO_CONTEXT) eglDestroyContext(display, context);
            if (surface != EGL_NO_SURFACE) eglDestroySurface(display, surface);
            if (initialized == EGL_TRUE) eglTerminate(display);
        }
    }
#else
    report << "external_gles_session=SKIPPED (not an Android build)\n";
#endif

    const XrResult destroyResult = xrDestroyInstance(instance);
    report << "xrDestroyInstance=" << resultText(destroyResult) << "\n";
    report << "external_gles_session_attempted="
           << (externalGlesSessionAttempted ? "YES" : "NO") << "\n";
    report << "external_frame_lifecycle_attempted="
           << (externalFrameLifecycleAttempted ? "YES" : "NO") << "\n";
    report << "probe_result=external lifecycle attempts completed; inspect each stage above; "
              "no projection layer was submitted\n";

    const std::string value = report.str();
    return environment->NewStringUTF(value.c_str());
}
