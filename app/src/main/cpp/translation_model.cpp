// SPDX-License-Identifier: AGPL-3.0-only
#include <jni.h>
#include <ggml-backend.h>
#include <llama.h>

#include <algorithm>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace {
struct LocalTranslator {
    std::unique_ptr<llama_model, decltype(&llama_model_free)> model{nullptr, llama_model_free};
    std::unique_ptr<llama_context, decltype(&llama_free)> context{nullptr, llama_free};
};

constexpr int context_size = 768;
constexpr int batch_size = 128;
std::once_flag backend;

bool end_of_reply(const std::string & text) {
    return text.find("\nEnglish:") != std::string::npos ||
           text.find("\nTurkish:") != std::string::npos ||
           text.find("\nTranslate this") != std::string::npos;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_draborneagle_draborntranslate_LocalTranslationModel_nativeOpen(
        JNIEnv * env, jobject, jstring path, jint threads) {
    try {
        std::call_once(backend, [] {
            ggml_backend_load_all();
            llama_backend_init();
        });
        auto translator = std::make_unique<LocalTranslator>();
        const char * characters = env->GetStringUTFChars(path, nullptr);
        if (characters == nullptr) return 0;
        const std::string filename(characters);
        env->ReleaseStringUTFChars(path, characters);
        llama_model_params model_params = llama_model_default_params();
        model_params.n_gpu_layers = 0;
        auto * model = llama_model_load_from_file(filename.c_str(), model_params);
        if (model == nullptr) return 0;
        translator->model.reset(model);

        llama_context_params params = llama_context_default_params();
        params.n_ctx = context_size;
        params.n_batch = batch_size;
        params.n_ubatch = 64;
        params.n_threads = threads;
        params.n_threads_batch = threads;
        params.no_perf = true;
        translator->context.reset(llama_init_from_model(model, params));
        if (!translator->context) return 0;
        return reinterpret_cast<jlong>(translator.release());
    } catch (const std::exception &) {
        return 0;
    }
}

extern "C" JNIEXPORT jbyteArray JNICALL
Java_com_draborneagle_draborntranslate_LocalTranslationModel_nativeComplete(
        JNIEnv * env, jobject, jlong handle, jbyteArray prompt, jint max_tokens, jobject canceled) {
    try {
        auto & translator = *reinterpret_cast<LocalTranslator *>(handle);
        auto * ctx = translator.context.get();
        const auto * vocab = llama_model_get_vocab(translator.model.get());

        const jclass flag_class = env->GetObjectClass(canceled);
        const jmethodID get_flag = env->GetMethodID(flag_class, "get", "()Z");
        env->DeleteLocalRef(flag_class);
        if (get_flag == nullptr) return nullptr;
        auto should_stop = [&] { return env->CallBooleanMethod(canceled, get_flag) == JNI_TRUE; };
        if (should_stop()) return nullptr;

        std::string input(env->GetArrayLength(prompt), '\0');
        env->GetByteArrayRegion(prompt, 0, static_cast<jsize>(input.size()),
                                reinterpret_cast<jbyte *>(input.data()));
        int count = -llama_tokenize(vocab, input.data(), input.size(), nullptr, 0, true, false);
        if (count <= 0 || count + max_tokens > context_size) return nullptr;
        std::vector<llama_token> tokens(count);
        if (llama_tokenize(vocab, input.data(), input.size(), tokens.data(), count, true, false) != count)
            return nullptr;

        llama_memory_clear(llama_get_memory(ctx), false);
        for (int offset = 0; offset < count; offset += batch_size) {
            if (should_stop()) return nullptr;
            const int size = std::min(batch_size, count - offset);
            if (llama_decode(ctx, llama_batch_get_one(tokens.data() + offset, size)) != 0)
                return nullptr;
        }
        std::unique_ptr<llama_sampler, decltype(&llama_sampler_free)> sampler{
                llama_sampler_init_greedy(), llama_sampler_free};
        std::string output;
        bool finished = false;
        for (int index = 0; index < max_tokens; ++index) {
            if (should_stop()) return nullptr;
            llama_token token = llama_sampler_sample(sampler.get(), ctx, -1);
            if (llama_vocab_is_eog(vocab, token)) {
                finished = true;
                break;
            }
            char piece[256];
            int length = llama_token_to_piece(vocab, token, piece, sizeof(piece), 0, false);
            if (length < 0) return nullptr;
            output.append(piece, length);
            if (end_of_reply(output)) {
                finished = true;
                break;
            }
            if (llama_decode(ctx, llama_batch_get_one(&token, 1)) != 0) return nullptr;
        }
        if (!finished || output.empty() || should_stop()) return nullptr;
        auto result = env->NewByteArray(static_cast<jsize>(output.size()));
        if (result != nullptr) {
            env->SetByteArrayRegion(result, 0, static_cast<jsize>(output.size()),
                                    reinterpret_cast<const jbyte *>(output.data()));
        }
        return result;
    } catch (const std::exception &) {
        return nullptr;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_com_draborneagle_draborntranslate_LocalTranslationModel_nativeClose(
        JNIEnv *, jobject, jlong handle) {
    delete reinterpret_cast<LocalTranslator *>(handle);
}
