#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <mutex>
#include <sstream>
#include <string>
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
    const std::vector<XrExtensionProperties>& extensions,
    const std::string& message
) {
    std::ostringstream out;
    out << "{\"status\":\"" << status << "\","
        << "\"loaderInitResult\":" << static_cast<int>(loaderInit) << ","
        << "\"extensionEnumerationResult\":" << static_cast<int>(enumeration) << ","
        << "\"message\":\"" << jsonEscape(message) << "\","
        << "\"extensions\":[";
    for (size_t i = 0; i < extensions.size(); ++i) {
        if (i != 0) out << ",";
        out << "\"" << jsonEscape(extensions[i].extensionName) << "\"";
    }
    out << "]}";
    return out.str();
}
} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_OpenXrLoaderProbe_nativeProbe(
    JNIEnv* env,
    jobject /* thiz */,
    jobject context
) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK || vm == nullptr || context == nullptr) {
        return env->NewStringUTF("{\"status\":\"ANDROID_CONTEXT_UNAVAILABLE\",\"message\":\"JNI JavaVM or Android Context is unavailable\",\"extensions\":[]}");
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
                initInfo.applicationContext = context;
                cachedLoaderInitResult = initializeLoader(
                    reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&initInfo)
                );
                loaderInitialized = cachedLoaderInitResult == XR_SUCCESS;
            }
        }
        initResult = cachedLoaderInitResult;
    }

    if (initResult != XR_SUCCESS) {
        return env->NewStringUTF(makeReport(
            "LOADER_INITIALIZATION_FAILED", initResult, XR_ERROR_INITIALIZATION_FAILED,
            {}, "xrInitializeLoaderKHR did not complete successfully."
        ).c_str());
    }

    uint32_t extensionCount = 0;
    XrResult enumResult = xrEnumerateInstanceExtensionProperties(
        nullptr, 0, &extensionCount, nullptr
    );
    if (enumResult != XR_SUCCESS) {
        return env->NewStringUTF(makeReport(
            "RUNTIME_ENUMERATION_FAILED", initResult, enumResult, {},
            "OpenXR loader initialized, but the active runtime did not provide extension enumeration."
        ).c_str());
    }

    std::vector<XrExtensionProperties> extensions(extensionCount);
    for (auto& extension : extensions) {
        extension.type = XR_TYPE_EXTENSION_PROPERTIES;
        extension.next = nullptr;
    }

    if (extensionCount > 0) {
        enumResult = xrEnumerateInstanceExtensionProperties(
            nullptr, extensionCount, &extensions[0], &extensionCount
        );
        if (enumResult != XR_SUCCESS) {
            return env->NewStringUTF(makeReport(
                "RUNTIME_ENUMERATION_FAILED", initResult, enumResult, {},
                "The runtime extension list could not be read."
            ).c_str());
        }
        extensions.resize(extensionCount);
    }

    return env->NewStringUTF(makeReport(
        "RUNTIME_FOUND", initResult, enumResult, extensions,
        "OpenXR loader initialized and active runtime extension enumeration succeeded. No instance, session, swapchain or frame was created."
    ).c_str());
}
