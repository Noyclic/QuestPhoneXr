#include <jni.h>
#include <vulkan/vulkan.h>
#include <vulkan/vulkan_android.h>

#include <algorithm>
#include <cstdint>
#include <sstream>
#include <string>
#include <vector>

namespace {

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
                    const char* hex = "0123456789abcdef";
                    out << "\\u00" << hex[(ch >> 4) & 0xf] << hex[ch & 0xf];
                } else {
                    out << static_cast<char>(ch);
                }
        }
    }
    return out.str();
}

bool hasExtension(const std::vector<VkExtensionProperties>& extensions, const char* name) {
    return std::any_of(extensions.begin(), extensions.end(), [name](const auto& item) {
        return std::string(item.extensionName) == name;
    });
}

const char* resultName(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        default: return "VK_ERROR_OR_OTHER";
    }
}

std::string versionString(uint32_t version) {
    std::ostringstream out;
    out << VK_VERSION_MAJOR(version) << "."
        << VK_VERSION_MINOR(version) << "."
        << VK_VERSION_PATCH(version);
    return out.str();
}

struct ExternalChecks {
    bool queried = false;
    bool importable = false;
    bool exportable = false;
};

ExternalChecks queryBufferHandle(
    VkPhysicalDevice physicalDevice,
    VkExternalMemoryHandleTypeFlagBits handleType
) {
    ExternalChecks result;
#if defined(VK_VERSION_1_1)
    VkPhysicalDeviceExternalBufferInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_BUFFER_INFO;
    info.flags = 0;
    info.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT;
    info.handleType = handleType;

    VkExternalBufferProperties properties{};
    properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_BUFFER_PROPERTIES;
    vkGetPhysicalDeviceExternalBufferProperties(physicalDevice, &info, &properties);
    result.queried = true;
    const auto features = properties.externalMemoryProperties.externalMemoryFeatures;
    result.importable = (features & VK_EXTERNAL_MEMORY_FEATURE_IMPORTABLE_BIT) != 0;
    result.exportable = (features & VK_EXTERNAL_MEMORY_FEATURE_EXPORTABLE_BIT) != 0;
#endif
    return result;
}

ExternalChecks querySemaphoreHandle(
    VkPhysicalDevice physicalDevice,
    VkExternalSemaphoreHandleTypeFlagBits handleType
) {
    ExternalChecks result;
#if defined(VK_VERSION_1_1)
    VkPhysicalDeviceExternalSemaphoreInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_SEMAPHORE_INFO;
    info.handleType = handleType;

    VkExternalSemaphoreProperties properties{};
    properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_SEMAPHORE_PROPERTIES;
    vkGetPhysicalDeviceExternalSemaphoreProperties(physicalDevice, &info, &properties);
    result.queried = true;
    result.importable = (properties.externalSemaphoreFeatures &
        VK_EXTERNAL_SEMAPHORE_FEATURE_IMPORTABLE_BIT) != 0;
    result.exportable = (properties.externalSemaphoreFeatures &
        VK_EXTERNAL_SEMAPHORE_FEATURE_EXPORTABLE_BIT) != 0;
#endif
    return result;
}

ExternalChecks queryFenceHandle(
    VkPhysicalDevice physicalDevice,
    VkExternalFenceHandleTypeFlagBits handleType
) {
    ExternalChecks result;
#if defined(VK_VERSION_1_1)
    VkPhysicalDeviceExternalFenceInfo info{};
    info.sType = VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_EXTERNAL_FENCE_INFO;
    info.handleType = handleType;

    VkExternalFenceProperties properties{};
    properties.sType = VK_STRUCTURE_TYPE_EXTERNAL_FENCE_PROPERTIES;
    vkGetPhysicalDeviceExternalFenceProperties(physicalDevice, &info, &properties);
    result.queried = true;
    result.importable = (properties.externalFenceFeatures &
        VK_EXTERNAL_FENCE_FEATURE_IMPORTABLE_BIT) != 0;
    result.exportable = (properties.externalFenceFeatures &
        VK_EXTERNAL_FENCE_FEATURE_EXPORTABLE_BIT) != 0;
#endif
    return result;
}

