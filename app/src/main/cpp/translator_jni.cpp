#include <jni.h>
#include <llama.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <mutex>
#include <string>
#include <vector>

#include <android/log.h>

namespace {

constexpr int32_t kContextTokens = 4096;
constexpr int32_t kMaxPromptTokens = 3072;
constexpr int32_t kMaxOutputTokens = 1024;
constexpr uint32_t kPromptBatchGrowth = 256;
constexpr uint32_t kContextGrowth = 256;
#ifndef NDEBUG
constexpr char kLogTag[] = "HyMT2Perf";
#endif

struct ModelHandle {
    llama_model * model = nullptr;
    llama_context * context = nullptr;
    uint32_t context_batch_capacity = 0;
    uint32_t context_tokens_capacity = 0;
    int32_t threads = 4;
    std::atomic<bool> cancel_requested{false};
};

std::once_flag g_backend_init;
thread_local std::string g_last_error;
std::mutex g_inference_mutex;

bool should_abort(void * user_data) {
    auto * handle = static_cast<ModelHandle *>(user_data);
    return handle == nullptr || handle->cancel_requested.load(std::memory_order_relaxed);
}

std::string from_jstring(JNIEnv * env, jstring value) {
    if (value == nullptr) return {};
    const jsize length = env->GetStringLength(value);
    const jchar * chars = env->GetStringChars(value, nullptr);
    if (chars == nullptr) return {};

    std::string utf8;
    utf8.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codepoint = chars[i];
        if (codepoint >= 0xD800 && codepoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = chars[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codepoint = 0x10000 + ((codepoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        }
        if (codepoint <= 0x7F) {
            utf8.push_back(static_cast<char>(codepoint));
        } else if (codepoint <= 0x7FF) {
            utf8.push_back(static_cast<char>(0xC0 | (codepoint >> 6)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else if (codepoint <= 0xFFFF) {
            utf8.push_back(static_cast<char>(0xE0 | (codepoint >> 12)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        } else {
            utf8.push_back(static_cast<char>(0xF0 | (codepoint >> 18)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 12) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | ((codepoint >> 6) & 0x3F)));
            utf8.push_back(static_cast<char>(0x80 | (codepoint & 0x3F)));
        }
    }
    env->ReleaseStringChars(value, chars);
    return utf8;
}

jstring to_jstring(JNIEnv * env, const std::string & utf8) {
    std::vector<jchar> utf16;
    utf16.reserve(utf8.size());
    size_t i = 0;
    while (i < utf8.size()) {
        const auto lead = static_cast<uint8_t>(utf8[i++]);
        uint32_t codepoint = lead;
        size_t extra = 0;
        if ((lead & 0xE0) == 0xC0) {
            codepoint = lead & 0x1F;
            extra = 1;
        } else if ((lead & 0xF0) == 0xE0) {
            codepoint = lead & 0x0F;
            extra = 2;
        } else if ((lead & 0xF8) == 0xF0) {
            codepoint = lead & 0x07;
            extra = 3;
        }
        for (size_t j = 0; j < extra && i < utf8.size(); ++j) {
            codepoint = (codepoint << 6) | (static_cast<uint8_t>(utf8[i++]) & 0x3F);
        }
        if (codepoint <= 0xFFFF) {
            utf16.push_back(static_cast<jchar>(codepoint));
        } else {
            codepoint -= 0x10000;
            utf16.push_back(static_cast<jchar>(0xD800 + (codepoint >> 10)));
            utf16.push_back(static_cast<jchar>(0xDC00 + (codepoint & 0x3FF)));
        }
    }
    return env->NewString(utf16.data(), static_cast<jsize>(utf16.size()));
}

void throw_illegal_state(JNIEnv * env, const std::string & message) {
    jclass exception = env->FindClass("java/lang/IllegalStateException");
    if (exception != nullptr) env->ThrowNew(exception, message.c_str());
}

bool tokenize(const llama_vocab * vocab, const std::string & prompt, std::vector<llama_token> & tokens) {
    const int32_t needed = -llama_tokenize(vocab, prompt.c_str(), static_cast<int32_t>(prompt.size()), nullptr, 0, true, true);
    if (needed <= 0 || needed > kMaxPromptTokens) {
        g_last_error = "输入文本过长，请缩短后重试。";
        return false;
    }
    tokens.resize(static_cast<size_t>(needed));
    const int32_t count = llama_tokenize(
        vocab,
        prompt.c_str(),
        static_cast<int32_t>(prompt.size()),
        tokens.data(),
        needed,
        true,
        true
    );
    if (count < 0) {
        g_last_error = "无法对输入文本进行分词。";
        return false;
    }
    tokens.resize(static_cast<size_t>(count));
    return true;
}

bool ensure_context(ModelHandle * handle, uint32_t prompt_tokens) {
    const uint32_t context_required = std::min(
        prompt_tokens + static_cast<uint32_t>(kMaxOutputTokens),
        static_cast<uint32_t>(kContextTokens)
    );
    if (handle->context != nullptr &&
        prompt_tokens <= handle->context_batch_capacity &&
        context_required <= handle->context_tokens_capacity) {
        return true;
    }

    uint32_t batch_capacity = ((prompt_tokens + kPromptBatchGrowth - 1) / kPromptBatchGrowth) * kPromptBatchGrowth;
    if (handle->context_batch_capacity > 0) {
        batch_capacity = std::max(batch_capacity, std::min(handle->context_batch_capacity * 2, static_cast<uint32_t>(kMaxPromptTokens)));
    }
    batch_capacity = std::min(batch_capacity, static_cast<uint32_t>(kMaxPromptTokens));
    uint32_t context_capacity = ((context_required + kContextGrowth - 1) / kContextGrowth) * kContextGrowth;
    context_capacity = std::max(context_capacity, handle->context_tokens_capacity);

    if (handle->context != nullptr) {
        llama_free(handle->context);
        handle->context = nullptr;
        handle->context_batch_capacity = 0;
        handle->context_tokens_capacity = 0;
    }

    llama_context_params params = llama_context_default_params();
    params.n_ctx = context_capacity;
    params.n_batch = batch_capacity;
    params.n_ubatch = std::min<uint32_t>(512, batch_capacity);
    params.n_threads = handle->threads;
    params.n_threads_batch = handle->threads;
    params.abort_callback = should_abort;
    params.abort_callback_data = handle;
#ifndef NDEBUG
    params.no_perf = false;
#endif
    handle->context = llama_init_from_model(handle->model, params);
    if (handle->context == nullptr) return false;

    handle->context_batch_capacity = batch_capacity;
    handle->context_tokens_capacity = context_capacity;
    return true;
}

} // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeLoadModel(
    JNIEnv * env,
    jobject,
    jstring model_path,
    jint threads
) {
    g_last_error.clear();
    const std::string path = from_jstring(env, model_path);
    if (path.empty()) {
        g_last_error = "模型路径为空。";
        return 0;
    }

    std::call_once(g_backend_init, [] { llama_backend_init(); });
    llama_model_params params = llama_model_default_params();
    params.n_gpu_layers = 0;
    llama_model * model = llama_model_load_from_file(path.c_str(), params);
    if (model == nullptr) {
        g_last_error = "llama.cpp 无法载入模型文件。";
        return 0;
    }

    auto * handle = new ModelHandle();
    handle->model = model;
    handle->threads = std::max(2, std::min(static_cast<int32_t>(threads), 6));
    return reinterpret_cast<jlong>(handle);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeClearCancel(
    JNIEnv *,
    jobject,
    jlong raw_handle
) {
    auto * handle = reinterpret_cast<ModelHandle *>(raw_handle);
    if (handle != nullptr) handle->cancel_requested.store(false, std::memory_order_relaxed);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeCancelModel(
    JNIEnv *,
    jobject,
    jlong raw_handle
) {
    auto * handle = reinterpret_cast<ModelHandle *>(raw_handle);
    if (handle != nullptr) handle->cancel_requested.store(true, std::memory_order_relaxed);
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeTranslate(
    JNIEnv * env,
    jobject,
    jlong raw_handle,
    jstring text_value,
    jstring target_value
) {
    g_last_error.clear();
    auto * handle = reinterpret_cast<ModelHandle *>(raw_handle);
    if (handle == nullptr) {
        throw_illegal_state(env, "本地模型尚未载入。 ");
        return nullptr;
    }

    const std::string source = from_jstring(env, text_value);
    const std::string target = from_jstring(env, target_value);
    if (source.empty() || target.empty()) {
        throw_illegal_state(env, "翻译内容或目标语言为空。 ");
        return nullptr;
    }

    const std::string user_text = "将以下文本翻译为 " + target +
        "，注意只需要输出翻译后的结果，不要额外解释：\n" + source;
    // Hy-MT2-1.8B's chat template uses its own user and assistant marker tokens.
    // llama_tokenize(add_special=true) adds the model's BOS token.
    const std::string prompt = "<｜hy_User｜>" + user_text + "<｜hy_Assistant｜>";

    std::lock_guard<std::mutex> lock(g_inference_mutex);
    if (handle->model == nullptr) {
        throw_illegal_state(env, "本地模型已释放。 ");
        return nullptr;
    }
    const llama_vocab * vocab = llama_model_get_vocab(handle->model);
    std::vector<llama_token> prompt_tokens;
    if (!tokenize(vocab, prompt, prompt_tokens)) {
        throw_illegal_state(env, g_last_error);
        return nullptr;
    }

#ifndef NDEBUG
    const auto context_started = std::chrono::steady_clock::now();
    const bool context_reused = handle->context != nullptr && prompt_tokens.size() <= handle->context_batch_capacity;
#endif
    if (!ensure_context(handle, static_cast<uint32_t>(prompt_tokens.size()))) {
        throw_illegal_state(env, "无法为本次翻译分配推理上下文；请关闭其他应用后重试。 ");
        return nullptr;
    }
#ifndef NDEBUG
    const auto context_setup_us = std::chrono::duration_cast<std::chrono::microseconds>(
        std::chrono::steady_clock::now() - context_started
    ).count();
#endif
    llama_memory_clear(llama_get_memory(handle->context), false);
    llama_perf_context_reset(handle->context);

    llama_sampler_chain_params sampler_params = llama_sampler_chain_default_params();
    llama_sampler * sampler = llama_sampler_chain_init(sampler_params);
    const int32_t vocab_size = llama_vocab_n_tokens(vocab);
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(20));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(0.6f, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(0.7f));
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(vocab_size, 64, 1.05f, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    llama_batch batch = llama_batch_get_one(prompt_tokens.data(), static_cast<int32_t>(prompt_tokens.size()));
    std::string result;
    bool failed = false;
    bool cancelled = false;
    llama_token sampled_token = LLAMA_TOKEN_NULL;
    std::array<char, 256> piece{};
    std::vector<char> expanded_piece;
    for (int32_t generated = 0; generated < kMaxOutputTokens; ++generated) {
        if (handle->cancel_requested.load(std::memory_order_relaxed)) {
            g_last_error = "本次翻译已取消。";
            failed = true;
            cancelled = true;
            break;
        }
        if (llama_decode(handle->context, batch) != 0) {
            cancelled = handle->cancel_requested.load(std::memory_order_relaxed);
            g_last_error = cancelled ? "本次翻译已取消。" : "模型推理失败；请缩短文本或重启应用后重试。";
            failed = true;
            break;
        }

        sampled_token = llama_sampler_sample(sampler, handle->context, -1);
        if (llama_vocab_is_eog(vocab, sampled_token)) break;

        int32_t piece_size = llama_token_to_piece(vocab, sampled_token, piece.data(), static_cast<int32_t>(piece.size()), 0, false);
        if (piece_size < 0) {
            expanded_piece.resize(static_cast<size_t>(-piece_size));
            piece_size = llama_token_to_piece(vocab, sampled_token, expanded_piece.data(), static_cast<int32_t>(expanded_piece.size()), 0, false);
            if (piece_size > 0) result.append(expanded_piece.data(), static_cast<size_t>(piece_size));
        } else if (piece_size > 0) {
            result.append(piece.data(), static_cast<size_t>(piece_size));
        }
        batch = llama_batch_get_one(&sampled_token, 1);
    }

#ifndef NDEBUG
    const llama_perf_context_data perf = llama_perf_context(handle->context);
    const double prompt_tokens_per_second = perf.t_p_eval_ms > 0.0 ? perf.n_p_eval * 1000.0 / perf.t_p_eval_ms : 0.0;
    const double generated_tokens_per_second = perf.t_eval_ms > 0.0 ? perf.n_eval * 1000.0 / perf.t_eval_ms : 0.0;
    __android_log_print(
        ANDROID_LOG_INFO,
        kLogTag,
        "context_setup_ms=%lld reused=%d context_tokens=%u prompt_tokens=%d prompt_ms=%.1f prompt_tps=%.2f generated_tokens=%d generation_ms=%.1f generation_tps=%.2f threads=%d",
        static_cast<long long>(context_setup_us / 1000),
        context_reused ? 1 : 0,
        llama_n_ctx(handle->context),
        perf.n_p_eval,
        perf.t_p_eval_ms,
        prompt_tokens_per_second,
        perf.n_eval,
        perf.t_eval_ms,
        generated_tokens_per_second,
        handle->threads
    );
#endif

    llama_sampler_free(sampler);

    if (failed) {
        throw_illegal_state(env, g_last_error);
        return nullptr;
    }
    if (result.empty()) {
        throw_illegal_state(env, "模型返回空译文，请更换目标语言或缩短文本。 ");
        return nullptr;
    }
    return to_jstring(env, result);
}

extern "C" JNIEXPORT void JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeReleaseModel(
    JNIEnv *,
    jobject,
    jlong raw_handle
) {
    auto * handle = reinterpret_cast<ModelHandle *>(raw_handle);
    if (handle == nullptr) return;
    std::lock_guard<std::mutex> lock(g_inference_mutex);
    if (handle->context != nullptr) llama_free(handle->context);
    if (handle->model != nullptr) llama_model_free(handle->model);
    delete handle;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_localtranslate_hymt2_NativeLlamaEngine_00024NativeBindings_nativeLastError(
    JNIEnv * env,
    jobject
) {
    return to_jstring(env, g_last_error);
}
