#pragma once

#include "third_party/openxr/include/openxr/openxr_loader_negotiation.h"

/*
 * ABI declaration from XR_KHR_android_create_instance, OpenXR platform
 * header. Kept local so native contract tests can also compile on Linux
 * without Android's JNI/EGL headers.
 */
#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME "XR_KHR_android_create_instance"
#define ENDXR_KHR_ANDROID_CREATE_INSTANCE_SPEC_VERSION 3

typedef struct XrInstanceCreateInfoAndroidKHR {
    XrStructureType type;
    const void* XR_MAY_ALIAS next;
    void* XR_MAY_ALIAS applicationVM;
    void* XR_MAY_ALIAS applicationActivity;
} XrInstanceCreateInfoAndroidKHR;

#if defined(__cplusplus)
extern "C" {
#endif

#if defined(__GNUC__)
__attribute__((visibility("default")))
#endif
XrResult XRAPI_CALL xrNegotiateLoaderRuntimeInterface(
    const XrNegotiateLoaderInfo* loaderInfo,
    XrNegotiateRuntimeRequest* runtimeRequest
);

#if defined(__cplusplus)
}
#endif
