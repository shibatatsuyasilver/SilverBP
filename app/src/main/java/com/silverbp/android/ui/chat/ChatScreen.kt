package com.silverbp.android.ui.chat

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.silverbp.android.R
import com.silverbp.android.chat.ChatMessage
import com.silverbp.android.recognition.ModelLoadPhase
import com.silverbp.android.recognition.RecognitionBackend
import com.silverbp.android.recognition.decodeFileWithExif
import com.silverbp.android.recognition.decodeUriWithExif
import androidx.compose.ui.graphics.Color
import com.silverbp.android.ui.components.ExpressiveAssistChip
import com.silverbp.android.ui.components.ModelLoadBanner
import com.silverbp.android.ui.components.StandardCard
import com.silverbp.android.ui.theme.AppSpacing
import com.silverbp.android.ui.theme.ForgePrimary
import com.silverbp.android.ui.theme.PillShape
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
    // 相機需要 CAMERA 權限,未授權就啟動 TakePicturePreview 會擲出 SecurityException。
    val cameraDeniedMsg = stringResource(R.string.camera_permission_denied)
    val cameraPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            camera.launch(null)
        } else {
            scope.launch { snackbarHostState.showSnackbar(cameraDeniedMsg) }
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
                    headlineContent = { Text(stringResource(R.string.chat_attach_camera)) },
                    leadingContent = {
                        Icon(Icons.Filled.PhotoCamera, contentDescription = null)
                    },
                    modifier = Modifier.clickable {
                        showAttachSheet = false
                        val granted = ContextCompat.checkSelfPermission(
                            context, Manifest.permission.CAMERA,
                        ) == PackageManager.PERMISSION_GRANTED
                        if (granted) {
                            camera.launch(null)
                        } else {
                            cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.chat_attach_gallery)) },
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
        // Flat Column instead of an inner Scaffold. The outer HomeWithTabs
        // Scaffold already supplies the bottom NavigationBar; nesting a second
        // Scaffold with its own bottomBar squashed the message area to ~0 dp
        // when the IME pushed the layout up, hiding all message bubbles.
        //
        // HomeWithTabs already insets this content below the status bar via its
        // own Scaffold content padding, so we do NOT re-pad for it here. We used
        // to add a manual status-bar padding on top of that, which double-counted
        // the inset and pushed this header a full status-bar height lower than
        // every other tab. The TopAppBar's own windowInsets is zeroed below for
        // the same reason (it must not add a third inset).
        Column(
            Modifier.fillMaxSize()
        ) {
            TopAppBar(
                // As a tab (onBack == null) HomeWithTabs' Scaffold already padded
                // for the status bar, so we zero the bar's own inset. As a root
                // route (onBack != null, opened from the floating assistant pill)
                // there is no outer Scaffold, so apply the default status-bar inset
                // — otherwise the "助理" title renders under the status bar.
                windowInsets = if (onBack != null) {
                    TopAppBarDefaults.windowInsets
                } else {
                    WindowInsets(0, 0, 0, 0)
                },
                title = { Text(stringResource(R.string.tab_chat)) },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.a11y_back),
                            )
                        }
                    } else {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.a11y_chat_list),
                            )
                        }
                    }
                },
                actions = {
                    // When launched as a route (back arrow on the left), keep the
                    // chat-history drawer reachable from the right (like Google Health).
                    if (onBack != null) {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                Icons.Filled.Menu,
                                contentDescription = stringResource(R.string.a11y_chat_list),
                            )
                        }
                    }
                },
            )
            if (state.modelPhase != ModelLoadPhase.Ready &&
                state.backend != RecognitionBackend.Cloud
            ) {
                ModelLoadBanner(phase = state.modelPhase)
            }
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ChatMessageList(
                    messages = state.messages,
                    streamingAssistantId = state.streamingAssistantId,
                    streamingText = state.streamingText,
                    onSuggestion = { suggestion -> input = suggestion },
                    modifier = Modifier.fillMaxSize(),
                )
                SnackbarHost(
                    snackbarHostState,
                    modifier = Modifier.align(Alignment.BottomCenter),
                )
            }
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
        Box(
            modifier
                .fillMaxSize()
                .padding(horizontal = AppSpacing.screenH, vertical = AppSpacing.screenV),
            contentAlignment = Alignment.Center,
        ) {
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
        contentPadding = PaddingValues(
            horizontal = AppSpacing.screenH,
            vertical = AppSpacing.screenV,
        ),
        verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap + AppSpacing.tight),
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
    // M3 Expressive bubbles (design/mockups/07-chat.html .bubble.me / .bubble.bot):
    // the user bubble keeps the filled brand-primary fill (clear "this is me")
    // with a squared bottom-end corner; the assistant bubble adopts the card
    // family look — a surfaceContainerHigh fill with a soft card shadow and a
    // squared bottom-start corner — so the thread reads as rounded speech bubbles
    // anchored to their sender.
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primary
    } else {
        MaterialTheme.colorScheme.surfaceContainerHigh
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
    ) {
        Surface(
            // Fully-rounded bubble (22dp) with one squared corner pointing at the
            // sender: bottom-end for me, bottom-start for the assistant.
            shape = RoundedCornerShape(
                topStart = AppSpacing.cardCorner,
                topEnd = AppSpacing.cardCorner,
                bottomStart = if (isUser) AppSpacing.cardCorner else AppSpacing.itemGap,
                bottomEnd = if (isUser) AppSpacing.itemGap else AppSpacing.cardCorner,
            ),
            color = bubbleColor,
            // Lift only the assistant card off the page; the filled user bubble
            // already separates itself with colour.
            tonalElevation = 0.dp,
            shadowElevation = if (isUser) 0.dp else 2.dp,
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Column(
                Modifier.padding(
                    horizontal = AppSpacing.cardPadding - AppSpacing.tight,
                    vertical = AppSpacing.itemGap + AppSpacing.tight,
                )
            ) {
                imagePath?.let { path ->
                    val bmp = remember(path) {
                        decodeFileWithExif(File(path), maxDim = CHAT_THUMBNAIL_MAX_DIM)
                    }
                    bmp?.let {
                        androidx.compose.foundation.Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .padding(bottom = AppSpacing.itemGap)
                                .size(180.dp)
                                .clip(RoundedCornerShape(AppSpacing.itemGap + AppSpacing.tight)),
                        )
                    }
                }
                if (text.isNotEmpty()) {
                    SelectionContainer {
                        Text(
                            text = text + (if (isStreaming) "…" else ""),
                            color = textColor,
                            // Larger body text for older-adult legibility.
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                } else if (isStreaming) {
                    Text("…", color = textColor, style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun EmptyChatHero(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        stringResource(R.string.chat_suggestion_latest),
        stringResource(R.string.chat_suggestion_weekly_avg),
        stringResource(R.string.chat_suggestion_last_exercise),
        stringResource(R.string.chat_suggestion_badges),
    )
    // The empty state now reads as one rounded content card (card-family look),
    // led by a tinted chat-icon tile — the same idiom Today/UnifiedHistory use
    // for their hero/metric tiles — with the suggestion chips grouped inside.
    StandardCard(
        cornerRadius = AppSpacing.heroCorner,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(ForgePrimary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Chat,
                    contentDescription = null,
                    modifier = Modifier.size(28.dp),
                    tint = ForgePrimary,
                )
            }
            Text(
                stringResource(R.string.chat_empty_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
            Text(
                stringResource(R.string.chat_empty_subtitle),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(AppSpacing.tight))
            // Suggestion chips wrap like the mockup's `.row` (flex-wrap),
            // rendered as M3 Expressive assist chips.
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(
                    AppSpacing.itemGap,
                    Alignment.CenterHorizontally,
                ),
                verticalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                suggestions.forEach { s ->
                    ExpressiveAssistChip(
                        label = s,
                        onClick = { onSuggestion(s) },
                    )
                }
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
    // M3 Expressive composer (design/mockups/07-chat.html .inputbar): a
    // surfaceContainerLow bar with a hairline top border, neutral +/mic icon
    // buttons, a pill-rounded field, and a circular primary send button.
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        // imePadding lifts the input row above the keyboard. We deliberately
        // do NOT declare windowSoftInputMode in the manifest — Vivo OriginOS's
        // adjustResize collapses the visible content area to zero under
        // edge-to-edge, and adjustPan pushes the TopAppBar off screen. Letting
        // Compose absorb the IME inset here is the only path that works
        // across OEMs. AppNavHost hides the outer NavigationBar when the IME
        // is open in the Chat tab so this padding doesn't leave a NavBar-
        // sized gap between the input row and the keyboard top.
        Column(
            Modifier
                .fillMaxWidth()
                .imePadding()
                .padding(horizontal = AppSpacing.itemGap, vertical = AppSpacing.itemGap)
        ) {
            stagedImagePath?.let { path ->
                StagedImageStrip(path = path, onClear = onClearImage)
                Spacer(Modifier.height(AppSpacing.tight))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(top = AppSpacing.itemGap),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(AppSpacing.itemGap),
            ) {
                IconButton(onClick = onAttach) {
                    Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.a11y_attach))
                }
                IconButton(onClick = onMic) {
                    Icon(Icons.Filled.Mic, contentDescription = stringResource(R.string.a11y_voice_input))
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = onInputChange,
                    placeholder = { Text(stringResource(R.string.chat_input_placeholder)) },
                    modifier = Modifier.weight(1f),
                    // Pill-rounded field so the composer reads as part of the
                    // rounded card family (mockup .inputbar .field uses a full pill).
                    shape = PillShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Transparent,
                    ),
                    minLines = 1,
                    maxLines = 5,
                )
                if (isGenerating) {
                    // While streaming, the primary action is "stop" — a clear
                    // filled error-tinted target, generously sized.
                    FilledIconButton(
                        onClick = onCancel,
                        modifier = Modifier.size(AppSpacing.touchTarget),
                        shape = CircleShape,
                        colors = IconButtonDefaults.filledIconButtonColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer,
                            contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        ),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.a11y_cancel))
                    }
                } else {
                    // Send is the screen's primary action: a circular filled
                    // brand-primary button (mockup .sendbtn) so it stands out from
                    // the neutral attach/mic icons.
                    FilledIconButton(
                        onClick = onSend,
                        enabled = canSend,
                        modifier = Modifier.size(AppSpacing.touchTarget),
                        shape = CircleShape,
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.Send,
                            contentDescription = stringResource(R.string.a11y_send),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StagedImageStrip(path: String, onClear: () -> Unit) {
    val bmp = remember(path) {
        decodeFileWithExif(File(path), maxDim = CHAT_THUMBNAIL_MAX_DIM)
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
                    // Same rounded-tile idiom the message-bubble thumbnail uses.
                    .clip(RoundedCornerShape(AppSpacing.itemGap + AppSpacing.tight))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
            )
        }
        Spacer(Modifier.size(8.dp))
        Text(
            stringResource(R.string.chat_image_attached),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onClear) {
            Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.a11y_remove_image))
        }
    }
}

private fun launchVoice(context: Context, launch: (Intent) -> Unit) {
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        )
        // Voice recognition language follows the active app locale so the
        // ASR engine returns text in the user's language.
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
        putExtra(RecognizerIntent.EXTRA_PROMPT, context.getString(R.string.chat_voice_prompt))
    }
    runCatching { launch(intent) }
        .onFailure {
            android.util.Log.w("ChatScreen", "voice intent failed: ${it.message}")
        }
}

private fun decodeUri(context: Context, uri: Uri): Bitmap? =
    decodeUriWithExif(context, uri, maxDim = CHAT_ATTACHMENT_MAX_DIM)

private const val CHAT_ATTACHMENT_MAX_DIM = 1536
private const val CHAT_THUMBNAIL_MAX_DIM = 512
