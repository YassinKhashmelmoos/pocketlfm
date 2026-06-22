package com.example.lfm25.viewmodel

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.lfm25.llama.LlamaModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

private const val TAG = "ChatViewModel"

/**
 * Data class representing a chat message.
 */
data class ChatMessage(
    val id: Long = generateMessageId(),
    val content: String,
    val isUser: Boolean,
    val isLoading: Boolean = false,
    val error: String? = null
) {
    companion object {
        private var counter = 0L
        fun generateMessageId(): Long {
            return System.currentTimeMillis() * 1000 + counter++
        }
    }
}

/**
 * UI State for the chat screen.
 */
data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isModelLoading: Boolean = false,
    val isGenerating: Boolean = false,
    val modelLoaded: Boolean = false,
    val error: String? = null,
    val systemPrompt: String = "You are a helpful AI assistant. Be concise and friendly."
)

/**
 * ViewModel for the chat screen.
 * Manages model loading and chat interactions.
 */
class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val llamaModel = LlamaModel.getInstance()

    init {
        loadModel()
    }

    /**
     * Load the LLM model from assets.
     */
    private fun loadModel() {
        viewModelScope.launch {
            _uiState.update { it.copy(isModelLoading = true, error = null) }

            val success = llamaModel.loadModel(getApplication())

            _uiState.update {
                it.copy(
                    isModelLoading = false,
                    modelLoaded = success,
                    error = if (!success) "Failed to load model. Make sure model.gguf is in assets." else null
                )
            }

            if (success) {
                Log.i(TAG, "Model loaded successfully, showing chat")
                // Add welcome message
                addMessage(
                    ChatMessage(
                        content = "Hello! I'm ready to help. Type a message to get started.",
                        isUser = false
                    )
                )
            }
        }
    }

    /**
     * Update input text.
     */
    fun onInputTextChanged(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    /**
     * Send a message.
     */
    fun sendMessage() {
        val currentInput = _uiState.value.inputText.trim()
        if (currentInput.isBlank() || _uiState.value.isGenerating) return

        // Add user message
        addMessage(ChatMessage(content = currentInput, isUser = true))
        
        // Clear input
        _uiState.update { it.copy(inputText = "") }

        // Generate response
        generateResponse(currentInput)
    }

    /**
     * Generate AI response.
     */
    private fun generateResponse(userMessage: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isGenerating = true) }

            // Add loading message
            val loadingMessage = ChatMessage(
                content = "",
                isUser = false,
                isLoading = true
            )
            addMessage(loadingMessage)

            // Build prompt with context
            val prompt = buildPrompt(userMessage)
            Log.d(TAG, "Prompt: $prompt")

            // Generate response
            val response = llamaModel.generate(
                prompt = prompt,
                temperature = LlamaModel.DEFAULT_TEMPERATURE,
                topP = LlamaModel.DEFAULT_TOP_P,
                topK = LlamaModel.DEFAULT_TOP_K,
                repeatPenalty = LlamaModel.DEFAULT_REPEAT_PENALTY,
                maxTokens = LlamaModel.DEFAULT_MAX_TOKENS
            )

            // Remove loading message and add actual response
            removeLoadingMessage()
            
            // Clean up response (remove prompt echo if any)
            val cleanedResponse = cleanResponse(response, prompt)
            
            addMessage(
                ChatMessage(
                    content = cleanedResponse.ifBlank { "I apologize, but I couldn't generate a response." },
                    isUser = false
                )
            )

            _uiState.update { it.copy(isGenerating = false) }
        }
    }

    /**
     * Build prompt with system message and context.
     */
    private fun buildPrompt(userMessage: String): String {
        val systemPrompt = _uiState.value.systemPrompt
        
        // ChatML format — the chat template used by Liquid LFM2.5.
        return """<|im_start|>system
$systemPrompt<|im_end|>
<|im_start|>user
$userMessage<|im_end|>
<|im_start|>assistant
""".trimIndent()
    }

    /**
     * Clean up the response to remove prompt echo.
     */
    private fun cleanResponse(response: String, prompt: String): String {
        var cleaned = response.trim()
        
        // Remove prompt if it was echoed back
        if (cleaned.startsWith(prompt)) {
            cleaned = cleaned.substring(prompt.length).trim()
        }
        
        // Remove common stop tokens
        val stopTokens = listOf("<|user|>", "<|system|>", "<|end|>", "</s>", "<|im_end|>")
        for (token in stopTokens) {
            val index = cleaned.indexOf(token)
            if (index != -1) {
                cleaned = cleaned.substring(0, index).trim()
            }
        }
        
        return cleaned
    }

    /**
     * Add a message to the chat.
     */
    private fun addMessage(message: ChatMessage) {
        _uiState.update { currentState ->
            currentState.copy(messages = currentState.messages + message)
        }
    }

    /**
     * Remove loading message.
     */
    private fun removeLoadingMessage() {
        _uiState.update { currentState ->
            currentState.copy(
                messages = currentState.messages.filterNot { it.isLoading }
            )
        }
    }

    /**
     * Clear all messages.
     */
    fun clearChat() {
        _uiState.update { 
            it.copy(
                messages = listOf(
                    ChatMessage(
                        content = "Chat cleared. How can I help you?",
                        isUser = false
                    )
                )
            ) 
        }
    }

    /**
     * Retry loading the model.
     */
    fun retryLoadModel() {
        loadModel()
    }

    override fun onCleared() {
        super.onCleared()
        llamaModel.unload()
    }
}
