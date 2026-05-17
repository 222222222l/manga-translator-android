#include <jni.h>
#include <algorithm>
#include <cctype>
#include <string>
#include <vector>
#include <android/log.h>

#include "common.h"
#include "llama.h"
#include "mtmd.h"
#include "mtmd-helper.h"

#define TAG "MiniCPMV-JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

static constexpr int MAX_SAFE_THREADS = 2;
static constexpr int MOBILE_CONTEXT_SIZE = 8192;
static constexpr int MOBILE_BATCH_SIZE = 2048;
static constexpr int MOBILE_MAX_GENERATION_TOKENS = 2048;
static constexpr const char * V46_ASSISTANT_PREFIX = "<|im_start|>assistant\n<think>\n\n</think>\n\n";

static llama_model * g_model = nullptr;
static llama_context * g_lctx = nullptr;
static mtmd_context * g_mtmd_ctx = nullptr;
static llama_sampler * g_smpl = nullptr;
static std::string g_last_error;
static constexpr const char * LOCAL_VLM_CLIENT_CLASS = "com/manga/translate/LocalVlmClient";

static void set_last_error(const std::string & message) {
    g_last_error = message;
    LOGE("%s", g_last_error.c_str());
}

static void reset_model_state() {
    if (g_smpl) {
        llama_sampler_free(g_smpl);
        g_smpl = nullptr;
    }
    if (g_mtmd_ctx) {
        mtmd_free(g_mtmd_ctx);
        g_mtmd_ctx = nullptr;
    }
    if (g_lctx) {
        llama_free(g_lctx);
        g_lctx = nullptr;
    }
    if (g_model) {
        llama_model_free(g_model);
        g_model = nullptr;
    }
    llama_backend_free();
}

static std::string strip_media_marker(const char * raw_prompt) {
    std::string prompt = raw_prompt != nullptr ? raw_prompt : "";
    const std::string marker = "<__media__>";
    if (prompt.rfind(marker, 0) == 0) {
        prompt.erase(0, marker.size());
        while (!prompt.empty() && std::isspace(static_cast<unsigned char>(prompt.front()))) {
            prompt.erase(prompt.begin());
        }
    }
    return prompt;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_manga_translate_LocalVlmClient_nativeInitModel(JNIEnv *env, jobject thiz, jstring model_path, jstring mmproj_path, jint num_threads) {
    g_last_error.clear();
    if (g_model && g_mtmd_ctx && g_lctx && g_smpl) {
        LOGI("Model already initialized");
        return JNI_TRUE;
    }
    if (g_model || g_mtmd_ctx || g_lctx || g_smpl) {
        LOGI("Found stale partial model state, resetting before re-initialization");
        reset_model_state();
    }

    const char * c_model_path = env->GetStringUTFChars(model_path, nullptr);
    const char * c_mmproj_path = env->GetStringUTFChars(mmproj_path, nullptr);

    const int safe_threads = std::max(1, std::min(static_cast<int>(num_threads), MAX_SAFE_THREADS));

    LOGI("Loading text model from %s", c_model_path);
    LOGI("Using safe mobile inference config: threads=%d, gpu_offload=off, flash_attn=disabled", safe_threads);
    
    llama_backend_init();

    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0;

    g_model = llama_model_load_from_file(c_model_path, model_params);
    if (!g_model) {
        set_last_error(std::string("Failed to load text model: ") + c_model_path);
        env->ReleaseStringUTFChars(model_path, c_model_path);
        env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
        reset_model_state();
        return JNI_FALSE;
    }

    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = MOBILE_CONTEXT_SIZE;
    ctx_params.n_batch = MOBILE_BATCH_SIZE;
    ctx_params.n_ubatch = MOBILE_BATCH_SIZE;
    ctx_params.n_threads = safe_threads;
    ctx_params.n_threads_batch = safe_threads;
    ctx_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;

    g_lctx = llama_init_from_model(g_model, ctx_params);
    if (!g_lctx) {
        set_last_error(std::string("Failed to initialize llama context for text model: ") + c_model_path);
        env->ReleaseStringUTFChars(model_path, c_model_path);
        env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
        reset_model_state();
        return JNI_FALSE;
    }

    LOGI("Loading vision model from %s", c_mmproj_path);
    mtmd_context_params mtmd_params = mtmd_context_params_default();
    mtmd_params.use_gpu = false;
    mtmd_params.n_threads = safe_threads;
    mtmd_params.image_max_tokens = -1;
    mtmd_params.print_timings = false;
    mtmd_params.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_DISABLED;
    mtmd_params.warmup = false;

    g_mtmd_ctx = mtmd_init_from_file(c_mmproj_path, g_model, mtmd_params);
    if (!g_mtmd_ctx) {
        set_last_error(
            std::string("Failed to initialize mtmd context from mmproj: ") +
            c_mmproj_path +
            ". Check whether the LLM and mmproj belong to the same MiniCPM-V family and whether the file is complete."
        );
        env->ReleaseStringUTFChars(model_path, c_model_path);
        env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
        reset_model_state();
        return JNI_FALSE;
    }

    // Initialize sampler
    llama_sampler_chain_params sparams = llama_sampler_chain_default_params();
    g_smpl = llama_sampler_chain_init(sparams);
    if (!g_smpl) {
        set_last_error("Failed to initialize sampler chain");
        env->ReleaseStringUTFChars(model_path, c_model_path);
        env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
        reset_model_state();
        return JNI_FALSE;
    }
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_k(40));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_top_p(0.95f, 1));
    llama_sampler_chain_add(g_smpl, llama_sampler_init_temp(0.2f));

    env->ReleaseStringUTFChars(model_path, c_model_path);
    env->ReleaseStringUTFChars(mmproj_path, c_mmproj_path);
    LOGI("Model loaded successfully");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_manga_translate_LocalVlmClient_nativeGetLastErrorMessage(JNIEnv *env, jobject thiz) {
    if (g_last_error.empty()) {
        return nullptr;
    }
    return env->NewStringUTF(g_last_error.c_str());
}

