// Tsuki on-device LLM: a thin JNI bridge over MNN's LLM engine (Qwen3-VL etc.), running inside Tsuki's own process
// (foreground service), so MagicOS cannot throttle it the way it throttles a backgrounded MNN Chat.
#include <jni.h>
#include <android/log.h>
#include <atomic>
#include <mutex>
#include <sstream>
#include <string>
#include <vector>
#include "llm/llm.hpp"

using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessages;

#include "tsuki_core.hpp"

namespace {
using tsuki::Session;
std::string str(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c); env->ReleaseStringUTFChars(s, c); return r;
}
}

extern "C" {

JNIEXPORT jlong JNICALL Java_com_runasakura_phoneagent_llm_EmbeddedMnn_nativeLoad(JNIEnv* env, jclass, jstring config, jstring extra) {
    auto* s = new Session();
    s->llm = Llm::createLLM(str(env, config));
    if (!s->llm) { delete s; return 0; }
    auto ex = str(env, extra);
    if (!ex.empty()) s->llm->set_config(ex);
    if (!s->llm->load()) { Llm::destroy(s->llm); delete s; return 0; }
    // The first turn after load() comes back empty (seen with Qwen3 on MNN, host harness): burn it on a 1-token warm-up.
    tsuki::generate(s->llm, {{"user", "hi"}}, 1, s->cancel);
    return reinterpret_cast<jlong>(s);
}

// roles[i]/contents[i]; images are passed inline as <img>/abs/path.jpg</img> in the content (MNN Omni syntax).
JNIEXPORT jstring JNICALL Java_com_runasakura_phoneagent_llm_EmbeddedMnn_nativeChat(JNIEnv* env, jclass, jlong h, jobjectArray roles, jobjectArray contents, jint maxTokens) {
    auto* s = reinterpret_cast<Session*>(h);
    if (!s || !s->llm) return env->NewStringUTF("");
    std::lock_guard<std::mutex> g(s->lock);
    s->cancel = false;
    ChatMessages msgs;
    jsize n = env->GetArrayLength(roles);
    for (jsize i = 0; i < n; i++) {
        auto r = (jstring) env->GetObjectArrayElement(roles, i); auto c = (jstring) env->GetObjectArrayElement(contents, i);
        msgs.emplace_back(str(env, r), str(env, c));
        env->DeleteLocalRef(r); env->DeleteLocalRef(c);
    }
    auto out = tsuki::generate(s->llm, msgs, maxTokens, s->cancel);
    return env->NewStringUTF(out.c_str());
}

JNIEXPORT void JNICALL Java_com_runasakura_phoneagent_llm_EmbeddedMnn_nativeCancel(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h); if (s) s->cancel = true;
}

JNIEXPORT void JNICALL Java_com_runasakura_phoneagent_llm_EmbeddedMnn_nativeRelease(JNIEnv*, jclass, jlong h) {
    auto* s = reinterpret_cast<Session*>(h); if (!s) return;
    s->cancel = true;
    { std::lock_guard<std::mutex> g(s->lock); if (s->llm) Llm::destroy(s->llm); s->llm = nullptr; }
    delete s;
}

}
