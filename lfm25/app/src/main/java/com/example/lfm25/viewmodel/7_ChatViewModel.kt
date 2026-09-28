package com.example.lfm25.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lfm25.data.ChatDatabase
import com.example.lfm25.data.MessageEntity
import com.example.lfm25.data.SessionEntity
import com.example.lfm25.llama.LlamaModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

private const val TAG = "ThunderAGI"

// ── LFM2.5-VL Zephyr chat template ───────────────────────────────────────────
// LiquidAI's LFM2.5 uses the Zephyr format:
//   <|system|>\n{content}<|endoftext|>\n
//   <|user|>\n{content}<|endoftext|>\n
//   <|assistant|>\n
// parse_special=true in the JNI tokenizer handles the special tokens correctly.

private const val SYSTEM_PROMPT = """You are Thunder AGI (الرَّعد), a powerful and intelligent AI assistant running fully on-device. You are direct, knowledgeable, and helpful. You give clear, accurate answers. You support Arabic and English. When asked something short, give a short answer. When asked something complex, reason through it step by step."""

private fun buildZephyrPrompt(history: List<ChatMessage>, userText: String, imagePath: String?): String {
    val sb = StringBuilder()
    // System turn
    sb.append("<|system|>\n$SYSTEM_PROMPT<|endoftext|>\n")
    // History turns (last 8 to stay within context)
    history.takeLast(8).forEach { msg ->
        if (msg.isUser) {
            sb.append("<|user|>\n${msg.content}<|endoftext|>\n")
        } else {
            sb.append("<|assistant|>\n${msg.content}<|endoftext|>\n")
        }
    }
    // Current user turn
    val imageNote = if (imagePath != null) "\n[Attached image: $imagePath]" else ""
    sb.append("<|user|>\n$userText$imageNote<|endoftext|>\n")
    // Assistant turn opener — model continues from here
    sb.append("<|assistant|>\n")
    return sb.toString()
}

// ── Adaptive sampling ─────────────────────────────────────────────────────────
// Short/factual prompts → lower temperature for focused answers
// Creative/open prompts → higher temperature for variety
private fun adaptiveTemperature(userText: String): Float {
    val lower = userText.lowercase()
    val isFactual = lower.startsWith("what ") || lower.startsWith("who ") ||
        lower.startsWith("when ") || lower.startsWith("where ") ||
        lower.startsWith("how many") || lower.startsWith("define ") ||
        lower.startsWith("translate") || lower.contains("كم") || lower.contains("ما هو")
    val isCreative = lower.startsWith("write ") || lower.startsWith("create ") ||
        lower.startsWith("imagine ") || lower.startsWith("story") || lower.startsWith("poem")
    return when {
        isFactual  -> 0.25f
        isCreative -> 0.75f
        else       -> 0.40f
    }
}

// ── Data classes ──────────────────────────────────────────────────────────────
data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val mediaPath: String? = null,
    val mediaType: String? = null
)

data class ChatSession(val id: String, val title: String, val updatedAt: Long)

data class ChatUiState(
    val sessions: List<ChatSession> = emptyList(),
    val currentSessionId: String? = null,
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isModelLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val modelLoaded: Boolean = false,
    val error: String? = null,
    val isRecording: Boolean = false,
    val showSessionDrawer: Boolean = false,
    val snackbar: String? = null
)