extern "C" JNIEXPORT void JNICALL
Java_com_manga_translate_LocalVlmClient_nativeFreeModel(JNIEnv *env, jobject thiz) {
    reset_model_state();
    LOGI("Model freed successfully");
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_manga_translate_LocalVlmClient_nativeProcessImage(JNIEnv *env, jobject thiz, jbyteArray image_bytes, jstring prompt) {
    if (!g_model || !g_mtmd_ctx || !g_lctx) {
        set_last_error("Model not initialized");
        return env->NewStringUTF("");
    }

    const char * c_prompt = env->GetStringUTFChars(prompt, nullptr);
    jsize img_len = env->GetArrayLength(image_bytes);
    jbyte * img_data = env->GetByteArrayElements(image_bytes, nullptr);
    const std::string user_prompt = strip_media_marker(c_prompt);
    std::string normalized_prompt = user_prompt.empty() ? " " : user_prompt;
    const std::string formatted_user_prompt =
        std::string("<|im_start|>user\n") +
        normalized_prompt +
        "<|im_end|>\n" +
        V46_ASSISTANT_PREFIX;

    LOGI("nativeProcessImage start: image_bytes=%d prompt_chars=%d", static_cast<int>(img_len), static_cast<int>(formatted_user_prompt.size()));
    llama_memory_clear(llama_get_memory(g_lctx), false);
    llama_sampler_reset(g_smpl);
    llama_pos current_position = 0;

    // Stage 1: decode image and prefill vision tokens using the official marker path.
    mtmd_bitmap * bitmap = mtmd_helper_bitmap_init_from_buf(g_mtmd_ctx, reinterpret_cast<const unsigned char *>(img_data), img_len);
    if (!bitmap) {
        set_last_error("Failed to decode image buffer");
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    mtmd_input_text image_marker = { mtmd_default_marker(), true, true };
    const mtmd_bitmap * bitmaps[] = { bitmap };
    mtmd_input_chunks * image_chunks = mtmd_input_chunks_init();
    int32_t image_tok_res = mtmd_tokenize(g_mtmd_ctx, image_chunks, &image_marker, bitmaps, 1);

    if (image_tok_res != 0) {
        set_last_error("Stage image tokenize failed with code " + std::to_string(image_tok_res));
        mtmd_input_chunks_free(image_chunks);
        mtmd_bitmap_free(bitmap);
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    int32_t image_eval_res = mtmd_helper_eval_chunks(
        g_mtmd_ctx,
        g_lctx,
        image_chunks,
        current_position,
        0,
        MOBILE_BATCH_SIZE,
        false,
        &current_position
    );
    mtmd_input_chunks_free(image_chunks);
    mtmd_bitmap_free(bitmap);

    if (image_eval_res != 0) {
        set_last_error("Stage image prefill failed with code " + std::to_string(image_eval_res));
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    LOGI("Stage image prefill done: current_position=%d", static_cast<int>(current_position));

    // Stage 2: eval the user prompt without injecting the legacy <__media__> marker.
    mtmd_input_text text_input = { formatted_user_prompt.c_str(), current_position == 0, true };
    mtmd_input_chunks * text_chunks = mtmd_input_chunks_init();
    int32_t text_tok_res = mtmd_tokenize(g_mtmd_ctx, text_chunks, &text_input, nullptr, 0);
    if (text_tok_res != 0) {
        set_last_error("Stage user prompt tokenize failed with code " + std::to_string(text_tok_res));
        mtmd_input_chunks_free(text_chunks);
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    int32_t text_eval_res = mtmd_helper_eval_chunks(
        g_mtmd_ctx,
        g_lctx,
        text_chunks,
        current_position,
        0,
        MOBILE_BATCH_SIZE,
        true,
        &current_position
    );
    mtmd_input_chunks_free(text_chunks);
    if (text_eval_res != 0) {
        set_last_error("Stage user prompt eval failed with code " + std::to_string(text_eval_res));
        env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
        env->ReleaseStringUTFChars(prompt, c_prompt);
        return env->NewStringUTF("");
    }

    LOGI("Stage user prompt done: current_position=%d", static_cast<int>(current_position));

    // Stage 3: generate text with an explicit position-aware batch, matching the official demo.
    std::string response;
    llama_batch gen_batch = llama_batch_init(1, 0, 1);
    for (int i = 0; i < MOBILE_MAX_GENERATION_TOKENS; i++) {
        llama_token id = llama_sampler_sample(g_smpl, g_lctx, -1);
        llama_sampler_accept(g_smpl, id);

        if (llama_vocab_is_eog(llama_model_get_vocab(g_model), id)) {
            break;
        }

        std::string piece(128, '\0');
        int n = llama_token_to_piece(
            llama_model_get_vocab(g_model),
            id,
            piece.data(),
            static_cast<int32_t>(piece.size()),
            0,
            true
        );
        if (n < 0) {
            piece.resize(static_cast<size_t>(-n));
            n = llama_token_to_piece(
                llama_model_get_vocab(g_model),
                id,
                piece.data(),
                static_cast<int32_t>(piece.size()),
                0,
                true
            );
        }
        if (n > 0) {
            response.append(piece.data(), static_cast<size_t>(n));
        }

        common_batch_clear(gen_batch);
        common_batch_add(gen_batch, id, current_position++, {0}, true);
        if (llama_decode(g_lctx, gen_batch) != 0) {
            set_last_error("Stage generation decode failed");
            break;
        }
    }
    llama_batch_free(gen_batch);

    env->ReleaseByteArrayElements(image_bytes, img_data, JNI_ABORT);
    env->ReleaseStringUTFChars(prompt, c_prompt);
    LOGI("nativeProcessImage finished: response_chars=%d", static_cast<int>(response.size()));
    return env->NewStringUTF(response.c_str());
}

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK || env == nullptr) {
        return JNI_ERR;
    }

    jclass clazz = env->FindClass(LOCAL_VLM_CLIENT_CLASS);
    if (clazz == nullptr) {
        LOGE("Failed to find %s for RegisterNatives", LOCAL_VLM_CLIENT_CLASS);
        return JNI_ERR;
    }

    static const JNINativeMethod methods[] = {
        {
            const_cast<char *>("nativeInitModel"),
            const_cast<char *>("(Ljava/lang/String;Ljava/lang/String;I)Z"),
            reinterpret_cast<void *>(Java_com_manga_translate_LocalVlmClient_nativeInitModel)
        },
        {
            const_cast<char *>("nativeGetLastErrorMessage"),
            const_cast<char *>("()Ljava/lang/String;"),
            reinterpret_cast<void *>(Java_com_manga_translate_LocalVlmClient_nativeGetLastErrorMessage)
        },
        {
            const_cast<char *>("nativeFreeModel"),
            const_cast<char *>("()V"),
            reinterpret_cast<void *>(Java_com_manga_translate_LocalVlmClient_nativeFreeModel)
        },
        {
            const_cast<char *>("nativeProcessImage"),
            const_cast<char *>("([BLjava/lang/String;)Ljava/lang/String;"),
            reinterpret_cast<void *>(Java_com_manga_translate_LocalVlmClient_nativeProcessImage)
        }
    };

    if (env->RegisterNatives(clazz, methods, sizeof(methods) / sizeof(methods[0])) != JNI_OK) {
        LOGE("RegisterNatives failed for %s", LOCAL_VLM_CLIENT_CLASS);
        env->DeleteLocalRef(clazz);
        return JNI_ERR;
    }

    env->DeleteLocalRef(clazz);
    LOGI("RegisterNatives succeeded for %s", LOCAL_VLM_CLIENT_CLASS);
    return JNI_VERSION_1_6;
}
