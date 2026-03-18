#include <jni.h>
#include <android/log.h>
#include "audio_engine.h"

#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, "EchoAudio", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "EchoAudio", __VA_ARGS__)

// 全局音频引擎实例
static std::unique_ptr<echo::AudioEngine> gAudioEngine;

extern "C" {

/**
 * Phase 2: JNI桥接层
 * 暴露C++音频引擎给Kotlin层调用
 * 
 * 方法命名规范: Java_com_echo_app_audio_OboeAudioEngine_nativeXXXX
 */

JNIEXPORT jboolean JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeInit(JNIEnv* env, jobject thiz) {
    LOGD("nativeInit called");
    
    if (gAudioEngine != nullptr) {
        LOGD("AudioEngine already exists, releasing first");
        gAudioEngine->release();
        gAudioEngine.reset();
    }
    
    gAudioEngine = std::make_unique<echo::AudioEngine>();
    bool result = gAudioEngine->initialize();
    
    LOGD("nativeInit result: %s", result ? "success" : "failed");
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeStart(JNIEnv* env, jobject thiz) {
    LOGD("nativeStart called");
    
    if (gAudioEngine == nullptr) {
        LOGE("AudioEngine not initialized");
        return;
    }
    
    bool result = gAudioEngine->start();
    LOGD("nativeStart result: %s", result ? "success" : "failed");
}

JNIEXPORT void JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeStop(JNIEnv* env, jobject thiz) {
    LOGD("nativeStop called");
    
    if (gAudioEngine == nullptr) {
        return;
    }
    
    gAudioEngine->stop();
    LOGD("nativeStop completed");
}

JNIEXPORT void JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeRelease(JNIEnv* env, jobject thiz) {
    LOGD("nativeRelease called");
    
    if (gAudioEngine != nullptr) {
        gAudioEngine->release();
        gAudioEngine.reset();
        LOGD("AudioEngine released");
    }
}

JNIEXPORT jboolean JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeIsRunning(JNIEnv* env, jobject thiz) {
    if (gAudioEngine == nullptr) {
        return JNI_FALSE;
    }
    return gAudioEngine->isRunning() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeSetVolume(JNIEnv* env, jobject thiz, jfloat volume) {
    if (gAudioEngine == nullptr) {
        LOGE("AudioEngine not initialized");
        return;
    }
    
    // volume 范围: 0.0 - 1.0
    gAudioEngine->setVolume(volume);
}

JNIEXPORT jfloat JNICALL
Java_com_echo_app_audio_OboeAudioEngine_nativeGetVolume(JNIEnv* env, jobject thiz) {
    if (gAudioEngine == nullptr) {
        return 0.0f;
    }
    return gAudioEngine->getVolume();
}

} // extern "C"
