package com.example.lfm25.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * Wrapper class for llama.cpp native library.
 * Handles model loading and text generation via JNI.
 */
class LlamaModel private constructor() {

    companion object {
        private const val TAG = "LlamaModel"
        private const val MODEL_FILENAME = "model.gguf"
        
        // Sampling defaults
        const val DEFAULT_TEMPERATURE = 0.7f
        const val DEFAULT_TOP_P = 0.9f
        const val DEFAULT_TOP_K = 40
        const val DEFAULT_REPEAT_PENALTY = 1.1f
        const val DEFAULT_MAX_TOKENS = 512

        @Volatile
        private var instance: LlamaModel? = null

        fun getInstance(): LlamaModel {
            return instance ?: synchronized(this) {
                instance ?: LlamaModel().also { instance = it }
            }
        }

        // Native methods
        @JvmStatic
        external fun nativeLoadModel(modelPath: String): Boolean

        @JvmStatic
        external fun nativeGenerate(
            prompt: String,
            temperature: Float,
            topP: Float,
            topK: Int,
            repeatPenalty: Float,
            maxTokens: Int
        ): String

        @JvmStatic
        external fun nativeUnloadModel()

        @JvmStatic
        external fun nativeGetSystemInfo(): String

        init {
            try {
                System.loadLibrary("llama-jni")
                Log.i(TAG, "Native library loaded successfully")
            } catch (e: UnsatisfiedLinkError) {
                Log.e(TAG, "Failed to load native library", e)
                throw RuntimeException("Failed to load llama-jni library", e)
            }
        }
    }

    private var isModelLoaded = false
    private var modelFile: File? = null

    /**
     * Load the GGUF model from app assets.
     * Copies the model from assets to internal storage if needed.
     */
    suspend fun loadModel(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (isModelLoaded) {
            Log.i(TAG, "Model already loaded")
            return@withContext true
        }

        try {
            // Copy model from assets if not already in internal storage
            modelFile = copyModelFromAssets(context)
            
            if (modelFile == null || !modelFile!!.exists()) {
                Log.e(TAG, "Model file not found in assets: $MODEL_FILENAME")
                return@withContext false
            }

            Log.i(TAG, "Loading model from: ${modelFile!!.absolutePath}")
            Log.i(TAG, "Model file size: ${modelFile!!.length() / (1024 * 1024)} MB")

            val success = nativeLoadModel(modelFile!!.absolutePath)
            isModelLoaded = success

            if (success) {
                Log.i(TAG, "Model loaded successfully")
                Log.i(TAG, "System info: ${nativeGetSystemInfo()}")
            } else {
                Log.e(TAG, "Failed to load model")
            }

            success
        } catch (e: Exception) {
            Log.e(TAG, "Error loading model", e)
            false
        }
    }

    /**
     * Generate text from a prompt.
     */
    suspend fun generate(
        prompt: String,
        temperature: Float = DEFAULT_TEMPERATURE,
        topP: Float = DEFAULT_TOP_P,
        topK: Int = DEFAULT_TOP_K,
        repeatPenalty: Float = DEFAULT_REPEAT_PENALTY,
        maxTokens: Int = DEFAULT_MAX_TOKENS
    ): String = withContext(Dispatchers.Default) {
        if (!isModelLoaded) {
            Log.e(TAG, "Model not loaded. Call loadModel() first.")
            return@withContext "Error: Model not loaded. Please load the model first."
        }

        if (prompt.isBlank()) {
            return@withContext ""
        }

        try {
            Log.i(TAG, "Generating response for prompt: ${prompt.take(50)}...")
            
            val response = nativeGenerate(
                prompt,
                temperature,
                topP,
                topK,
                repeatPenalty,
                maxTokens
            )
            
            Log.i(TAG, "Generated response: ${response.take(100)}...")
            response
        } catch (e: Exception) {
            Log.e(TAG, "Error generating text", e)
            "Error: ${e.message}"
        }
    }

    /**
     * Unload the model and free resources.
     */
    fun unload() {
        if (isModelLoaded) {
            nativeUnloadModel()
            isModelLoaded = false
            Log.i(TAG, "Model unloaded")
        }
    }

    /**
     * Check if model is loaded.
     */
    fun isLoaded(): Boolean = isModelLoaded

    /**
     * Copy model from assets to internal storage.
     * This is necessary because the native code needs a file path.
     */
    private fun copyModelFromAssets(context: Context): File? {
        val destFile = File(context.filesDir, MODEL_FILENAME)

        // If already copied, return existing file
        if (destFile.exists()) {
            Log.i(TAG, "Model already exists in internal storage")
            return destFile
        }

        // Check if model exists in assets
        val assetFiles = context.assets.list("") ?: emptyArray()
        if (MODEL_FILENAME !in assetFiles) {
            Log.e(TAG, "Model file not found in assets. Available files: ${assetFiles.joinToString()}")
            return null
        }

        Log.i(TAG, "Copying model from assets to internal storage...")

        try {
            context.assets.open(MODEL_FILENAME).use { input ->
                FileOutputStream(destFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                    output.flush()
                    
                    Log.i(TAG, "Model copied successfully: $totalBytes bytes")
                }
            }
            return destFile
        } catch (e: Exception) {
            Log.e(TAG, "Error copying model from assets", e)
            destFile.delete()
            return null
        }
    }
}
