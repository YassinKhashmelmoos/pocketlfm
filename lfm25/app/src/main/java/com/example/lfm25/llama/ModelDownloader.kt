package com.example.lfm25.llama

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloader"

// Small model URL (TinyLlama 1.1B Chat - ~600MB)
// You can change this to any GGUF model URL
const val DEFAULT_MODEL_URL = "https://huggingface.co/LiquidAI/LFM2.5-VL-450M-GGUF/resolve/main/LFM2.5-VL-450M-Q4_K_M.gguf"
const val MODEL_FILENAME = "model.gguf"

sealed class DownloadState {
    object NotStarted : DownloadState()
    object Checking : DownloadState()
    data class Downloading(val progress: Int) : DownloadState()
    object Completed : DownloadState()
    data class Error(val message: String) : DownloadState()
}

class ModelDownloader(private val context: Context) {
    
    private val _downloadState = MutableStateFlow<DownloadState>(DownloadState.NotStarted)
    val downloadState: StateFlow<DownloadState> = _downloadState.asStateFlow()
    
    val modelFile: File
        get() = File(context.filesDir, MODEL_FILENAME)
    
    fun isModelExists(): Boolean {
        return modelFile.exists() && modelFile.length() > 100_000_000 // At least 100MB
    }
    
    suspend fun checkAndDownloadModel() {
        if (isModelExists()) {
            Log.i(TAG, "Model already exists: ${modelFile.absolutePath}")
            _downloadState.value = DownloadState.Completed
            return
        }
        
        downloadModel()
    }
    
    suspend fun downloadModel(url: String = DEFAULT_MODEL_URL) = withContext(Dispatchers.IO) {
        try {
            _downloadState.value = DownloadState.Downloading(0)
            
            var connection = openConnection(url)
            
            // Handle redirects
            var redirectCount = 0
            while (connection.responseCode in 301..399 && redirectCount < 5) {
                val newUrl = connection.getHeaderField("Location")
                Log.i(TAG, "Following redirect to: $newUrl")
                connection = openConnection(newUrl)
                redirectCount++
            }
            
            if (connection.responseCode != 200) {
                throw Exception("HTTP ${connection.responseCode}")
            }
            
            val totalSize = connection.contentLength
            Log.i(TAG, "Downloading model: ${totalSize / 1024 / 1024} MB")
            
            connection.inputStream.use { input ->
                FileOutputStream(modelFile).use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    var totalBytes = 0L
                    var lastProgress = 0
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                        
                        // Update progress every 1%
                        if (totalSize > 0) {
                            val progress = (totalBytes * 100 / totalSize).toInt()
                            if (progress != lastProgress) {
                                lastProgress = progress
                                _downloadState.value = DownloadState.Downloading(progress)
                                Log.i(TAG, "Download progress: $progress%")
                            }
                        }
                    }
                }
            }
            
            Log.i(TAG, "Model downloaded successfully: ${modelFile.length()} bytes")
            _downloadState.value = DownloadState.Completed
            
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            modelFile.delete()
            _downloadState.value = DownloadState.Error("Download failed: ${e.message}")
        }
    }
    
    private fun openConnection(urlString: String): HttpURLConnection {
        val url = URL(urlString)
        val connection = url.openConnection() as HttpURLConnection
        connection.connectTimeout = 60000
        connection.readTimeout = 60000
        connection.instanceFollowRedirects = true
        connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Android) LLM-Chat-App")
        connection.connect()
        return connection
    }
    
    fun deleteModel() {
        modelFile.delete()
        _downloadState.value = DownloadState.NotStarted
    }
}
