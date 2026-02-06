# llama.cpp Android Integration Setup Guide

Complete step-by-step instructions to set up an offline GGUF LLM in your Android app.

## File Structure

```
app/
├── src/
│   ├── main/
│   │   ├── cpp/
│   │   │   ├── CMakeLists.txt          # CMake build configuration
│   │   │   ├── llama-jni.cpp           # JNI bridge implementation
│   │   │   ├── llama-android.h         # Header file with structures
│   │   │   ├── llama.cpp               # llama.cpp source (downloaded)
│   │   │   ├── llama.h                 # llama.cpp header (downloaded)
│   │   │   ├── ggml.c                  # GGML source (downloaded)
│   │   │   ├── ggml.h                  # GGML header (downloaded)
│   │   │   └── ... (other llama.cpp files)
│   │   ├── assets/
│   │   │   └── model.gguf              # Your GGUF model file
│   │   ├── java/com/example/lfm25/
│   │   │   ├── MainActivity.kt
│   │   │   ├── llama/
│   │   │   │   └── LlamaModel.kt       # JNI wrapper class
│   │   │   ├── viewmodel/
│   │   │   │   └── ChatViewModel.kt    # MVVM ViewModel
│   │   │   └── ui/components/
│   │   │       └── ChatScreen.kt       # Jetpack Compose UI
│   │   └── res/...
│   └── ...
├── build.gradle.kts                      # App-level build config
└── ...
```

## Step-by-Step Setup

### Step 1: Download llama.cpp Sources

Run the provided PowerShell script:

```powershell
.\download-llama-cpp.ps1
```

Or manually download from: https://github.com/ggerganov/llama.cpp/releases

Required files:
- `llama.cpp`, `llama.h`
- `ggml.c`, `ggml.h`
- `ggml-alloc.c`, `ggml-alloc.h`
- `ggml-backend.c`, `ggml-backend.h`
- `ggml-quants.c`, `ggml-quants.h`
- `ggml-opt.cpp`, `ggml-opt.h`
- `sgemm.cpp`, `sgemm.h`
- `unicode.cpp`, `unicode.h`
- `unicode-data.cpp`, `unicode-data.h`

### Step 2: Download a GGUF Model

Download a compatible GGUF model. Recommended small models for Android:

1. **TinyLlama-1.1B** (~600MB)
   ```bash
   wget https://huggingface.co/TheBloke/TinyLlama-1.1B-Chat-v1.0-GGUF/resolve/main/tinyllama-1.1b-chat-v1.0.Q4_K_M.gguf
   ```

2. **Phi-2** (~1.6GB)
   ```bash
   wget https://huggingface.co/TheBloke/phi-2-GGUF/resolve/main/phi-2.Q4_K_M.gguf
   ```

3. **Llama-3.2-1B** (~700MB)
   ```bash
   wget https://huggingface.co/bartowski/Llama-3.2-1B-Instruct-GGUF/resolve/main/Llama-3.2-1B-Instruct-Q4_K_M.gguf
   ```

Copy the downloaded model to:
```
app/src/main/assets/model.gguf
```

### Step 3: Update Gradle Configuration

The `build.gradle.kts` has been updated with:
- CMake configuration
- NDK settings for arm64-v8a
- Required dependencies

### Step 4: Build the Project

In Android Studio:
1. Sync Project with Gradle Files
2. Build → Make Project (Ctrl+F9)

Or via command line:
```bash
./gradlew assembleDebug
```

### Step 5: Run the App

Connect an Android device (arm64-v8a recommended) and run:
```bash
./gradlew installDebug
```

## Configuration

### Sampling Parameters

Edit `LlamaModel.kt` to change defaults:

```kotlin
const val DEFAULT_TEMPERATURE = 0.7f   // Creativity (0.0 - 2.0)
const val DEFAULT_TOP_P = 0.9f         // Nucleus sampling (0.0 - 1.0)
const val DEFAULT_TOP_K = 40           // Top-k sampling (1 - 100)
const val DEFAULT_REPEAT_PENALTY = 1.1f // Repetition penalty (1.0 - 2.0)
const val DEFAULT_MAX_TOKENS = 512     // Max response length
```

### Prompt Format

The chat uses a simple prompt format. Customize in `ChatViewModel.kt`:

```kotlin
private fun buildPrompt(userMessage: String): String {
    return """<|system|>
You are a helpful AI assistant.
<|user|>
$userMessage
<|assistant|>
""".trimIndent()
}
```

Different models use different formats:
- **Llama-2/3**: `<|system|>\n...\n<|user|>\n...\n<|assistant|>\n`
- **ChatML**: `<|im_start|>system\n...<|im_end|>\n...`
- **Alpaca**: `### Instruction:\n...\n### Response:\n`

## Troubleshooting

### Build Errors

**Error: `CMake Error: Could not find CMake executable`**
- Install CMake 3.22.1+ via SDK Manager → SDK Tools

**Error: `undefined reference to 'llama_init_from_model'`**
- Check that llama.cpp version matches the API used in `llama-jni.cpp`
- Update llama.cpp to latest version

**Error: `model.gguf not found in assets`**
- Ensure model file is placed in `app/src/main/assets/`
- Check file size (Git may have LFS issues with large files)

### Runtime Errors

**Error: `Failed to load native library`**
- Check ABI filter in `build.gradle.kts`: `abiFilters.add("arm64-v8a")`
- Verify device architecture: `adb shell getprop ro.product.cpu.abi`

**Error: `Failed to load model`**
- Verify model file exists in internal storage
- Check logcat for detailed error messages
- Ensure model format is GGUF (not GGML or old format)

**Slow inference**
- Use smaller models (Q4_K_M quantization)
- Reduce context size in `llama-jni.cpp`: `ctx_params.n_ctx = 1024`
- Enable optimizations: already set `-O3 -march=armv8.2-a+fp16+dotprod`

**Out of memory**
- Reduce `ctx_params.n_ctx` (context length)
- Use smaller model (Q4_0 instead of Q8_0)
- Close other apps to free RAM

## Performance Tips

1. **Use quantized models**: Q4_K_M offers good balance of size/quality
2. **Reduce context size**: Lower `n_ctx` for faster inference
3. **ARM64 devices**: App is optimized for arm64-v8a with NEON
4. **Batch size**: Adjust `n_batch` based on device capabilities

## Model Recommendations

| Model | Size | Speed | Quality | Use Case |
|-------|------|-------|---------|----------|
| TinyLlama-1.1B-Q4 | ~600MB | Fast | Good | Simple Q&A |
| Phi-2-Q4 | ~1.6GB | Medium | Better | Reasoning |
| Llama-3.2-1B-Q4 | ~700MB | Fast | Better | General chat |
| Qwen2.5-0.5B-Q4 | ~400MB | Very Fast | Good | Mobile-first |

## Logging

The app logs detailed information:
- Model loading
- Prompt tokenization
- First 50 generated tokens
- Response generation stats

View logs with:
```bash
adb logcat -s LlamaJNI:D LlamaModel:D ChatViewModel:D
```

## License

llama.cpp is licensed under MIT. See: https://github.com/ggerganov/llama.cpp/blob/master/LICENSE
