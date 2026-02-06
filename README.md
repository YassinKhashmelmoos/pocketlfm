# LFM25 - Offline Android LLM Chat

A native Android chat application that runs Large Language Models (LLMs) entirely offline using [llama.cpp](https://github.com/ggerganov/llama.cpp) and Kotlin with Jetpack Compose.

## Project Overview

LFM25 brings the power of local AI to your Android device. By leveraging llama.cpp's efficient C++ inference engine and the modern Android tech stack, this app enables private, offline conversations with AI models without requiring an internet connection or sending data to external servers.

## Features

- 🔒 **100% Offline** - No internet connection required for inference
- 🚀 **Native Performance** - C++ NDK backend for optimized model execution
- 🎨 **Modern UI** - Built with Jetpack Compose for smooth, responsive interfaces
- 💬 **Chat History** - Persistent conversation storage
- ⚙️ **Configurable** - Adjustable parameters (temperature, context length, etc.)
- 📱 **Multiple Format Support** - GGUF model compatibility
- 🔋 **Battery Efficient** - Optimized for mobile devices

## Tech Stack

| Component | Technology |
|-----------|------------|
| Language | Kotlin |
| UI Framework | Jetpack Compose |
| Inference Engine | llama.cpp (C++) |
| Build System | Gradle with CMake |
| NDK | Android Native Development Kit |
| Architecture | MVVM |
| Dependency Injection | Hilt |
| Async | Kotlin Coroutines & Flow |

## Folder Structure

```
lfm25/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── assets/           # GGUF model files
│   │   │   ├── cpp/              # Native C++ code (llama.cpp)
│   │   │   ├── java/com/example/lfm25/
│   │   │   │   ├── data/         # Repositories, data sources
│   │   │   │   ├── di/           # Dependency injection modules
│   │   │   │   ├── domain/       # Use cases, models
│   │   │   │   ├── ui/           # Composables, ViewModels
│   │   │   │   └── utils/        # Extensions, helpers
│   │   │   └── res/              # Android resources
│   │   └── test/                 # Unit tests
│   └── build.gradle.kts
├── gradle/
├── .gitignore
├── build.gradle.kts
├── CMakeLists.txt
└── README.md
```

## Model Setup

1. **Download a GGUF model** from [Hugging Face](https://huggingface.co/models?search=gguf) or convert your own
   - Recommended: Llama-2-7B, Mistral-7B, or Phi-2/3 quantized models
   - Use Q4_K_M or Q5_K_M quantization for best balance of size/quality

2. **Place the model file** in the assets directory:
   ```
   app/src/main/assets/models/your-model.gguf
   ```

3. **Update the model path** in your configuration (see `ModelConfig.kt`)

4. **Build the native libraries** (see Build & Run steps)

## Build & Run Steps

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or newer
- Android SDK 24+ (Android 7.0+)
- NDK 25.0+ installed via SDK Manager
- CMake 3.22+
- 8GB+ RAM recommended for building

### Debug Build

```bash
# Clone with submodules
git clone --recursive https://github.com/yourusername/lfm25.git
cd lfm25

# Build and install debug APK
./gradlew installDebug
```

### From Android Studio

1. Open the project in Android Studio
2. Sync project with Gradle files
3. Select a device/emulator (API 24+, ARM64 preferred)
4. Click **Run** (▶️) or press `Shift + F10`

## Generate Release APK

### 1. Create Keystore (first time only)

```bash
keytool -genkey -v \
  -keystore release.keystore \
  -alias lfm25 \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000
```

### 2. Configure Signing

Create `local.properties` (if not exists) and add:

```properties
store.file=release.keystore
store.password=YOUR_STORE_PASSWORD
key.alias=lfm25
key.password=YOUR_KEY_PASSWORD
```

### 3. Build Release

```bash
# Build signed APK
./gradlew assembleRelease

# Or AAB for Play Store
./gradlew bundleRelease
```

Output locations:
- APK: `app/build/outputs/apk/release/`
- AAB: `app/build/outputs/bundle/release/`

## Security Notes

⚠️ **Important security considerations:**

1. **Keystore Protection**
   - Never commit `.jks` or keystore files to version control
   - Store keystore passwords in environment variables, not in `local.properties` (which is in `.gitignore`)
   - Use Google Play App Signing for Play Store distribution

2. **Model File Security**
   - GGUF models in `assets/` are bundled within the APK and accessible to users who extract it
   - Consider using Android App Bundle (AAB) and Play Asset Delivery for larger models

3. **Data Privacy**
   - All inference happens locally on device
   - No data is transmitted to external servers
   - Chat history is stored in app's private storage

4. **ProGuard/R8**
   - Release builds are minified and obfuscated by default
   - Review `proguard-rules.pro` if adding reflection code

## Future Improvements

- [ ] **Multiple Model Support** - Switch between different models
- [ ] **Fine-tuning on Device** - LoRA adapter support
- [ ] **Voice Input/Output** - STT/TTS integration
- [ ] **RAG Support** - Document indexing and retrieval
- [ ] **GPU Acceleration** - Vulkan/OpenCL backend for llama.cpp
- [ ] **Cloud Sync** - Optional encrypted backup of chat history
- [ ] **Widgets** - Home screen quick access
- [ ] **Wear OS Support** - Smartwatch companion app

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## Acknowledgments

- [llama.cpp](https://github.com/ggerganov/llama.cpp) by Georgi Gerganov
- [TheBloke](https://huggingface.co/TheBloke) for quantized GGUF models
- Android Jetpack Compose team

---

**Note**: Large models (>4GB) may require 64-bit devices with sufficient RAM. For best performance, use devices with at least 8GB RAM.
