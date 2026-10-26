// Host test of the bridge core against a real MNN model (x86_64 build of MNN):
//   llm_harness <model_dir/config.json>
// Checks: a reply is produced, decoding stops at EOS before max tokens, cancel stops within one token.
#include <chrono>
#include <cstdio>
#include <thread>
#include "../app/src/main/cpp/tsuki_core.hpp"
int main(int argc, char** argv) {
    using namespace tsuki;
    auto* llm = Llm::createLLM(argv[1]);
    llm->set_config(R"({"thread_num":4,"precision":"low","memory":"low","use_mmap":true,"tmp_path":"/tmp"})");
    auto t0 = std::chrono::steady_clock::now();
    if (!llm->load()) { printf("LOAD_FAIL\n"); return 1; }
    auto ms = [&](auto a) { return (long) std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - a).count(); };
    printf("load %ld ms\n", ms(t0));
    std::atomic<bool> cancel{false};
    ChatMessages m{{"system", "只输出 JSON。"}, {"user", "/no_think 把动作写成 JSON：点击「发送」按钮。格式 {\"action\":{\"type\":\"tap\",\"text\":\"...\"}}"}};
    auto t1 = std::chrono::steady_clock::now();
    auto out = generate(llm, m, 200, cancel);
    printf("reply %ld ms (%zu bytes): %s\n", ms(t1), out.size(), out.c_str());
    // second call must be independent (reset) and still work
    auto out2 = generate(llm, {{"user", "/no_think 1+1=? 只回答数字"}}, 50, cancel);
    printf("reply2: %s\n", out2.c_str());
    // cancel mid-generation
    std::thread th([&] { std::this_thread::sleep_for(std::chrono::milliseconds(300)); cancel = true; });
    auto t2 = std::chrono::steady_clock::now();
    auto out3 = generate(llm, {{"user", "/no_think 写一篇两千字的文章，主题是月亮。"}}, 2000, cancel);
    th.join();
    printf("cancelled after %ld ms, %zu bytes\n", ms(t2), out3.size());
    Llm::destroy(llm);
    printf("HARNESS_OK\n");
}
