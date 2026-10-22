// JNI bridge between BitChord's FloatAudioProcessor and libjamesdsp.
//
// Deliberately small: BitChord already has the decoded float stream in hand,
// so this exposes only the engine lifecycle, the handful of effects the v1
// UI controls, and a stereo interleaved float process call. None of Rootless
// JamesDSP's AudioRecord/session capture machinery is needed here.

#include <jni.h>
#include <android/log.h>

#include <cmath>
#include <cstdint>
#include <cstring>
#include <mutex>

extern "C" {
#include "jdsp_header.h"
}

#define LOG_TAG "BitChordJamesDSP"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// The engine's own EEL compiler has process-global tables. Rootless JamesDSP
// initialises them once in its Application; we do the same lazily. It is kept
// alive for the process because a create/destroy cycle can happen on every
// player rebuild during a crossfade and NSEEL_start is not designed to be
// re-entered.
std::once_flag g_eelInit;

struct Engine {
    JamesDSPLib dsp{};

    // Last values handed to the engine. Effects are only touched when their
    // setting actually changes: reverb in particular rebuilds its network,
    // and a slider drag should not do that on every audio block.
    bool hasApplied = false;
    bool bassEnabled = false;
    float bassGainDb = 0.0f;
    bool stereoEnabled = false;
    float stereoLevel = 0.0f;
    bool tubeEnabled = false;
    float tubeLevel = 0.0f;
    int reverbPreset = -1;
    float preGainDb = 0.0f;
    bool limiterEnabled = true;
};

Engine* fromHandle(jlong handle) {
    return reinterpret_cast<Engine*>(static_cast<intptr_t>(handle));
}

void applyConfig(Engine* engine,
                 jboolean bassEnabled,
                 jfloat bassGainDb,
                 jboolean stereoEnabled,
                 jfloat stereoLevel,
                 jboolean tubeEnabled,
                 jfloat tubeLevel,
                 jint reverbPreset,
                 jfloat preGainDb,
                 jboolean limiterEnabled) {
    JamesDSPLib* dsp = &engine->dsp;
    const bool first = !engine->hasApplied;

    const bool bass = bassEnabled == JNI_TRUE && bassGainDb > 0.01f;
    const bool stereo = stereoEnabled == JNI_TRUE && stereoLevel > 0.01f;
    const bool tube = tubeEnabled == JNI_TRUE && tubeLevel > 0.01f;
    const int reverb = reverbPreset >= 0 ? static_cast<int>(reverbPreset) : -1;

    if (first || bass != engine->bassEnabled || std::fabs(bassGainDb - engine->bassGainDb) > 0.001f) {
        if (bass) {
            BassBoostSetParam(dsp, bassGainDb);
            BassBoostEnable(dsp);
        } else {
            BassBoostDisable(dsp);
        }
        engine->bassEnabled = bass;
        engine->bassGainDb = bassGainDb;
    }

    if (first || stereo != engine->stereoEnabled || std::fabs(stereoLevel - engine->stereoLevel) > 0.001f) {
        // The engine's stereo widener starts from a disabled state whenever
        // its mix changes; re-enabling afterwards is what Rootless does too.
        StereoEnhancementDisable(dsp);
        StereoEnhancementSetParam(dsp, (stereoLevel / 100.0f));
        if (stereo) StereoEnhancementEnable(dsp);
        engine->stereoEnabled = stereo;
        engine->stereoLevel = stereoLevel;
    }

    if (first || tube != engine->tubeEnabled || std::fabs(tubeLevel - engine->tubeLevel) > 0.001f) {
        if (tube) {
            VacuumTubeSetGain(dsp, static_cast<double>(tubeLevel / 100.0f));
            VacuumTubeEnable(dsp);
        } else {
            VacuumTubeDisable(dsp);
        }
        engine->tubeEnabled = tube;
        engine->tubeLevel = tubeLevel;
    }

    if (first || reverb != engine->reverbPreset) {
        if (reverb >= 0 && reverb <= 18) {
            Reverb_SetParam(dsp, reverb);
            ReverbEnable(dsp);
        } else {
            ReverbDisable(dsp);
        }
        engine->reverbPreset = reverb;
    }

    if (first || std::fabs(preGainDb - engine->preGainDb) > 0.001f) {
        JamesDSPSetPostGain(dsp, static_cast<double>(preGainDb));
        engine->preGainDb = preGainDb;
    }

    if (first || (limiterEnabled == JNI_TRUE) != engine->limiterEnabled) {
        // The engine has no separate limiter bypass: at threshold 0 dB it is
        // effectively a final safety clamp, at -1 dB it is a real limiter.
        JLimiterSetCoefficients(dsp, limiterEnabled == JNI_TRUE ? -1.0 : 0.0, 100.0);
        engine->limiterEnabled = limiterEnabled == JNI_TRUE;
    }

    engine->hasApplied = true;
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_music_bitchord_playback_audio_JamesDspNative_nativeCreate(
        JNIEnv*, jobject, jint sampleRate, jint maxBlockFrames) {
    if (sampleRate <= 0 || maxBlockFrames <= 0) return 0;

    std::call_once(g_eelInit, [] {
        JamesDSPGlobalMemoryAllocation();
        LOGI("loaded JamesDSP engine");
    });

    auto* engine = new Engine();
    if (!engine) return 0;

    JamesDSPInit(&engine->dsp, maxBlockFrames, static_cast<float>(sampleRate));
    if (!JamesDSPGetMutexStatus(&engine->dsp)) {
        LOGE("JamesDSP mutex initialisation failed");
        JamesDSPFree(&engine->dsp);
        delete engine;
        return 0;
    }
    return reinterpret_cast<jlong>(engine);
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_JamesDspNative_nativeDestroy(
        JNIEnv*, jobject, jlong handle) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    JamesDSPFree(&engine->dsp);
    delete engine;
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_JamesDspNative_nativeApply(
        JNIEnv*, jobject, jlong handle,
        jboolean bassEnabled, jfloat bassGainDb,
        jboolean stereoEnabled, jfloat stereoLevel,
        jboolean tubeEnabled, jfloat tubeLevel,
        jint reverbPreset, jfloat preGainDb, jboolean limiterEnabled) {
    auto* engine = fromHandle(handle);
    if (!engine) return;
    applyConfig(engine, bassEnabled, bassGainDb, stereoEnabled, stereoLevel,
                tubeEnabled, tubeLevel, reverbPreset, preGainDb, limiterEnabled);
}

extern "C" JNIEXPORT void JNICALL
Java_com_music_bitchord_playback_audio_JamesDspNative_nativeProcess(
        JNIEnv* env, jobject, jlong handle, jfloatArray input, jfloatArray output,
        jint frames) {
    auto* engine = fromHandle(handle);
    if (!engine || frames <= 0) return;

    jfloat* src = env->GetFloatArrayElements(input, nullptr);
    jfloat* dst = env->GetFloatArrayElements(output, nullptr);
    if (!src || !dst) {
        if (src) env->ReleaseFloatArrayElements(input, src, JNI_ABORT);
        if (dst) env->ReleaseFloatArrayElements(output, dst, JNI_ABORT);
        return;
    }

    engine->dsp.processFloatMultiplexd(
            &engine->dsp, src, dst, static_cast<size_t>(frames));

    env->ReleaseFloatArrayElements(input, src, JNI_ABORT);
    env->ReleaseFloatArrayElements(output, dst, 0);
}
