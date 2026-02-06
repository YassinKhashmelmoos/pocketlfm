package com.example.lfm25

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.lfm25.ui.components.ChatScreen
import com.example.lfm25.ui.theme.Lfm25Theme
import com.example.lfm25.viewmodel.ChatViewModel

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            Lfm25Theme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ChatApp()
                }
            }
        }
    }
}

@Composable
fun ChatApp() {
    val viewModel: ChatViewModel = viewModel()
    // Use collectAsStateWithLifecycle to observe state changes
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    ChatScreen(
        uiState = uiState,
        onInputTextChanged = viewModel::onInputTextChanged,
        onSendMessage = viewModel::sendMessage,
        onClearChat = viewModel::clearChat,
        onRetryLoadModel = viewModel::retryLoadModel
    )
}
