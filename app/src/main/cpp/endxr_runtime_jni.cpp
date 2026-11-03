#include <jni.h>

#include <cstdint>
#include <string>

#include "endxr_runtime_core.hpp"

namespace {

endxr::RuntimeCore g_runtime_core;

jstring makeError(JNIEnv* env, const std::string& error) {
    if (error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(error.c_str());
}

} // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeInitialize(
    JNIEnv* env,
    jobject,
    jint width,
    jint height,
    jint target_fps
) {
    const endxr::RuntimeConfig config{
        static_cast<std::uint32_t>(width),
        static_cast<std::uint32_t>(height),
        static_cast<std::uint32_t>(target_fps)
    };
    std::string error;
    g_runtime_core.initialize(config, &error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeCreateSession(
    JNIEnv* env,
    jobject
) {
    std::string error;
    g_runtime_core.createSession(&error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeBeginSession(
    JNIEnv* env,
    jobject
) {
    std::string error;
    g_runtime_core.beginSession(&error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeWaitFrame(
    JNIEnv* env,
    jobject,
    jlong monotonic_now_ns
) {
    endxr::FrameTiming timing;
    std::string error;
    if (!g_runtime_core.waitFrame(
            static_cast<std::int64_t>(monotonic_now_ns),
            &timing,
            &error)) {
        return nullptr;
    }

    const jlong values[] = {
        static_cast<jlong>(timing.frame_index),
        static_cast<jlong>(timing.predicted_display_time_ns),
        static_cast<jlong>(timing.predicted_display_period_ns)
    };
    jlongArray result = env->NewLongArray(3);
    if (result == nullptr) {
        std::string cleanup_error;
        g_runtime_core.endFrame(timing, false, &cleanup_error);
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 3, values);
    if (env->ExceptionCheck()) {
        std::string cleanup_error;
        g_runtime_core.endFrame(timing, false, &cleanup_error);
        return nullptr;
    }
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeEndFrame(
    JNIEnv* env,
    jobject,
    jlong frame_index,
    jlong predicted_display_time_ns,
    jlong predicted_display_period_ns,
    jboolean rendered_successfully
) {
    const endxr::FrameTiming timing{
        static_cast<std::uint64_t>(frame_index),
        static_cast<std::int64_t>(predicted_display_time_ns),
        static_cast<std::int64_t>(predicted_display_period_ns)
    };
    std::string error;
    g_runtime_core.endFrame(timing, rendered_successfully == JNI_TRUE, &error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeRequestStop(
    JNIEnv* env,
    jobject
) {
    std::string error;
    g_runtime_core.requestStop(&error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeDestroySession(
    JNIEnv* env,
    jobject
) {
    std::string error;
    g_runtime_core.destroySession(&error);
    return makeError(env, error);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeMarkLost(
    JNIEnv* env,
    jobject,
    jstring reason
) {
    std::string message;
    if (reason != nullptr) {
        const char* chars = env->GetStringUTFChars(reason, nullptr);
        if (chars != nullptr) {
            message.assign(chars);
            env->ReleaseStringUTFChars(reason, chars);
        }
    }

    std::string error;
    g_runtime_core.markLost(message, &error);
    return makeError(env, error);
}

extern "C" JNIEXPORT void JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeShutdown(
    JNIEnv*,
    jobject
) {
    g_runtime_core.shutdown();
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeSnapshot(
    JNIEnv* env,
    jobject
) {
    const auto snapshot = g_runtime_core.snapshot();
    const jlong values[] = {
        static_cast<jlong>(snapshot.state),
        static_cast<jlong>(snapshot.config.width),
        static_cast<jlong>(snapshot.config.height),
        static_cast<jlong>(snapshot.config.target_fps),
        static_cast<jlong>(snapshot.submitted_frames)
    };

    jlongArray result = env->NewLongArray(5);
    if (result == nullptr) {
        return nullptr;
    }
    env->SetLongArrayRegion(result, 0, 5, values);
    return result;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_noyclic_questphonexr_runtime_EndXrRuntimeBridge_nativeLastError(
    JNIEnv* env,
    jobject
) {
    return makeError(env, g_runtime_core.snapshot().last_error);
}
