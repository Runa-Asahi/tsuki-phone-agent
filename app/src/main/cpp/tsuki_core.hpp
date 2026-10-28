// Shared core of the on-device bridge (used by the JNI layer and by the host test harness tools/llm_harness.cpp).
#pragma once
#include <atomic>
#include <mutex>
#include <ostream>
#include <string>
#include "llm/llm.hpp"

namespace tsuki {
using MNN::Transformer::Llm;
using MNN::Transformer::ChatMessages;

struct Session {
    Llm* llm = nullptr;
    std::mutex lock;
    std::atomic<bool> cancel{false};
};

// Collects decoded text; once cancel is set, writes fail (EOF) so nothing more is appended.
class CancelBuf : public std::streambuf {
public:
    CancelBuf(std::string& out, std::atomic<bool>& cancel) : out_(out), cancel_(cancel) {}
protected:
    int_type overflow(int_type c) override {
        if (cancel_.load()) return traits_type::eof();
        if (c != traits_type::eof()) out_.push_back(static_cast<char>(c));
        return c;
    }
    std::streamsize xsputn(const char* s, std::streamsize n) override {
        if (cancel_.load()) return 0;
        out_.append(s, static_cast<size_t>(n)); return n;
    }
private:
    std::string& out_;
    std::atomic<bool>& cancel_;
};

// Stateless chat turn: reset, prefill + first token, then one token at a time so a cancel (step deadline / 急停)
// stops compute within one token instead of running to max_new_tokens.
inline std::string generate(Llm* llm, const ChatMessages& msgs, int maxTokens, std::atomic<bool>& cancel) {
    std::string out;
    CancelBuf buf(out, cancel);
    std::ostream os(&buf);
    llm->reset();
    llm->response(msgs, &os, nullptr, 1);
    for (int i = 1; i < maxTokens && !cancel.load() && !llm->stoped(); i++) llm->generate(1);
    return out;
}
}
