package com.example.lfm25.ui.components

import android.Manifest
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.lfm25.viewmodel.ChatMessage
import com.example.lfm25.viewmodel.ChatSession
import com.example.lfm25.viewmodel.ChatUiState
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    uiState: ChatUiState,
    onInputChanged: (String) -> Unit,
    onSendMessage: (Uri?) -> Unit,
    onClearChat: () -> Unit,
    onRetryLoadModel: () -> Unit,
    onNewSession: () -> Unit,
    onSwitchSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onToggleDrawer: () -> Unit,
    onStartVoice: () -> Unit,
    onStopVoice: () -> Unit,
    onThumbsUp: (String) -> Unit,
    onThumbsDown: (String) -> Unit,
    onImportFineTune: (Uri) -> Unit,
    onClearSnackbar: () -> Unit,
    modifier: Modifier = Modifier
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbarHost = remember { SnackbarHostState() }
    var pendingImage by remember { mutableStateOf<Uri?>(null) }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> pendingImage = uri }
    val ftPicker    = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri -> uri?.let { onImportFineTune(it) } }
    val micPerm     = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) onStartVoice() }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty())
            scope.launch { listState.animateScrollToItem(uiState.messages.size - 1) }
    }

    LaunchedEffect(uiState.snackbar) {
        uiState.snackbar?.let { snackbarHost.showSnackbar(it); onClearSnackbar() }
    }

    if (uiState.showSessionDrawer) {
        SessionDrawer(
            sessions = uiState.sessions,
            currentId = uiState.currentSessionId,
            onNew = onNewSession,
            onSwitch = onSwitchSession,
            onDelete = onDeleteSession,
            onDismiss = onToggleDrawer,
            onImportFT = { ftPicker.launch("*/*") }
        )
        return
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            val sessionTitle = uiState.sessions.find { it.id == uiState.currentSessionId }?.title ?: "Thunder AGI"
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onToggleDrawer) { Icon(Icons.Default.Menu, "Sessions") }
                },
                title = { Text(sessionTitle, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                actions = {
                    IconButton(onClick = onNewSession) { Icon(Icons.Default.Add, "New chat") }
                    if (uiState.messages.isNotEmpty()) {
                        IconButton(onClick = onClearChat) { Icon(Icons.Default.Delete, "Clear") }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            )
        },
        bottomBar = {
            if (uiState.modelLoaded) {
                Column {
                    pendingImage?.let { uri ->
                        Row(
                            Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            AsyncImage(model = uri, contentDescription = null,
                                modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                            Spacer(Modifier.width(8.dp))
                            Text("Image ready", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            IconButton(onClick = { pendingImage = null }) { Icon(Icons.Default.Close, null) }
                        }
                    }
                    InputBar(
                        text = uiState.inputText,
                        generating = uiState.isGenerating,
                        recording = uiState.isRecording,
                        onTextChange = onInputChanged,
                        onSend = { onSendMessage(pendingImage); pendingImage = null },
                        onAttach = { imagePicker.launch("image/*") },
                        onMicPress = { micPerm.launch(Manifest.permission.RECORD_AUDIO) },
                        onMicRelease = onStopVoice,
                        modifier = Modifier.imePadding()
                    )
                }
            }
        }
    ) { padding ->
        Box(modifier.fillMaxSize().padding(padding)) {
            when {
                uiState.isModelLoading -> LoadingView()
                !uiState.modelLoaded   -> ErrorView(uiState.error ?: "Unknown error", onRetryLoadModel)
                else -> MessageList(uiState.messages, listState, onThumbsUp, onThumbsDown, Modifier.fillMaxSize())
            }
        }
    }
}

// ── Session Drawer ────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SessionDrawer(
    sessions: List<ChatSession>,
    currentId: String?,
    onNew: () -> Unit,
    onSwitch: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
    onImportFT: () -> Unit
) {
    Column(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        TopAppBar(
            title = { Text("Chats") },
            navigationIcon = { IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, null) } },
            actions = { IconButton(onClick = onNew) { Icon(Icons.Default.Add, "New") } }
        )
        LazyColumn(Modifier.weight(1f)) {
            items(sessions, key = { it.id }) { s ->
                ListItem(
                    headlineContent = { Text(s.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier
                        .clickable { onSwitch(s.id) }
                        .background(if (s.id == currentId) MaterialTheme.colorScheme.primaryContainer.copy(.4f) else Color.Transparent),
                    trailingContent = {
                        IconButton(onClick = { onDelete(s.id) }) {
                            Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
                        }
                    }
                )
                HorizontalDivider()
            }
        }
        HorizontalDivider()
        ListItem(
            headlineContent = { Text("Import Fine-tune (.gguf)") },
            leadingContent = { Icon(Icons.Default.Upload, null) },
            modifier = Modifier.clickable { onImportFT() }
        )
        Spacer(Modifier.height(8.dp))
    }
}

// ── Message List ──────────────────────────────────────────────────────────────

@Composable
private fun MessageList(
    messages: List<ChatMessage>,
    listState: androidx.compose.foundation.lazy.LazyListState,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit,
    modifier: Modifier
) {
    if (messages.isEmpty()) {
        Box(modifier, contentAlignment = Alignment.Center) {
            Text("Say something!", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(state = listState, modifier = modifier,
            contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(messages, key = { it.id }) { msg ->
                MessageBubble(msg, onUp, onDown)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun MessageBubble(
    msg: ChatMessage,
    onUp: (String) -> Unit,
    onDown: (String) -> Unit
) {
    val isUser = msg.isUser
    val bg = if (isUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (isUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    val clipboard = LocalClipboardManager.current
    var showMenu by remember { mutableStateOf(false) }
    var showFeedback by remember { mutableStateOf(false) }

    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start) {
        if (!isUser) {
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondary), Alignment.Center) {
                Text("AI", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondary)
            }
            Spacer(Modifier.width(8.dp))
        }

        Column(horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(16.dp, 16.dp, if (isUser) 4.dp else 16.dp, if (isUser) 16.dp else 4.dp))
                    .background(bg)
                    .combinedClickable(
                        onClick = {},
                        onLongClick = { showMenu = true }
                    )
                    .padding(12.dp)
            ) {
                Column {
                    msg.mediaPath?.let { path ->
                        AsyncImage(model = File(path), contentDescription = null,
                            modifier = Modifier.fillMaxWidth().heightIn(max = 200.dp).clip(RoundedCornerShape(8.dp)),
                            contentScale = ContentScale.Fit)
                        Spacer(Modifier.height(4.dp))
                    }
                    if (msg.isLoading) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = fg)
                            Spacer(Modifier.width(8.dp))
                            Text("Thinking…", style = MaterialTheme.typography.bodyMedium, color = fg)
                        }
                    } else {
                        Text(msg.content, style = MaterialTheme.typography.bodyMedium, color = fg)
                    }
                }

                // Context menu
                DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                    DropdownMenuItem(
                        text = { Text("Copy") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, null) },
                        onClick = { clipboard.setText(AnnotatedString(msg.content)); showMenu = false }
                    )
                    if (!isUser && !msg.isLoading) {
                        DropdownMenuItem(
                            text = { Text("👍 Good") },
                            onClick = { onUp(msg.id); showMenu = false }
                        )
                        DropdownMenuItem(
                            text = { Text("👎 Bad") },
                            onClick = { onDown(msg.id); showMenu = false }
                        )
                    }
                }
            }
        }

        if (isUser) {
            Spacer(Modifier.width(8.dp))
            Box(Modifier.size(32.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer), Alignment.Center) {
                Text("You", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

// ── Input Bar ─────────────────────────────────────────────────────────────────

@Composable
private fun InputBar(
    text: String, generating: Boolean, recording: Boolean,
    onTextChange: (String) -> Unit, onSend: () -> Unit,
    onAttach: () -> Unit, onMicPress: () -> Unit, onMicRelease: () -> Unit,
    modifier: Modifier = Modifier
) {
    val keyboard = LocalSoftwareKeyboardController.current
    Surface(modifier, shadowElevation = 4.dp, color = MaterialTheme.colorScheme.surface) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onAttach, enabled = !generating) {
                Icon(Icons.Default.AttachFile, "Attach", tint = MaterialTheme.colorScheme.primary)
            }

            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (recording) "Listening…" else "Message…") },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = {
                    if (text.isNotBlank() && !generating) { onSend(); keyboard?.hide() }
                }),
                singleLine = false, maxLines = 4,
                enabled = !generating && !recording,
                shape = RoundedCornerShape(24.dp)
            )

            // Hold-to-speak mic
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(if (recording) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.secondaryContainer)
                    .pointerInput(Unit) {
                        detectTapGestures(onPress = { onMicPress(); tryAwaitRelease(); onMicRelease() })
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (recording) Icons.Default.Stop else Icons.Default.Mic,
                    "Voice",
                    tint = if (recording) Color.White else MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            AnimatedVisibility(text.isNotBlank() && !generating) {
                FloatingActionButton(onClick = onSend, modifier = Modifier.size(48.dp),
                    containerColor = MaterialTheme.colorScheme.primary) {
                    Icon(Icons.AutoMirrored.Filled.Send, "Send", tint = MaterialTheme.colorScheme.onPrimary)
                }
            }
            if (generating) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
        }
    }
}

// ── Loading / Error ───────────────────────────────────────────────────────────

@Composable
private fun LoadingView() {
    Column(Modifier.fillMaxSize(), Alignment.CenterHorizontally, Arrangement.Center) {
        CircularProgressIndicator(Modifier.size(64.dp), strokeWidth = 4.dp)
        Spacer(Modifier.height(24.dp))
        Text("Loading Thunder AGI…", style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun ErrorView(error: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(32.dp), Alignment.CenterHorizontally, Arrangement.Center) {
        Icon(Icons.Default.Error, null, Modifier.size(64.dp), tint = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(16.dp))
        Text("Failed to Load Model", style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(error, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}
