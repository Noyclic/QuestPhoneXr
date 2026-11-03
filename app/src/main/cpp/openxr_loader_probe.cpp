#include <jni.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <algorithm>
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
