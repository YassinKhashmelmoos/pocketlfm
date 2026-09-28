#include <jni.h>
#include <string>
#include <vector>
#include <android/log.h>
#include <mutex>
#include <thread>
#include <algorithm>
#include <fstream>
#include <sys/sysinfo.h>

#include "llama.h"

#define LOG_TAG "ThunderAGI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  LOG_TAG, __VA_ARGS__)

// ── Device capability detection ───────────────────────────────────────────────
static int detect_threads() {
    int cores = (int)std::thread::hardware_concurrency();
    return std::min(std::max(cores, 2), 8);
}

static long get_free_ram_mb() {
    struct sysinfo info;
    if (sysinfo(&info) == 0) return (info.freeram * info.mem_unit) / (1024 * 1024);
    return 2048; // assume 2GB if unknown
}

// ── Configuration ─────────────────────────────────────────────────────────────
// LFM2.5-VL-450M: 24 layers, hybrid short-conv + attention.
// Q4_K_M: ~270MB file, ~420MB peak RAM.
// Context: dynamically chosen based on available RAM.

static const int    BATCH_SIZE      = 512;
static const int    REP_PEN_WINDOW  = 256;

// LFM2.5-VL Zephyr stop tokens
static const std::vector<std::string> STOP_TOKENS = {
    "<|endoftext|>", "<|user|>", "<|system|>", "<|end|>",
    "</s>", "<|im_end|>", "[/INST]", "<|eot_id|>"
};

// ── State ─────────────────────────────────────────────────────────────────────
static std::mutex     g_mutex;
static llama_model*   g_model   = nullptr;
static llama_context* g_ctx     = nullptr;
static llama_sampler* g_sampler = nullptr;
static bool           g_loaded  = false;
static int            g_n_past  = 0;
static int            g_ctx_size = 4096;

// ── Helpers ───────────────────────────────────────────────────────────────────
static void batch_add(llama_batch& b, llama_token tok, int32_t pos, bool logits) {
    b.token[b.n_tokens]      = tok;
    b.pos[b.n_tokens]        = pos;
    b.n_seq_id[b.n_tokens]   = 1;
    b.seq_id[b.n_tokens][0]  = 0;
    b.logits[b.n_tokens]     = logits ? 1 : 0;
    b.n_tokens++;
}

static void init_sampler(float temp, float top_p, int top_k, float rep_pen) {
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    auto sp = llama_sampler_chain_default_params();
    sp.no_perf = true;
    g_sampler = llama_sampler_chain_init(sp);
    llama_sampler_chain_add(g_sampler, llama_sampler_init_penalties(REP_PEN_WINDOW, rep_pen, 0.0f, 0.0f));
    // Min-P: removes tokens below 5% of top token probability — improves coherence
    llama_sampler_chain_add(g_sampler, llama_sampler_init_min_p(0.05f, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_k(top_k));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_top_p(top_p, 1));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_temp(temp));
    llama_sampler_chain_add(g_sampler, llama_sampler_init_dist(LLAMA_DEFAULT_SEED));
}

static std::string trim_str(const std::string& s) {
    size_t a = s.find_first_not_of(" \t\n\r");
    size_t b = s.find_last_not_of(" \t\n\r");
    return (a == std::string::npos) ? "" : s.substr(a, b - a + 1);
}

