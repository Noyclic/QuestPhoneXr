#include <jni.h>

#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstdint>
#include <cstring>
#include <sstream>
#include <string>
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

    XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
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
                }
            }
        }
    } else {
        report << "runtime_extension_list=unavailable (loader may not have an active runtime)\n";
    }
    report << "runtime_advertises_XR_KHR_android_create_instance="
           << (androidCreateInstanceExtensionFound ? "YES" : "NO_OR_UNAVAILABLE")
           << "\n";

    XrInstanceCreateInfoAndroidKHR androidCreateInfo{
        XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR
    };
    androidCreateInfo.applicationVM = virtualMachine;
    androidCreateInfo.applicationActivity = activity;

    XrInstanceCreateInfo instanceCreateInfo{XR_TYPE_INSTANCE_CREATE_INFO};
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
        XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME
    };
    instanceCreateInfo.enabledExtensionCount = 1;
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

    XrInstanceProperties instanceProperties{XR_TYPE_INSTANCE_PROPERTIES};
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

    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId systemId = XR_NULL_SYSTEM_ID;
    const XrResult getSystemResult = xrGetSystem(
        instance, &systemInfo, &systemId
    );
    report << "xrGetSystem(HMD)=" << resultText(getSystemResult) << "\n";

    if (XR_SUCCEEDED(getSystemResult)) {
        XrSystemProperties systemProperties{XR_TYPE_SYSTEM_PROPERTIES};
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

    const XrResult destroyResult = xrDestroyInstance(instance);
    report << "xrDestroyInstance=" << resultText(destroyResult) << "\n";
    report << "probe_result=loader completed runtime instance checks; "
              "no session or rendered frame was attempted\n";

    const std::string value = report.str();
    return environment->NewStringUTF(value.c_str());
}
