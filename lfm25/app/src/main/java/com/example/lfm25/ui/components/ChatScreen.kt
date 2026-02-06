package com.example.lfm25.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.lfm25.viewmodel.ChatMessage
import com.example.lfm25.viewmodel.ChatUiState
import kotlinx.coroutines.launch

/**
 * Main chat screen composable.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    onClearChat: () -> Unit,
    onRetryLoadModel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Scroll to bottom when new messages arrive
    LaunchedEffect(uiState.messages.size, uiState.messages.lastOrNull()?.content) {
        if (uiState.messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(uiState.messages.size - 1)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Local LLM Chat") },
                actions = {
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = onClearChat) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear chat"
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        },
        bottomBar = {
            if (uiState.modelLoaded && !uiState.isModelLoading) {
                ChatInputBar(
                    inputText = uiState.inputText,
                    isGenerating = uiState.isGenerating,
                    onInputTextChanged = onInputTextChanged,
                    onSendMessage = onSendMessage,
                    modifier = Modifier.imePadding()
                )
            }
        }
    ) { paddingValues ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isModelLoading -> {
                    LoadingModelView()
                }
                uiState.error != null && !uiState.modelLoaded -> {
                    ErrorView(
                        error = uiState.error,
                        onRetry = onRetryLoadModel
                    )
                }
                else -> {
                    ChatMessagesList(
                        messages = uiState.messages,
                        listState = listState,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }
    }
}

/**
 * List of chat messages.
 */
@Composable
private fun ChatMessagesList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    modifier: Modifier = Modifier
) {
    if (messages.isEmpty()) {
        Box(
            modifier = modifier,
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Start a conversation!",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(messages, key = { it.id }) { message ->
                ChatMessageItem(message = message)
            }
        }
    }
}

/**
 * Single chat message item.
 */
@Composable
private fun ChatMessageItem(
    message: ChatMessage,
    modifier: Modifier = Modifier
) {
    val isUser = message.isUser
    val backgroundColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        if (!isUser) {
            // AI Avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.secondary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "AI",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondary
                )
            }
            Spacer(modifier = Modifier.width(8.dp))
        }

        Box(
            modifier = Modifier
                .weight(1f, fill = false)
                .clip(
                    RoundedCornerShape(
                        topStart = 16.dp,
                        topEnd = 16.dp,
                        bottomStart = if (isUser) 16.dp else 4.dp,
                        bottomEnd = if (isUser) 4.dp else 16.dp
                    )
                )
                .background(backgroundColor)
                .padding(12.dp)
        ) {
            if (message.isLoading) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = textColor
                    )
                    Text(
                        text = "Thinking...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = textColor
                    )
                }
            } else {
                Text(
                    text = message.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = textColor
                )
            }
        }

        if (isUser) {
            Spacer(modifier = Modifier.width(8.dp))
            // User Avatar
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "You",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
        }
    }
}

/**
 * Input bar for typing messages.
 */
@Composable
private fun ChatInputBar(
    inputText: String,
    isGenerating: Boolean,
    onInputTextChanged: (String) -> Unit,
    onSendMessage: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboardController = LocalSoftwareKeyboardController.current

    Surface(
        modifier = modifier,
        shadowElevation = 4.dp,
        color = MaterialTheme.colorScheme.surface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedTextField(
                value = inputText,
                onValueChange = onInputTextChanged,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Type a message...") },
                keyboardOptions = KeyboardOptions(
                    capitalization = KeyboardCapitalization.Sentences,
                    imeAction = ImeAction.Send
                ),
                keyboardActions = KeyboardActions(
                    onSend = {
                        if (inputText.isNotBlank() && !isGenerating) {
                            onSendMessage()
                            keyboardController?.hide()
                        }
                    }
                ),
                singleLine = false,
                maxLines = 4,
                enabled = !isGenerating,
                shape = RoundedCornerShape(24.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                )
            )

            AnimatedVisibility(
                visible = inputText.isNotBlank() && !isGenerating,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                FloatingActionButton(
                    onClick = onSendMessage,
                    modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Send,
                        contentDescription = "Send message"
                    )
                }
            }

            if (isGenerating) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}

/**
 * Loading view while model is being loaded.
 */
@Composable
private fun LoadingModelView(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(64.dp),
            strokeWidth = 4.dp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Loading AI Model...",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "This may take a few moments (697 MB)",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

/**
 * Error view when model fails to load.
 */
@Composable
private fun ErrorView(
    error: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Refresh,
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        Spacer(modifier = Modifier.height(24.dp))
        Text(
            text = "Failed to Load Model",
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = error,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onRetry,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Icon(
                imageVector = Icons.Default.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Retry")
        }
    }
}
