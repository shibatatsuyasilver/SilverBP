package com.silverbp.android.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.ui.components.ModelLoadBanner
import kotlinx.coroutines.launch
import java.io.File
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    onBack: (() -> Unit)? = null,
    vm: ChatViewModel = viewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)

    val gallery = rememberLauncherForActivityResult(
        ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = decodeUri(context, uri)
                if (bmp != null) vm.persistAndStageBitmap(context, bmp)
            }
        }
    }
    val camera = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview()
    ) { bmp: Bitmap? ->
        if (bmp != null) {
            scope.launch { vm.persistAndStageBitmap(context, bmp) }
        }
    }

    val voiceLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val text = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                .orEmpty()
            if (text.isNotBlank()) {
                input = if (input.isBlank()) text else "$input $text"
            }
        }
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) launchVoice(context, voiceLauncher::launch)
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage ?: return@LaunchedEffect
        snackbarHostState.showSnackbar(msg)
        vm.dismissError()
    }

    if (showAttachSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = sheetState,
        ) {
            Column(Modifier.padding(bottom = 16.dp)) {
                ListItem(
                    headlineContent = { Text("拍照") },
                    leadingContent = {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        camera.launch(null)
                    },
                )
                ListItem(
                    headlineContent = { Text("從相簿選擇") },
                    leadingContent = {
                        Icon(Icons.Filled.Image, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        gallery.launch(
                            androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageOnly
                            )
                        )
                    },
                )
            }
        }
    }

    ChatSessionsDrawer(
        sessions = state.sessions,
        activeSessionId = state.sessionId,
        drawerState = drawerState,
        onNewSession = {
            vm.newSession()
            scope.launch { drawerState.close() }
        },
        onSelectSession = { id ->
            vm.switchTo(id)
            scope.launch { drawerState.close() }
        },
        onRenameSession = vm::renameSession,
        onDeleteSession = vm::deleteSession,
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text(stringResource(R.string.tab_chat)) },
                    navigationIcon = {
                        if (onBack != null) {
                            IconButton(onClick = onBack) {
                                Icon(
                                    Icons.AutoMirrored.Filled.ArrowBack,
                                    contentDescription = "返回",
                                )
                            }
                        } else {
                            IconButton(onClick = { scope.launch { drawerState.open() } }) {
                                Icon(Icons.Filled.Menu, contentDescription = "對話列表")
                            }
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                ChatInputBar(
                    input = input,
                    onInputChange = { input = it },
                    stagedImagePath = state.stagedImagePath,
                    onClearImage = vm::clearStagedImage,
                    onAttach = { showAttachSheet = true },
                    onMic = {
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            launchVoice(context, voiceLauncher::launch)
                        } else {
                            micPermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onSend = {
                        val toSend = input
                        input = ""
                        vm.send(toSend)
                    },
                    canSend = state.canSend(input),
                    isGenerating = state.isGenerating,
                    onCancel = vm::cancelGeneration,
                )
            },
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                if (state.modelPhase != ModelLoadPhase.Ready &&
                    state.backend != RecognitionBackend.Cloud
                ) {
                    ModelLoadBanner(phase = state.modelPhase)
                }

                ChatMessageList(
                    messages = state.messages,
                    streamingAssistantId = state.streamingAssistantId,
                    streamingText = state.streamingText,
                    onSuggestion = { suggestion -> input = suggestion },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun ChatMessageList(
    messages: List<ChatMessage>,
    streamingAssistantId: String?,
    streamingText: String,
    onSuggestion: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (messages.isEmpty()) {
        Box(modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            EmptyChatHero(onSuggestion)
        }
        return
    }
    val listState = rememberLazyListState()
    LaunchedEffect(messages.size, streamingText) {
        // Smooth-scroll to most recent on new content (reverseLayout means index 0 is the newest).
        listState.animateScrollToItem(0)
    }
    LazyColumn(
        state = listState,
        reverseLayout = true,
        modifier = modifier,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            items = messages.reversed(),
            key = { it.id },
        ) { msg ->
            val displayText = if (msg.id == streamingAssistantId && streamingText.isNotEmpty()) {
                streamingText
            } else {
                msg.text
            }
            MessageBubble(
                role = msg.role,
                text = displayText,
                imagePath = msg.imagePath,
                isStreaming = msg.id == streamingAssistantId,
            )
        }
    }
}

@Composable
private fun MessageBubble(
    role: ChatMessage.Role,
    text: String,
    imagePath: String?,
    isStreaming: Boolean,
) {
    if (role == ChatMessage.Role.System) return
    val isUser = role == ChatMessage.Role.User
    val bubbleColor = if (isUser) {
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
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp,
                topEnd = 16.dp,
                bottomStart = if (isUser) 16.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 16.dp,
            ),
            color = bubbleColor,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                imagePath?.let { path ->
                    val bmp = remember(path) {
                        runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
                    }
                    bmp?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = 6.dp)
                                .size(180.dp)
                                .clip(RoundedCornerShape(8.dp)),
                        )
                    }
                }
                if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = text + (if (isStreaming) "…" else ""),
                            color = textColor,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                } else if (isStreaming) {
                    Text("…", color = textColor, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

@Composable
private fun EmptyChatHero(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "我最新的血壓是多少?",
        "本週血壓的平均",
        "上次運動表現如何?",
        "我達成了什麼徽章?",
    )
    Column(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "問問你的健康紀錄",
            style = MaterialTheme.typography.titleLarge,
            textAlign = TextAlign.Center,
        )
        Text(
            "可用文字、語音、照片提問。回答以你的紀錄為主。",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { s ->
                AssistChip(
                    onClick = { onSuggestion(s) },
                    label = { Text(s) },
                )
            }
        }
    }
}

