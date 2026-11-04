#include "endxr_openxr_runtime.hpp"

#include <array>
#include <cstdio>
#include <cstring>
#include <cstdint>
#include <mutex>
#include <new>
#include <unordered_map>

struct XrInstance_T {
    XrVersion requestedApiVersion = XR_MAKE_VERSION(1, 0, 0);
    void* applicationVm = nullptr;
    void* applicationActivity = nullptr;
};

namespace {
constexpr XrSystemId kEndXrSystemId = 1;
constexpr std::uint32_t kRecommendedEyeWidth = 960;
constexpr std::uint32_t kRecommendedEyeHeight = 1080;
constexpr std::uint32_t kMaximumEyeWidth = 1200;
constexpr std::uint32_t kMaximumEyeHeight = 1200;
constexpr std::uint32_t kEndXrVendorId = 0x454E4458; // "ENDX"
constexpr XrVersion kEndXrApiVersion = XR_MAKE_VERSION(1, 0, 0);
constexpr const char* kRuntimeName = "EndXR Phone Runtime";
constexpr const char* kSystemName = "EndXR Phone VR";

std::mutex g_instanceMutex;
std::unordered_map<XrInstance, XrInstance_T*> g_instances;
std::uint64_t g_nextInstanceHandle = 1;

XrInstance makeInstanceHandle(XrInstance_T* object) {
#if XR_PTR_SIZE == 8
    return object;
#else
    (void)object;
    return g_nextInstanceHandle++;
#endif
}

struct BaseInStructure {
    XrStructureType type;
    const void* next;
};

bool isValidInstance(XrInstance instance) {
    if (instance == XR_NULL_HANDLE) return false;
    std::lock_guard<std::mutex> lock(g_instanceMutex);
    return g_instances.find(instance) != g_instances.end();
}

bool hasEnabledExtension(const XrInstanceCreateInfo* info, const char* extensionName) {
    for (std::uint32_t i = 0; i < info->enabledExtensionCount; ++i) {
        const char* candidate = info->enabledExtensionNames[i];
        if (candidate != nullptr && std::strcmp(candidate, extensionName) == 0) return true;
    }
    return false;
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

XRAPI_ATTR XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
);

XrResult XRAPI_CALL endxrEnumerateApiLayerProperties(
    std::uint32_t capacity,
    std::uint32_t* count,
    XrApiLayerProperties*
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *count = 0;
    (void)capacity;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL endxrEnumerateInstanceExtensionProperties(
    const char* layerName,
    std::uint32_t capacity,
    std::uint32_t* count,
    XrExtensionProperties* properties
) {
    if (count == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (layerName != nullptr && layerName[0] != '\0') {
        return XR_ERROR_API_LAYER_NOT_PRESENT;
    }
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (properties == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    if (properties[0].type != XR_TYPE_EXTENSION_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    std::snprintf(
        properties[0].extensionName,
        sizeof(properties[0].extensionName),
        "%s",
        ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME
    );
    properties[0].extensionVersion = ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION;
    return XR_SUCCESS;
}

XrResult XRAPI_CALL endxrCreateInstance(
    const XrInstanceCreateInfo* createInfo,
    XrInstance* instance
) {
    if (createInfo == nullptr || instance == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *instance = XR_NULL_HANDLE;
    if (createInfo->type != XR_TYPE_INSTANCE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (createInfo->enabledApiLayerCount != 0) return XR_ERROR_API_LAYER_NOT_PRESENT;
    if (createInfo->enabledExtensionCount > 0 && createInfo->enabledExtensionNames == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (createInfo->enabledExtensionCount > 1) return XR_ERROR_EXTENSION_NOT_PRESENT;

    const bool androidExtension = hasEnabledExtension(
        createInfo, ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME
    );
    if (createInfo->enabledExtensionCount > 0 && !androidExtension) {
        return XR_ERROR_EXTENSION_NOT_PRESENT;
    }
    const XrInstanceCreateInfoAndroidKHR* androidInfo = nullptr;
    if (androidExtension) {
        androidInfo = findAndroidCreateInfo(createInfo->next);
        if (androidInfo == nullptr ||
            androidInfo->applicationVM == nullptr ||
            androidInfo->applicationActivity == nullptr) {
            return XR_ERROR_VALIDATION_FAILURE;
        }
    }

    const XrVersion requested = createInfo->applicationInfo.apiVersion;
    if (XR_VERSION_MAJOR(requested) != 1 || XR_VERSION_MINOR(requested) > 0) {
        return XR_ERROR_API_VERSION_UNSUPPORTED;
    }

    XrInstance_T* created = new (std::nothrow) XrInstance_T();
    if (created == nullptr) return XR_ERROR_OUT_OF_MEMORY;
    created->requestedApiVersion = requested;
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

XrResult XRAPI_CALL endxrDestroyInstance(XrInstance instance) {
    XrInstance_T* object = nullptr;
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

XrResult XRAPI_CALL endxrGetInstanceProperties(
    XrInstance instance, XrInstanceProperties* properties
) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    if (properties == nullptr || properties->type != XR_TYPE_INSTANCE_PROPERTIES) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    properties->runtimeVersion = kEndXrApiVersion;
    std::snprintf(properties->runtimeName, sizeof(properties->runtimeName), "%s", kRuntimeName);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL endxrGetSystem(
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

XrResult XRAPI_CALL endxrGetSystemProperties(
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

XrResult XRAPI_CALL endxrEnumerateViewConfigurations(
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

XrResult XRAPI_CALL endxrGetViewConfigurationProperties(
    XrInstance instance, XrSystemId systemId,
    XrViewConfigurationType viewType,
    XrViewConfigurationProperties* properties
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

XrResult XRAPI_CALL endxrEnumerateViewConfigurationViews(
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

XrResult XRAPI_CALL endxrEnumerateEnvironmentBlendModes(
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

XrResult XRAPI_CALL endxrPollEvent(XrInstance instance, XrEventDataBuffer*) {
    if (!isValidInstance(instance)) return XR_ERROR_HANDLE_INVALID;
    return XR_EVENT_UNAVAILABLE;
}

XrResult XRAPI_CALL endxrResultToString(
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
        default: break;
    }
    std::snprintf(buffer, XR_MAX_RESULT_STRING_SIZE, "%s", label);
    return XR_SUCCESS;
}

XrResult XRAPI_CALL endxrStructureTypeToString(
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
        default: break;
    }
    std::snprintf(buffer, XR_MAX_STRUCTURE_NAME_SIZE, "%s", label);
    return XR_SUCCESS;
}

#define ENDXR_PROC(name, fn) {name, reinterpret_cast<PFN_xrVoidFunction>(fn)}
struct ProcEntry {
    const char* name;
    PFN_xrVoidFunction function;
};
const std::array<ProcEntry, 13> kProcTable{{
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
    ENDXR_PROC("xrGetViewConfigurationProperties", endxrGetViewConfigurationProperties)
}};
#undef ENDXR_PROC

XrResult XRAPI_CALL endxrGetInstanceProcAddr(
    XrInstance instance, const char* name, PFN_xrVoidFunction* function
) {
    if (function == nullptr || name == nullptr) return XR_ERROR_VALIDATION_FAILURE;
    *function = nullptr;
    if (instance != XR_NULL_HANDLE && !isValidInstance(instance)) {
        return XR_ERROR_HANDLE_INVALID;
    }
    for (const auto& entry : kProcTable) {
        if (std::strcmp(name, entry.name) == 0) {
            *function = entry.function;
            return XR_SUCCESS;
        }
    }
    if (std::strcmp(name, "xrEnumerateViewConfigurationViews") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(endxrEnumerateViewConfigurationViews);
        return XR_SUCCESS;
    }
    if (std::strcmp(name, "xrEnumerateEnvironmentBlendModes") == 0) {
        *function = reinterpret_cast<PFN_xrVoidFunction>(endxrEnumerateEnvironmentBlendModes);
        return XR_SUCCESS;
    }
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}
} // namespace

extern "C" __attribute__((visibility("default"))) XrResult XRAPI_CALL
xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
) {
    if (loaderInfo == nullptr || runtimeRequest == nullptr) {
        return XR_ERROR_VALIDATION_FAILURE;
    }
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
