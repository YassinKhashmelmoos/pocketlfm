package com.example.lfm25.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lfm25.data.ChatDatabase
import com.example.lfm25.data.MessageEntity
import com.example.lfm25.data.SessionEntity
import com.example.lfm25.intelligence.KnowledgeGraph
import com.example.lfm25.intelligence.NightlyTrainer
import com.example.lfm25.intelligence.TinyRL
import com.example.lfm25.llama.LlamaModel
import com.example.lfm25.notification.UpdateNotificationListener
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
// Exact format LiquidAI used during training.
// parse_special=true in JNI tokenizer ensures these are single tokens.

private const val BASE_SYSTEM_PROMPT = """You are Thunder AGI (الرَّعد للذكاء العام المصطنع), a powerful, knowledgeable, and helpful AI assistant running fully on your device. You are direct and clear. You answer in the same language the user uses — Arabic or English. For short questions give short answers. For complex questions reason step by step."""

private suspend fun buildZephyrPrompt(
    history: List<ChatMessage>,
    userText: String,
    imagePath: String?,
    knowledgeGraph: KnowledgeGraph,
    softPromptFile: File?
): String {
    val sb = StringBuilder()

    // Inject learned knowledge context into system prompt
    val kgContext = knowledgeGraph.buildContextString()
    val systemFull = if (kgContext.isNotBlank()) "$BASE_SYSTEM_PROMPT\n\n$kgContext" else BASE_SYSTEM_PROMPT

    sb.append("<|system|>\n$systemFull<|endoftext|>\n")

    // Inject soft prompt (learned examples from nightly training)
    softPromptFile?.takeIf { it.exists() }?.let {
        val softPrompt = it.readText().trim()
        if (softPrompt.isNotBlank()) sb.append(softPrompt).append("\n")
    }

    // History (last 10 turns to stay in context)
    history.takeLast(10).forEach { msg ->
        if (msg.isUser) sb.append("<|user|>\n${msg.content}<|endoftext|>\n")
        else            sb.append("<|assistant|>\n${msg.content}<|endoftext|>\n")
    }

    // Current turn
    val imgNote = if (imagePath != null) "\n[Image: $imagePath]" else ""
    sb.append("<|user|>\n$userText$imgNote<|endoftext|>\n")
    sb.append("<|assistant|>\n")

    return sb.toString()
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
    val showSettings: Boolean = false,
    val snackbar: String? = null,
    val notifPermissionNeeded: Boolean = false
)