// ── JNI ───────────────────────────────────────────────────────────────────────
extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeLoadModel(
        JNIEnv* env, jclass, jstring model_path) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_loaded) { LOGI("Already loaded"); return JNI_TRUE; }

    const char* path = env->GetStringUTFChars(model_path, nullptr);
    LOGI("Loading: %s", path);

    llama_backend_init();

    // ── Dynamic context size based on available RAM ────────────────────────
    long free_mb = get_free_ram_mb();
    if      (free_mb > 4000) g_ctx_size = 16384;
    else if (free_mb > 2500) g_ctx_size = 8192;
    else if (free_mb > 1500) g_ctx_size = 4096;
    else                     g_ctx_size = 2048;
    LOGI("Free RAM: %ld MB → ctx_size: %d", free_mb, g_ctx_size);

    // ── Model params ──────────────────────────────────────────────────────
    llama_model_params mp = llama_model_default_params();
    mp.n_gpu_layers = 0;
    mp.use_mmap  = true;   // memory-map: reduces RAM copy by ~80MB
    mp.use_mlock = false;  // don't lock pages — let Android manage pressure

    g_model = llama_model_load_from_file(path, mp);
    env->ReleaseStringUTFChars(model_path, path);

    if (!g_model) {
        LOGE("Failed to load model");
        llama_backend_free();
        return JNI_FALSE;
    }

    // ── Context params ────────────────────────────────────────────────────
    int n_threads = detect_threads();
    LOGI("Threads: %d", n_threads);

    llama_context_params cp = llama_context_default_params();
    cp.n_ctx           = g_ctx_size;
    cp.n_batch         = BATCH_SIZE;
    cp.n_ubatch        = BATCH_SIZE;
    cp.n_threads       = n_threads;
    cp.n_threads_batch = n_threads;
    // Flash attention: AUTO lets llama.cpp enable it on attention layers only.
    // LFM2.5's short-conv recurrent layers are unaffected.
    cp.flash_attn_type = LLAMA_FLASH_ATTN_TYPE_AUTO;
    cp.offload_kqv     = false;
    // F16 KV cache: best accuracy/speed tradeoff for a 450M model
    cp.type_k = GGML_TYPE_F16;
    cp.type_v = GGML_TYPE_F16;

    g_ctx = llama_init_from_model(g_model, cp);
    if (!g_ctx) {
        LOGE("Context creation failed");
        llama_model_free(g_model); g_model = nullptr;
        llama_backend_free();
        return JNI_FALSE;
    }

    init_sampler(0.40f, 0.92f, 50, 1.15f);
    g_loaded = true;
    g_n_past = 0;
    LOGI("Ready. ctx=%d threads=%d sysinfo: %s", g_ctx_size, n_threads, llama_print_system_info());
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeGenerate(
        JNIEnv* env, jclass,
        jstring j_prompt,
        jfloat temperature, jfloat top_p, jint top_k,
        jfloat repeat_penalty, jint max_tokens) {

    std::lock_guard<std::mutex> lock(g_mutex);
    if (!g_loaded || !g_ctx) return env->NewStringUTF("Error: model not loaded.");

    const char* raw = env->GetStringUTFChars(j_prompt, nullptr);
    std::string prompt(raw);
    env->ReleaseStringUTFChars(j_prompt, raw);
    if (prompt.empty()) return env->NewStringUTF("");

    LOGI("Generate: len=%zu n_past=%d max=%d temp=%.2f", prompt.size(), g_n_past, max_tokens, temperature);

    const llama_vocab* vocab = llama_model_get_vocab(g_model);

    // ── Tokenise ──────────────────────────────────────────────────────────
    // 3x buffer: Arabic/CJK text tokenises to more tokens than char count
    int buf = (int)prompt.size() * 3 + 128;
    std::vector<llama_token> tokens(buf);
    int n_tokens = llama_tokenize(vocab,
        prompt.c_str(), (int)prompt.size(),
        tokens.data(), buf,
        true,  // add_special
        true); // parse_special — critical: treats <|user|> as a single token

    if (n_tokens < 0) {
        tokens.resize(-n_tokens + 8);
        n_tokens = llama_tokenize(vocab,
            prompt.c_str(), (int)prompt.size(),
            tokens.data(), (int)tokens.size(), true, true);
    }
    if (n_tokens <= 0) return env->NewStringUTF("...");
    tokens.resize(n_tokens);
    LOGI("Tokens: %d", n_tokens);

    // ── Context guard ─────────────────────────────────────────────────────
    if (g_n_past + n_tokens + max_tokens >= g_ctx_size) {
        LOGW("Context full, resetting");
        llama_kv_cache_clear(g_ctx);
        g_n_past = 0;
    }

    init_sampler(temperature, top_p, top_k, repeat_penalty);

    // ── Prompt ingestion (delta: only feed new tokens) ────────────────────
    int feed_from = (g_n_past > 0 && g_n_past <= n_tokens) ? g_n_past : 0;
    if (feed_from == 0) { llama_kv_cache_clear(g_ctx); g_n_past = 0; }

    llama_batch batch = llama_batch_init(BATCH_SIZE, 0, 1);
    for (int i = feed_from; i < n_tokens; ) {
        batch.n_tokens = 0;
        int end = std::min(i + BATCH_SIZE, n_tokens);
        for (int j = i; j < end; j++)
            batch_add(batch, tokens[j], g_n_past + (j - feed_from), j == n_tokens - 1);
        if (llama_decode(g_ctx, batch) != 0) {
            llama_batch_free(batch);
            return env->NewStringUTF("...");
        }
        i = end;
    }
    g_n_past = n_tokens;

    // ── Generation loop ───────────────────────────────────────────────────
    std::string response;
    response.reserve(512);

    for (int i = 0; i < max_tokens; i++) {
        llama_token tok = llama_sampler_sample(g_sampler, g_ctx, -1);
        llama_sampler_accept(g_sampler, tok);
        if (llama_vocab_is_eog(vocab, tok)) break;

        char piece[512];
        int nc = llama_token_to_piece(vocab, tok, piece, sizeof(piece), 0, true);
        if (nc > 0) {
            response.append(piece, nc);
            bool stopped = false;
            for (auto& stop : STOP_TOKENS) {
                if (response.size() >= stop.size() &&
                    response.compare(response.size()-stop.size(), stop.size(), stop) == 0) {
                    response = response.substr(0, response.size()-stop.size());
                    stopped = true; break;
                }
            }
            if (stopped) break;
        }

        batch.n_tokens = 0;
        batch_add(batch, tok, g_n_past, true);
        if (llama_decode(g_ctx, batch) != 0) break;
        g_n_past++;
    }

    llama_batch_free(batch);

    response = trim_str(response);
    if (response.empty()) response = "...";
    LOGI("Done: %zu chars n_past=%d", response.size(), g_n_past);
    return env->NewStringUTF(response.c_str());
}

JNIEXPORT void JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeResetContext(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_ctx) { llama_kv_cache_clear(g_ctx); g_n_past = 0; }
    LOGI("Context reset");
}

JNIEXPORT void JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeUnloadModel(JNIEnv*, jclass) {
    std::lock_guard<std::mutex> lock(g_mutex);
    if (g_sampler) { llama_sampler_free(g_sampler); g_sampler = nullptr; }
    if (g_ctx)     { llama_free(g_ctx);             g_ctx     = nullptr; }
    if (g_model)   { llama_model_free(g_model);     g_model   = nullptr; }
    llama_backend_free();
    g_loaded = false; g_n_past = 0;
    LOGI("Unloaded");
}

JNIEXPORT jstring JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeGetSystemInfo(JNIEnv* env, jclass) {
    return env->NewStringUTF(llama_print_system_info());
}

JNIEXPORT jint JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeGetContextSize(JNIEnv*, jclass) {
    return g_ctx_size;
}

JNIEXPORT jboolean JNICALL
Java_com_example_lfm25_llama_LlamaModel_nativeIsLoaded(JNIEnv*, jclass) {
    return g_loaded ? JNI_TRUE : JNI_FALSE;
}

} // extern "C"
