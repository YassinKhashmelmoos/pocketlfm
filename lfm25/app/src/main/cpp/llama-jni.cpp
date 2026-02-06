#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <memory>
#include <mutex>

#include "llama.h"

#define LOG_TAG "LlamaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Global state
static std::mutex g_mutex;
static llama_model* g_model = nullptr;
static bool g_model_loaded = false;

// Helper function to add token to batch
static void batch_add_token(llama_batch& batch, llama_token token, int32_t pos, int32_t seq_id, bool logits) {
    batch.token[batch.n_tokens] = token;
    batch.pos[batch.n_tokens] = pos;
    batch.n_seq_id[batch.n_tokens] = 1;
    batch.seq_id[batch.n_tokens][0] = seq_id;
    batch.logits[batch.n_tokens] = logits ? 1 : 0;
    batch.n_tokens++;
}

// Helper to clear batch
static void batch_clear(llama_batch& batch) {
    batch.n_tokens = 0;
}

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeLoadModel(
        JNIEnv *env,
        jclass clazz,
        jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (g_model_loaded) {
        LOGI("Model already loaded");
        return JNI_TRUE;
    }

    const char *path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading model from: %s", path);

    // Initialize backend
    llama_backend_init();
    
    // Model parameters
    llama_model_params model_params = llama_model_default_params();
    model_params.n_gpu_layers = 0; // CPU only for Android
    
    // Load model
    g_model = llama_model_load_from_file(path, model_params);
    env->ReleaseStringUTFChars(model_path, path);

    if (g_model == nullptr) {
        LOGE("Failed to load model from file");
        return JNI_FALSE;
    }

    LOGI("Model loaded successfully");
    g_model_loaded = true;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeGenerate(
        JNIEnv *env,
        jclass clazz,
        jstring prompt,
        jfloat temperature,
        jfloat top_p,
        jint top_k,
        jfloat repeat_penalty,
        jint max_tokens) {
    std::lock_guard<std::mutex> lock(g_mutex);

    if (!g_model_loaded || g_model == nullptr) {
        LOGE("Model not loaded");
        return env->NewStringUTF("Error: Model not loaded. Call loadModel() first.");
    }

    // Get prompt string
    const char *prompt_str = env->GetStringUTFChars(prompt, nullptr);
    std::string prompt_text(prompt_str);
    env->ReleaseStringUTFChars(prompt, prompt_str);

    LOGI("=================================");
    LOGI("Prompt: %s", prompt_text.c_str());
    LOGI("Parameters: temp=%.2f, top_p=%.2f, top_k=%d, repeat_penalty=%.2f, max_tokens=%d",
         temperature, top_p, top_k, repeat_penalty, max_tokens);

    // Get vocab
    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // Context parameters
    llama_context_params ctx_params = llama_context_default_params();
    ctx_params.n_ctx = 2048;
    ctx_params.n_batch = 512;
    ctx_params.n_ubatch = 512;
    
    // Create context
    llama_context* ctx = llama_init_from_model(g_model, ctx_params);
    if (ctx == nullptr) {
        LOGE("Failed to create context");
        return env->NewStringUTF("Error: Failed to create context");
    }

    // Tokenize prompt
    std::vector<llama_token> tokens(prompt_text.length() + 16);
    int n_tokens = llama_tokenize(
        vocab,
        prompt_text.c_str(),
        prompt_text.length(),
        tokens.data(),
        tokens.size(),
        true,
        false
    );

    if (n_tokens < 0) {
        LOGE("Tokenization failed");
        llama_free(ctx);
        return env->NewStringUTF("Error: Tokenization failed");
    }
    tokens.resize(n_tokens);
    LOGI("Prompt tokenized to %d tokens", n_tokens);

    // Build sampler
    llama_sampler* sampler = nullptr;
    auto sparams = llama_sampler_chain_default_params();
    sparams.no_perf = false;
    sampler = llama_sampler_chain_init(sparams);

    // Add samplers
    llama_sampler_chain_add(sampler, llama_sampler_init_penalties(64, repeat_penalty, 0.0f, 0.0f));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(sampler, llama_sampler_init_temp(temperature));
    llama_sampler_chain_add(sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));

    // Create batch
    llama_batch batch = llama_batch_init(512, 0, 1);

    // Add tokens to batch
    for (int i = 0; i < n_tokens; i++) {
        batch_add_token(batch, tokens[i], i, 0, i == n_tokens - 1);
    }

    // Decode prompt
    if (llama_decode(ctx, batch) != 0) {
        LOGE("Failed to decode prompt");
        llama_batch_free(batch);
        llama_sampler_free(sampler);
        llama_free(ctx);
        return env->NewStringUTF("Error: Failed to decode prompt");
    }

    // Generate response
    std::string response;
    int n_decode = 0;
    llama_token new_token_id;

    for (int i = 0; i < max_tokens; i++) {
        // Sample next token
        new_token_id = llama_sampler_sample(sampler, ctx, -1);

        // Check for end of generation
        if (llama_vocab_is_eog(vocab, new_token_id)) {
            LOGI("End of generation token at step %d", i);
            break;
        }

        // Convert token to piece
        char piece[256];
        int n_chars = llama_token_to_piece(vocab, new_token_id, piece, sizeof(piece), 0, true);
        if (n_chars > 0) {
            response.append(piece, n_chars);
        }

        n_decode++;
        
        if (n_decode <= 50) {
            std::string token_text(piece, n_chars > 0 ? n_chars : 0);
            LOGI("Token %d: id=%d text='%s'", n_decode, new_token_id, token_text.c_str());
        }

        // Prepare next batch
        batch_clear(batch);
        batch_add_token(batch, new_token_id, n_tokens + i, 0, true);

        // Decode next token
        if (llama_decode(ctx, batch) != 0) {
            LOGE("Failed to decode at step %d", i);
            break;
        }
    }

    LOGI("=================================");
    LOGI("Generation complete!");
    LOGI("Total tokens generated: %d", n_decode);
    LOGI("Response length: %zu characters", response.length());
    LOGI("Response: %s", response.c_str());
    LOGI("=================================");

    // Cleanup
    llama_batch_free(batch);
    llama_sampler_free(sampler);
    llama_free(ctx);

    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeUnloadModel(
        JNIEnv *env,
        jclass clazz) {
    std::lock_guard<std::mutex> lock(g_mutex);

    LOGI("Unloading model...");

    if (g_model != nullptr) {
        llama_model_free(g_model);
        g_model = nullptr;
    }

    llama_backend_free();
    g_model_loaded = false;

    LOGI("Model unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeGetSystemInfo(
        JNIEnv *env,
        jclass clazz) {
    std::string info = llama_print_system_info();
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