// ── ViewModel ─────────────────────────────────────────────────────────────────
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _ui = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _ui.asStateFlow()

    private val model         = LlamaModel.getInstance()
    private val db            = ChatDatabase.getInstance(application)
    private val knowledgeGraph = KnowledgeGraph(application)
    private val tinyRL        = TinyRL(application)
    private val nightlyTrainer = NightlyTrainer(application)
    private val softPromptFile = File(application.filesDir, "soft_prompt_cache.txt")

    private var sessionJob: Job? = null
    private var speechRecognizer: SpeechRecognizer? = null

    init {
        observeSessions()
        ensureModelLoaded()
        nightlyTrainer.schedule()
        checkNotifPermission()
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

    fun toggleDrawer()   = _ui.update { it.copy(showSessionDrawer = !it.showSessionDrawer) }
    fun toggleSettings() = _ui.update { it.copy(showSettings = !it.showSettings) }

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
                content  = text.ifBlank { "[Image]" },
                isUser   = true,
                mediaPath = imgPath,
                mediaType = if (imgPath != null) "image" else null
            )
            persistAndShow(userMsg, sid)

            // Auto-title from first user message
            if (_ui.value.messages.count { it.isUser } <= 1 && text.isNotBlank()) {
                db.sessionDao().insert(SessionEntity(id = sid, title = text.take(45).trim(),
                    updatedAt = System.currentTimeMillis()))
            }

            generateResponse(text, imgPath, sid)
        }
    }

    private fun generateResponse(userText: String, imagePath: String?, sessionId: String) {
        viewModelScope.launch {
            _ui.update { it.copy(isGenerating = true) }
            _ui.update { it.copy(messages = it.messages +
                    ChatMessage(content = "", isUser = false, isLoading = true)) }

            val history = _ui.value.messages.filterNot { it.isLoading }
            val prompt  = buildZephyrPrompt(history, userText, imagePath, knowledgeGraph, softPromptFile)

            // TinyRL: get learned sampling params for this topic
            val topic   = tinyRL.classifyTopic(userText)
            val rlParams = tinyRL.getParams(topic)

            Log.d(TAG, "topic=$topic temp=${rlParams.temperature}")

            val response = model.generate(
                prompt        = prompt,
                temperature   = rlParams.temperature,
                topP          = rlParams.topP,
                topK          = 50,
                repeatPenalty = rlParams.repPenalty,
                maxTokens     = 512
            )

            _ui.update { it.copy(messages = it.messages.filterNot { m -> m.isLoading }) }

            val aiMsg = ChatMessage(content = response.ifBlank { "..." }, isUser = false)
            persistAndShow(aiMsg, sessionId)
            _ui.update { it.copy(isGenerating = false) }

            // Learn from conversation asynchronously
            launch(Dispatchers.IO) {
                knowledgeGraph.learnFromConversation(userText, response)
            }
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

    // ── Feedback (TinyRL) ──────────────────────────────────────────────────────

    fun submitFeedback(msgId: String, good: Boolean) {
        val msg  = _ui.value.messages.find { it.id == msgId } ?: return
        val prev = _ui.value.messages.takeWhile { it.id != msgId }.lastOrNull { it.isUser }

        // Apply RL signal
        val topic = tinyRL.classifyTopic(prev?.content ?: "")
        tinyRL.applyFeedback(topic, good)

        // Persist for nightly training
        viewModelScope.launch(Dispatchers.IO) {
            val line = """{"prompt":${prev?.content.orEmpty().jsonStr()},"response":${msg.content.jsonStr()},"good":$good}""" + "\n"
            File(getApplication<Application>().filesDir, "feedback_log.jsonl").appendText(line)
        }
        _ui.update { it.copy(snackbar = if (good) "👍 Saved — model will improve!" else "👎 Noted") }
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

    // ── Notification / WhatsApp sync ───────────────────────────────────────────

    private fun checkNotifPermission() {
        val granted = UpdateNotificationListener.isPermissionGranted(getApplication())
        _ui.update { it.copy(notifPermissionNeeded = !granted) }
    }

    fun openNotifPermissionSettings(context: Context) {
        UpdateNotificationListener.openPermissionSettings(context)
    }

    fun dismissNotifPrompt() = _ui.update { it.copy(notifPermissionNeeded = false) }

    // ── Voice ──────────────────────────────────────────────────────────────────

    fun startVoice(context: Context) {
        if (!SpeechRecognizer.isRecognitionAvailable(context)) {
            _ui.update { it.copy(snackbar = "Speech recognition not available") }
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
            })
        }
    }

    fun stopVoice() {
        speechRecognizer?.stopListening()
        _ui.update { it.copy(isRecording = false) }
    }

    fun clearSnackbar() = _ui.update { it.copy(snackbar = null) }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private suspend fun saveUri(uri: Uri): String? = withContext(Dispatchers.IO) {
        try {
            val ext = getApplication<Application>().contentResolver.getType(uri)
                ?.substringAfterLast("/") ?: "jpg"
            val f = File(getApplication<Application>().filesDir, "img_${System.currentTimeMillis()}.$ext")
            getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { i -> FileOutputStream(f).use { o -> i.copyTo(o) } }
            f.absolutePath
        } catch (e: Exception) { null }
    }

    private fun String.jsonStr() =
        "\"${replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")}\""

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        knowledgeGraph.close()
    }
}