void appendCheck(std::ostringstream& out, const char* name, const ExternalChecks& check) {
    out << "{\"name\":\"" << name << "\",\"queried\":"
        << (check.queried ? "true" : "false")
        << ",\"importable\":" << (check.importable ? "true" : "false")
        << ",\"exportable\":" << (check.exportable ? "true" : "false") << "}";
}

std::string probe() {
    uint32_t loaderVersion = VK_API_VERSION_1_0;
#if defined(VK_VERSION_1_1)
    const VkResult versionResult = vkEnumerateInstanceVersion(&loaderVersion);
    if (versionResult != VK_SUCCESS) loaderVersion = VK_API_VERSION_1_0;
#endif

    VkApplicationInfo appInfo{};
    appInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
    appInfo.pApplicationName = "QuestPhoneXR Vulkan Probe";
    appInfo.applicationVersion = 1;
    appInfo.pEngineName = "QuestPhoneXR";
    appInfo.engineVersion = 1;
    appInfo.apiVersion = std::min(loaderVersion, static_cast<uint32_t>(VK_API_VERSION_1_1));

    VkInstanceCreateInfo createInfo{};
    createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
    createInfo.pApplicationInfo = &appInfo;

    VkInstance instance = VK_NULL_HANDLE;
    const VkResult createResult = vkCreateInstance(&createInfo, nullptr, &instance);
    if (createResult != VK_SUCCESS) {
        std::ostringstream out;
        out << "{\"available\":false,\"status\":\"INSTANCE_CREATE_FAILED\","
            << "\"message\":\"vkCreateInstance: " << resultName(createResult) << "\","
            << "\"loaderVersion\":\"" << versionString(loaderVersion) << "\",\"devices\":[]}";
        return out.str();
    }

    uint32_t deviceCount = 0;
    VkResult enumerateResult = vkEnumeratePhysicalDevices(instance, &deviceCount, nullptr);
    if (enumerateResult != VK_SUCCESS || deviceCount == 0) {
        vkDestroyInstance(instance, nullptr);
        std::ostringstream out;
        out << "{\"available\":false,\"status\":\"NO_PHYSICAL_DEVICE\","
            << "\"message\":\"No Vulkan physical device was enumerated\","
            << "\"loaderVersion\":\"" << versionString(loaderVersion) << "\",\"devices\":[]}";
        return out.str();
    }

    std::vector<VkPhysicalDevice> physicalDevices(deviceCount);
    enumerateResult = vkEnumeratePhysicalDevices(instance, &deviceCount, physicalDevices.data());
    if (enumerateResult != VK_SUCCESS) {
        vkDestroyInstance(instance, nullptr);
        return "{\"available\":false,\"status\":\"DEVICE_ENUMERATION_FAILED\","
               "\"message\":\"vkEnumeratePhysicalDevices failed\",\"devices\":[]}";
    }

    std::ostringstream out;
    out << "{\"available\":true,\"status\":\"OK\",\"message\":"
        << "\"Native Vulkan enumeration completed; this is not a compositor test.\","
        << "\"loaderVersion\":\"" << versionString(loaderVersion) << "\",\"devices\":[";
    bool firstDevice = true;

    for (VkPhysicalDevice physicalDevice : physicalDevices) {
        VkPhysicalDeviceProperties properties{};
        vkGetPhysicalDeviceProperties(physicalDevice, &properties);

        uint32_t extensionCount = 0;
        VkResult extensionResult = vkEnumerateDeviceExtensionProperties(
            physicalDevice, nullptr, &extensionCount, nullptr);
        std::vector<VkExtensionProperties> extensions;
        if (extensionResult == VK_SUCCESS && extensionCount > 0) {
            extensions.resize(extensionCount);
            extensionResult = vkEnumerateDeviceExtensionProperties(
                physicalDevice, nullptr, &extensionCount, extensions.data());
            if (extensionResult != VK_SUCCESS) extensions.clear();
        }

        const bool api11 = VK_VERSION_MAJOR(properties.apiVersion) > 1 ||
            (VK_VERSION_MAJOR(properties.apiVersion) == 1 &&
             VK_VERSION_MINOR(properties.apiVersion) >= 1);
        const bool extMemoryFd = hasExtension(extensions, VK_KHR_EXTERNAL_MEMORY_FD_EXTENSION_NAME);
        const bool extSemaphoreFd = hasExtension(extensions, VK_KHR_EXTERNAL_SEMAPHORE_FD_EXTENSION_NAME);
        const bool extFenceFd = hasExtension(extensions, VK_KHR_EXTERNAL_FENCE_FD_EXTENSION_NAME);
        const bool extAhb = hasExtension(extensions, VK_ANDROID_EXTERNAL_MEMORY_ANDROID_HARDWARE_BUFFER_EXTENSION_NAME);
        const bool extProps2 = hasExtension(extensions, VK_KHR_GET_PHYSICAL_DEVICE_PROPERTIES_2_EXTENSION_NAME);

        ExternalChecks opaqueBuffer, dmaBuf, ahbBuffer, opaqueSemaphore, syncFdSemaphore;
        ExternalChecks opaqueFence, syncFdFence;
        if (api11) {
            opaqueBuffer = queryBufferHandle(physicalDevice, VK_EXTERNAL_MEMORY_HANDLE_TYPE_OPAQUE_FD_BIT);
            opaqueSemaphore = querySemaphoreHandle(physicalDevice, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_OPAQUE_FD_BIT);
            syncFdSemaphore = querySemaphoreHandle(physicalDevice, VK_EXTERNAL_SEMAPHORE_HANDLE_TYPE_SYNC_FD_BIT);
            opaqueFence = queryFenceHandle(physicalDevice, VK_EXTERNAL_FENCE_HANDLE_TYPE_OPAQUE_FD_BIT);
            syncFdFence = queryFenceHandle(physicalDevice, VK_EXTERNAL_FENCE_HANDLE_TYPE_SYNC_FD_BIT);
#if defined(VK_EXT_external_memory_dma_buf)
            dmaBuf = queryBufferHandle(physicalDevice, VK_EXTERNAL_MEMORY_HANDLE_TYPE_DMA_BUF_BIT_EXT);
#endif
#if defined(VK_ANDROID_external_memory_android_hardware_buffer)
            ahbBuffer = queryBufferHandle(physicalDevice, VK_EXTERNAL_MEMORY_HANDLE_TYPE_ANDROID_HARDWARE_BUFFER_BIT_ANDROID);
#endif
        }

        if (!firstDevice) out << ",";
        firstDevice = false;
        out << "{\"name\":\"" << jsonEscape(properties.deviceName) << "\","
            << "\"vendorId\":" << properties.vendorID << ","
            << "\"deviceId\":" << properties.deviceID << ","
            << "\"driverVersion\":" << properties.driverVersion << ","
            << "\"apiVersion\":\"" << versionString(properties.apiVersion) << "\","
            << "\"api11CoreQueries\":" << (api11 ? "true" : "false") << ","
            << "\"extensionEnumerationSucceeded\":" << (extensionResult == VK_SUCCESS ? "true" : "false") << ","
            << "\"extensions\":{"
            << "\"VK_KHR_external_memory_fd\":" << (extMemoryFd ? "true" : "false") << ","
            << "\"VK_KHR_external_semaphore_fd\":" << (extSemaphoreFd ? "true" : "false") << ","
            << "\"VK_KHR_external_fence_fd\":" << (extFenceFd ? "true" : "false") << ","
            << "\"VK_ANDROID_external_memory_android_hardware_buffer\":" << (extAhb ? "true" : "false") << ","
            << "\"VK_KHR_get_physical_device_properties2\":" << (extProps2 ? "true" : "false")
            << "},\"externalHandles\":[";
        appendCheck(out, "OPAQUE_FD buffer", opaqueBuffer);
        out << ",";
        appendCheck(out, "DMA_BUF buffer", dmaBuf);
        out << ",";
        appendCheck(out, "ANDROID_HARDWARE_BUFFER buffer", ahbBuffer);
        out << ",";
        appendCheck(out, "OPAQUE_FD semaphore", opaqueSemaphore);
        out << ",";
        appendCheck(out, "SYNC_FD semaphore", syncFdSemaphore);
        out << ",";
        appendCheck(out, "OPAQUE_FD fence", opaqueFence);
        out << ",";
        appendCheck(out, "SYNC_FD fence", syncFdFence);
        out << "]}";
    }

    out << "]}";
    vkDestroyInstance(instance, nullptr);
    return out.str();
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_performance_NativeVulkanProbe_nativeProbe(JNIEnv* env, jobject) {
    const std::string result = probe();
    return env->NewStringUTF(result.c_str());
}