// ── ViewModel ─────────────────────────────────────────────────────────────────
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val model = LlamaModel.getInstance()
    private val db    = ChatDatabase.getInstance(application)
    private var sessionJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        observeSessions()
        ensureModelLoaded()
    }

    // ── Model ──────────────────────────────────────────────────────────────────

    private fun ensureModelLoaded() {
        if (model.isLoaded()) {
            _ui.update { it.copy(modelLoaded = true) }
            ensureSession()
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(isModelLoading = true, error = null) }
            val ok = model.loadModel(getApplication())
            _ui.update { it.copy(isModelLoading = false, modelLoaded = ok,
                error = if (!ok) "Failed to load model." else null) }
            if (ok) ensureSession()
        }
    }

    fun retryLoadModel() = ensureModelLoaded()

    // ── Sessions ───────────────────────────────────────────────────────────────

    private fun observeSessions() {
        viewModelScope.launch {
            db.sessionDao().getAllSessions().collect { rows ->
                _ui.update { it.copy(sessions = rows.map { r -> ChatSession(r.id, r.title, r.updatedAt) }) }
            }
        }
    }

    private fun ensureSession() {
        if (_ui.value.currentSessionId != null) return
        val first = _ui.value.sessions.firstOrNull()
        if (first != null) switchSession(first.id) else newSession()
    }

    fun newSession() {
        viewModelScope.launch {
            val id = UUID.randomUUID().toString()
            db.sessionDao().insert(SessionEntity(id = id, title = "New Chat"))
            switchSession(id)
            _ui.update { it.copy(showSessionDrawer = false) }
        }
    }

    fun switchSession(id: String) {
        sessionJob?.cancel()
        model.resetContext()
        _ui.update { it.copy(currentSessionId = id, messages = emptyList(), showSessionDrawer = false) }
        sessionJob = viewModelScope.launch {
            db.messageDao().getMessagesForSession(id).collect { rows ->
                _ui.update { it.copy(messages = rows.map { r ->
                    ChatMessage(id = r.id, content = r.content, isUser = r.isUser,
                        mediaPath = r.mediaPath, mediaType = r.mediaType)
                })}
            }
        }
    }

    fun deleteSession(id: String) {
        viewModelScope.launch {
            db.sessionDao().deleteById(id)
            db.messageDao().deleteForSession(id)
            if (_ui.value.currentSessionId == id) {
                _ui.update { it.copy(currentSessionId = null, messages = emptyList()) }
                ensureSession()
            }
        }
    }

    fun toggleDrawer() = _ui.update { it.copy(showSessionDrawer = !it.showSessionDrawer) }

    // ── Messaging ──────────────────────────────────────────────────────────────

    fun onInputChanged(text: String) = _ui.update { it.copy(inputText = text) }

    fun sendMessage(imageUri: Uri? = null) {
        val text = _ui.value.inputText.trim()
        if ((text.isBlank() && imageUri == null) || _ui.value.isGenerating) return
        val sid = _ui.value.currentSessionId ?: return
        _ui.update { it.copy(inputText = "") }

        viewModelScope.launch {
            val imgPath = imageUri?.let { saveUri(it) }
            val userMsg = ChatMessage(
                content = text.ifBlank { "[Image]" },
                isUser = true,
                mediaPath = imgPath,
                mediaType = if (imgPath != null) "image" else null
            )
            persistAndShow(userMsg, sid)

            // Auto-title from first message
            if (_ui.value.messages.count { it.isUser } <= 1 && text.isNotBlank()) {
                val title = text.take(45).trim()
                db.sessionDao().insert(SessionEntity(id = sid, title = title, updatedAt = System.currentTimeMillis()))
            }

            generateResponse(text, imgPath, sid)
        }
    }

    private fun generateResponse(userText: String, imagePath: String?, sessionId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isGenerating = true) }
            _ui.update { it.copy(messages = it.messages + ChatMessage(content = "", isUser = false, isLoading = true)) }

            // Build correct LFM2.5-VL Zephyr prompt from full history
            val history = _ui.value.messages.filterNot { it.isLoading || it.isUser && it.content == userText }
            val prompt = buildZephyrPrompt(history, userText, imagePath)
            val temp   = adaptiveTemperature(userText)

            Log.d(TAG, "Prompt template built, temp=$temp")

            val response = model.generate(
                prompt        = prompt,
                temperature   = temp,
                topP          = 0.92f,
                topK          = 50,
                repeatPenalty = 1.15f,
                maxTokens     = 512
            )

            _ui.update { it.copy(messages = it.messages.filterNot { m -> m.isLoading }) }

            val aiMsg = ChatMessage(content = response.ifBlank { "..." }, isUser = false)
            persistAndShow(aiMsg, sessionId)
            _ui.update { it.copy(isGenerating = false) }
        }
    }

    private suspend fun persistAndShow(msg: ChatMessage, sessionId: String) {
        db.messageDao().insert(MessageEntity(
            id = msg.id, sessionId = sessionId,
            content = msg.content, isUser = msg.isUser,
            mediaPath = msg.mediaPath, mediaType = msg.mediaType
        ))
        db.sessionDao().touch(sessionId)
    }

    fun clearChat() {
        val id = _ui.value.currentSessionId ?: return
        viewModelScope.launch {
            db.messageDao().deleteForSession(id)
            model.resetContext()
            _ui.update { it.copy(messages = emptyList()) }
        }
    }

    // ── Voice ──────────────────────────────────────────────────────────────────

    fun startVoice(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _ui.update { it.copy(snackbar = "Speech recognition not available on this device") }
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).also { sr ->
            sr.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: android.os.Bundle?) = _ui.update { it.copy(isRecording = true) }
                override fun onResults(r: android.os.Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    _ui.update { it.copy(inputText = it.inputText + t, isRecording = false) }
                }
                override fun onPartialResults(r: android.os.Bundle?) {
                    val t = r?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: ""
                    if (t.isNotBlank()) _ui.update { it.copy(inputText = t) }
                }
                override fun onError(e: Int) = _ui.update { it.copy(isRecording = false) }
                override fun onBeginningOfSpeech() {}
                override fun onEndOfSpeech()      {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onRmsChanged(r: Float) {}
                override fun onEvent(t: Int, p: android.os.Bundle?) {}
            })
            sr.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    fun stopVoice() {
        speechRecognizer?.stopListening()
        _ui.update { it.copy(isRecording = false) }
    }

    // ── Feedback ───────────────────────────────────────────────────────────────

    fun submitFeedback(msgId: String, good: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val msg  = _ui.value.messages.find { it.id == msgId } ?: return@launch
            val prev = _ui.value.messages.takeWhile { it.id != msgId }.lastOrNull { it.isUser }
            val line = """{"prompt":${prev?.content.orEmpty().jsonStr()},"response":${msg.content.jsonStr()},"good":$good}""" + "\n"
            File(getApplication<Application>().filesDir, "feedback_log.jsonl").appendText(line)
            _ui.update { it.copy(snackbar = if (good) "👍 Saved" else "👎 Noted") }
        }
    }

    fun importFineTune(uri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val dest = File(getApplication<Application>().filesDir, "finetune_import.gguf")
                getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                    FileOutputStream(dest).use { o -> i.copyTo(o) }
                }
                _ui.update { it.copy(snackbar = "Fine-tune imported! Restart app to apply.") }
            } catch (e: Exception) {
                _ui.update { it.copy(snackbar = "Import failed: ${e.message}") }
            }
        }
    }

    fun clearSnackbar() = _ui.update { it.copy(snackbar = null) }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun saveUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val ext = getApplication<Application>().contentResolver.getType(uri)?.substringAfterLast("/") ?: "jpg"
            val f = File(getApplication<Application>().filesDir, "img_${System.currentTimeMillis()}.$ext")
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { i ->
                FileOutputStream(f).use { o -> i.copyTo(o) }
            }
            f.absolutePath
        } catch (e: Exception) { null }
    }

    private fun String.jsonStr() = "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        // Do NOT unload model — stays resident across ViewModel recreation
    }
}
