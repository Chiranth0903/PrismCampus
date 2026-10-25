#include <jni.h>
#include "llama.h"
#include <algorithm>
#include <fstream>
#include <memory>
#include <stdexcept>
#include <string>
#include <vector>
#include <chrono>

struct GenerationDeadline {
    std::chrono::steady_clock::time_point end = std::chrono::steady_clock::now() + std::chrono::seconds(20);
    bool expired() const { return std::chrono::steady_clock::now() >= end; }
};

static bool over_budget(jlong budget_mib) {
    if (budget_mib <= 0) return false; // Explicit profiling build only.
    std::ifstream status("/proc/self/status");
    std::string line;
    while (std::getline(status, line)) {
        if (line.rfind("VmRSS:", 0) == 0) {
            return std::stol(line.substr(6)) >= budget_mib * 1024L;
        }
    }
    return false;
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_prismcampus_app_ai_QwenNative_generate(JNIEnv *env, jobject, jstring path, jbyteArray input, jlong memory_budget_mib) {
    const char *raw_path = env->GetStringUTFChars(path, nullptr);
    if (!raw_path) return nullptr;
    const std::string model_path(raw_path);
    env->ReleaseStringUTFChars(path, raw_path);
    std::string prompt(env->GetArrayLength(input), '\0');
    env->GetByteArrayRegion(input, 0, prompt.size(), reinterpret_cast<jbyte *>(prompt.data()));
    try {
        GenerationDeadline deadline;
        const std::string budget_message = "App memory reached the memory budget (" + std::to_string(memory_budget_mib) + " MiB). Close unused screens and retry.";
        if (over_budget(memory_budget_mib)) throw std::runtime_error(budget_message);
        llama_backend_init();
        auto mp = llama_model_default_params();
        mp.n_gpu_layers = 0;
        mp.use_mmap = true;
        mp.progress_callback = [](float, void *data) { return !static_cast<GenerationDeadline *>(data)->expired(); };
        mp.progress_callback_user_data = &deadline;
        std::unique_ptr<llama_model, decltype(&llama_model_free)> model(
            llama_model_load_from_file(model_path.c_str(), mp), llama_model_free);
        if (!model) throw std::runtime_error(deadline.expired() ? "Offline AI took too long to load. Please retry." : "Unable to load the bundled Qwen GGUF model.");
        const auto *vocab = llama_model_get_vocab(model.get());
        const int count = -llama_tokenize(vocab, prompt.data(), prompt.size(), nullptr, 0, true, true);
        if (count <= 0) throw std::runtime_error("Unable to tokenize the prompt.");
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, prompt.data(), prompt.size(), tokens.data(), count, true, true) < 0)
            throw std::runtime_error("Unable to tokenize the prompt.");
        // Bound KV cache and retain the latest question if campus context is lengthy.
        if (tokens.size() > 448) tokens.erase(tokens.begin(), tokens.end() - 448);
        auto cp = llama_context_default_params();
        cp.n_ctx = 512;
        cp.n_batch = 448;
        cp.n_ubatch = 32;
        cp.n_threads = 4;
        cp.n_threads_batch = 4;
        cp.abort_callback = [](void *data) { return static_cast<GenerationDeadline *>(data)->expired(); };
        cp.abort_callback_data = &deadline;
        std::unique_ptr<llama_context, decltype(&llama_free)> ctx(
            llama_init_from_model(model.get(), cp), llama_free);
        if (!ctx) throw std::runtime_error("Insufficient memory for offline Qwen.");
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler(
            llama_sampler_init_greedy(), llama_sampler_free);
        auto batch = llama_batch_get_one(tokens.data(), tokens.size());
        std::string answer;
        llama_token token;
        for (int i = 0; i < 64; ++i) {
            if (deadline.expired()) {
                if (!answer.empty()) break;
                throw std::runtime_error("Offline AI took too long. Please ask a shorter question.");
            }
            if (over_budget(memory_budget_mib)) throw std::runtime_error(budget_message);
            if (llama_decode(ctx.get(), batch) != 0) {
                if (deadline.expired() && !answer.empty()) break;
                throw std::runtime_error(deadline.expired() ? "Offline AI took too long. Please ask a shorter question." : "Offline decoding failed.");
            }
            token = llama_sampler_sample(sampler.get(), ctx.get(), -1);
            if (llama_vocab_is_eog(vocab, token)) break;
            std::vector<char> piece(256);
            int length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            if (length < 0) {
                piece.resize(-length);
                length = llama_token_to_piece(vocab, token, piece.data(), piece.size(), 0, false);
            }
            if (length > 0) answer.append(piece.data(), length);
            batch = llama_batch_get_one(&token, 1);
        }
        // Model and KV cache are released after each response, including on failure.
        auto output = env->NewByteArray(answer.size());
        if (output) env->SetByteArrayRegion(output, 0, answer.size(), reinterpret_cast<const jbyte *>(answer.data()));
        return output;
    } catch (const std::exception &e) {
        env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), e.what());
        return nullptr;
    }
}
