#pragma once

#include "third_party/openxr/include/openxr/openxr_loader_negotiation.h"

#include <cstdint>

#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME "XR_KHR_android_create_instance"
#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION 3
#define ENDXR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME "XR_KHR_opengl_es_enable"
#define ENDXR_KHR_OPENGL_ES_ENABLE_SPEC_VERSION 10

// Khronos XR_KHR_android_create_instance ABI structure.
typedef struct XrInstanceCreateInfoAndroidKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    void* XR_MAY_ALIAS applicationVM;
    void* XR_MAY_ALIAS applicationActivity;
} XrInstanceCreateInfoAndroidKHR;

// Khronos XR_KHR_opengl_es_enable Android binding and image ABI structures.
// EGL handles are opaque pointer-sized values in the Android ABI.
typedef struct XrGraphicsBindingOpenGLESAndroidKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    void* display;
    void* config;
    void* context;
} XrGraphicsBindingOpenGLESAndroidKHR;

typedef struct XrSwapchainImageOpenGLESKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    uint32_t image;
} XrSwapchainImageOpenGLESKHR;

typedef struct XrGraphicsRequirementsOpenGLESKHR {
    XrStructureType type;
    void* XR_MAY_ALIAS next;
    XrVersion minApiVersionSupported;
    XrVersion maxApiVersionSupported;
} XrGraphicsRequirementsOpenGLESKHR;

typedef XrResult (XRAPI_PTR *PFN_xrGetOpenGLESGraphicsRequirementsKHR)(
    XrInstance instance,
    XrSystemId systemId,
    XrGraphicsRequirementsOpenGLESKHR* graphicsRequirements
);

#if defined(__cplusplus)
extern "C" {
#endif

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
XRAPI_ATTR XrResult XRAPI_CALL xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
);

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
void endxrSetHeadOrientation(float x, float y, float z, float w);
std::uint32_t endxrGetLastGlesError();

#if defined(__cplusplus)
}
#endif