@Composable
private fun ChatInputBar(
    input: String,
    onInputChange: (String) -> Unit,
    stagedImagePath: String?,
    onClearImage: () -> Unit,
    onAttach: () -> Unit,
    onMic: () -> Unit,
    onSend: () -> Unit,
    canSend: Boolean,
    isGenerating: Boolean,
    onCancel: () -> Unit,
) {
    Surface(tonalElevation = 3.dp) {
        // softInputMode defaults to adjustResize, so the activity window already
        // shrinks for the IME — adding imePadding() here would double-count and
        // leave a keyboard-sized gap between the input row and the keyboard top.
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp)
        ) {
            stagedImagePath?.let { path ->
                StagedImageStrip(path = path, onClear = onClearImage)
                Spacer(Modifier.height(4.dp))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier.fillMaxWidth().padding(top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Filled.Add, contentDescription = "附加")
                }
                IconButton(onClick = onMic) {
                    Icon(Icons.Filled.Mic, contentDescription = "語音輸入")
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text("輸入訊息…") },
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp),
                    minLines = 1,
                    maxLines = 5,
                )
                if (isGenerating) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Filled.Close, contentDescription = "取消")
                    }
                } else {
                    IconButton(onClick = onSend, enabled = canSend) {
                        Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "傳送")
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedImageStrip(path: String, onClear: () -> Unit) {
    val bmp = remember(path) {
        runCatching { BitmapFactory.decodeFile(File(path).absolutePath) }.getOrNull()
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        bmp?.let {
            androidx.compose.foundation.Image(
                bitmap = it.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            "已附加圖片",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = "移除圖片")
        }
    }
}

private fun launchVoice(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.TAIWAN.toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, "請說話")
    }
    runCatching { launch(intent) }
        .onFailure {
            android.util.Log.w("ChatScreen", "voice intent failed: ${it.message}")
        }
}

private fun decodeUri(context: Context, uri: Uri): Bitmap? = runCatching {
    context.contentResolver.openInputStream(uri)?.use { stream ->
        BitmapFactory.decodeStream(stream)
    }
}.getOrNull()
