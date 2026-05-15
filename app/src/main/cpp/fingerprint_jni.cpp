#include <jni.h>
#include <android/log.h>
#include <string>
#include <sstream>
#include <cstring>
#include "chromaprint.h"

#define LOG_TAG "FingerprintJNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_inkwise_music_audio_FingerprintGenerator_nativeCreate(
    JNIEnv *env, jclass, jint sampleRate, jint numChannels) {
    auto ctx = chromaprint_new(CHROMAPRINT_ALGORITHM_DEFAULT);
    if (!ctx) {
        LOGD("chromaprint_new failed");
        return 0;
    }
    if (!chromaprint_start(ctx, sampleRate, numChannels)) {
        LOGD("chromaprint_start failed (sampleRate=%d, channels=%d)", sampleRate, numChannels);
        chromaprint_free(ctx);
        return 0;
    }
    return reinterpret_cast<jlong>(ctx);
}

JNIEXPORT jboolean JNICALL
Java_com_inkwise_music_audio_FingerprintGenerator_nativeFeed(
    JNIEnv *env, jclass, jlong ctxPtr, jshortArray pcmData, jint size) {
    auto ctx = reinterpret_cast<ChromaprintContext*>(ctxPtr);
    if (!ctx) return JNI_FALSE;
    jshort *data = env->GetShortArrayElements(pcmData, nullptr);
    if (!data) return JNI_FALSE;
    int result = chromaprint_feed(ctx, data, size);
    env->ReleaseShortArrayElements(pcmData, data, JNI_ABORT);
    return result ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL
Java_com_inkwise_music_audio_FingerprintGenerator_nativeFinish(
    JNIEnv *env, jclass, jlong ctxPtr) {
    auto ctx = reinterpret_cast<ChromaprintContext*>(ctxPtr);
    if (!ctx) return nullptr;
    chromaprint_finish(ctx);
    char *fp = nullptr;
    if (chromaprint_get_fingerprint(ctx, &fp) && fp) {
        jstring result = env->NewStringUTF(fp);
        chromaprint_dealloc(fp);
        return result;
    }
    return nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_inkwise_music_audio_FingerprintGenerator_nativeBase64ToRaw(
    JNIEnv *env, jclass, jstring base64Fp) {
    if (!base64Fp) return nullptr;
    const char *encoded = env->GetStringUTFChars(base64Fp, nullptr);
    if (!encoded) return nullptr;
    int encodedSize = strlen(encoded);

    uint32_t *fp = nullptr;
    int size = 0;
    int algorithm = 0;
    if (chromaprint_decode_fingerprint(encoded, encodedSize, &fp, &size, &algorithm, 1)) {
        std::ostringstream oss;
        for (int i = 0; i < size; i++) {
            if (i > 0) oss << ",";
            oss << fp[i];
        }
        chromaprint_dealloc(fp);
        env->ReleaseStringUTFChars(base64Fp, encoded);
        return env->NewStringUTF(oss.str().c_str());
    }
    env->ReleaseStringUTFChars(base64Fp, encoded);
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_inkwise_music_audio_FingerprintGenerator_nativeFree(
    JNIEnv *, jclass, jlong ctxPtr) {
    auto ctx = reinterpret_cast<ChromaprintContext*>(ctxPtr);
    if (ctx) {
        chromaprint_free(ctx);
    }
}

} // extern "C"
