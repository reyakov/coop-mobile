package su.reya.coop.screens.chat

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenuGroup
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_copy
import coop.composeapp.generated.resources.ic_reply
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.EventId
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalProfileCache
import su.reya.coop.LocalSettings
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.RoomUiState
import su.reya.coop.Screen
import su.reya.coop.formatAsGroup
import su.reya.coop.shared.Avatar
import su.reya.coop.uiStateFlow
import su.reya.coop.viewmodel.AccountViewModel
import su.reya.coop.viewmodel.ChatScreenViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    viewModel: ChatScreenViewModel,
    accountViewModel: AccountViewModel,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val clipboardManager = LocalClipboard.current
    val navigator = LocalNavigator.current
    val profileCache = LocalProfileCache.current
    val settings = LocalSettings.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    val id = viewModel.id
    val currentUser by viewModel.currentUser.collectAsStateWithLifecycle()
    val chatRooms by viewModel.chatRooms.collectAsStateWithLifecycle()
    val room by remember(id) { derivedStateOf { chatRooms.firstOrNull { it.id == id } } }

    // Show empty screen
    if (room == null) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Something went wrong.",
                style = MaterialTheme.typography.titleMediumEmphasized,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
        return
    }

    val loading = viewModel.loading
    val newOtherMessages = viewModel.newOtherMessages
    val requireScreening = viewModel.requireScreening
    val messages = viewModel.messages

    val groupedMessages =
        remember { derivedStateOf { messages.groupBy { it.createdAt().formatAsGroup() } } }

    val roomState by remember(id, currentUser?.publicKey) {
        (room as Room).uiStateFlow(profileCache, currentUser?.publicKey)
    }.collectAsStateWithLifecycle(RoomUiState())

    var text by remember { mutableStateOf("") }
    var selectedMessage by remember { mutableStateOf<Pair<MessageModel, Rect>?>(null) }
    var replyingTo by remember { mutableStateOf<MessageModel?>(null) }

    val blurAmount by animateDpAsState(
        targetValue = if (selectedMessage != null) 8.dp else 0.dp,
        label = "blurAnimation"
    )

    val goToMessage = { eventId: EventId? ->
        if (eventId != null) {
            scope.launch {
                var targetIndex = -1
                var currentIndex = 0

                for (group in groupedMessages.value) {
                    val msgInGroup = group.value
                    val idx = msgInGroup.indexOfFirst { it.id() == eventId }
                    if (idx != -1) {
                        targetIndex = currentIndex + idx
                        break
                    }
                    currentIndex += msgInGroup.size + 1
                }

                if (targetIndex != -1) {
                    listState.animateScrollToItem(targetIndex)
                }
            }
        }
    }

    val sendFile = { uri: Uri ->
        scope.launch {
            // Read file on IO dispatcher
            val file = withContext(coroutineDispatcher) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            // Parse the file content type
            val type = context.contentResolver.getType(uri)

            // Send message (handles errors internally via ViewModel)
            viewModel.sendFileMessage(file, type)
        }
    }

    val fileLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { sendFile(it) }
        }

    val sttLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val data = result.data
                val results = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                if (!results.isNullOrEmpty()) text = results[0]
            }
        }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Scaffold(
            modifier = Modifier.blur(blurAmount),
            contentWindowInsets = ScaffoldDefaults.contentWindowInsets.union(WindowInsets.ime),
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            snackbarHost = { SnackbarHost(snackbarHostState) },
            topBar = {
                TopAppBar(
                    title = {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                room?.members?.firstOrNull()?.let { pubkey ->
                                    navigator.navigate(Screen.Profile(pubkey.toBech32()))
                                }
                            }
                        ) {
                            if (loading) {
                                LoadingIndicator(modifier = Modifier.size(32.dp))
                            } else {
                                Avatar(
                                    picture = roomState.picture,
                                    description = roomState.name,
                                    size = 32.dp,
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = roomState.name,
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                    },
                    navigationIcon = {
                        BadgedBox(
                            badge = {
                                if (newOtherMessages > 0) {
                                    Badge {
                                        Text(newOtherMessages.toString())
                                    }
                                }
                            }
                        ) {
                            IconButton(onClick = { navigator.goBack() }) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_arrow_back),
                                    contentDescription = "Back"
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                )
            },
            content = { innerPadding ->
                Surface(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(top = innerPadding.calculateTopPadding()),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        if (requireScreening && settings.screening) {
                            room?.let { ScreenerCard(accountViewModel, it) }
                        }

                        when (messages.isNotEmpty()) {
                            true -> {
                                LazyColumn(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    state = listState,
                                    reverseLayout = true,
                                    contentPadding = PaddingValues(16.dp),
                                ) {
                                    groupedMessages.value.forEach { (dateHeader, messagesInGroup) ->
                                        items(
                                            items = messagesInGroup,
                                            key = { it.ensureId().id()?.toHex()!! }
                                        ) { event ->
                                            val model =
                                                rememberMessageModel(event, currentUser?.publicKey)

                                            val replyPreview =
                                                remember(model.replyEventIds, messages.size) {
                                                    model.replyEventIds.firstOrNull()
                                                        ?.let { replyId ->
                                                            messages.find { it.id() == replyId }
                                                        }
                                                }

                                            Column(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .animateItem(),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                replyPreview?.let { previewEvent ->
                                                    ReplyPreview(
                                                        event = previewEvent,
                                                        isMine = model.isMine,
                                                        onClick = { goToMessage(previewEvent.id()) }
                                                    )
                                                }
                                                ChatMessage(
                                                    model = model,
                                                    modifier = Modifier.graphicsLayer {
                                                        alpha =
                                                            if (selectedMessage?.first?.id == model.id) 0f else 1f
                                                    },
                                                    onLongClick = { rect ->
                                                        selectedMessage = model to rect
                                                    }
                                                )
                                            }
                                        }
                                        item {
                                            DateSeparator(dateHeader)
                                        }
                                    }
                                }
                            }

                            false -> {
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxWidth(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Column(
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            text = "No messages yet",
                                            style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                                fontWeight = FontWeight.SemiBold
                                            ),
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                        Text(
                                            text = "Your conversations will appear here.",
                                            style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.outline
                                        )
                                    }
                                }
                            }
                        }

                        when (requireScreening && settings.screening) {
                            true -> {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    Button(
                                        onClick = { navigator.goBack() },
                                        modifier = Modifier
                                            .weight(1f)
                                            .size(ButtonDefaults.MediumContainerHeight)
                                    ) {
                                        Text(
                                            text = "Reject",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                    FilledTonalButton(
                                        onClick = { viewModel.requireScreening = false },
                                        modifier = Modifier
                                            .weight(1f)
                                            .size(ButtonDefaults.MediumContainerHeight)
                                    ) {
                                        Text(
                                            text = "Accept",
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                    }
                                }
                            }

                            else -> {
                                AnimatedVisibility(visible = replyingTo != null) {
                                    replyingTo?.let {
                                        ReplyBox(it) { replyingTo = null }
                                    }
                                }
                                ChatInput(
                                    value = text,
                                    onValueChange = { text = it },
                                    onSend = {
                                        viewModel.sendMessage(text, replyingTo?.id)
                                        text = ""
                                        replyingTo = null
                                    },
                                    onUpload = {
                                        fileLauncher.launch("image/*")
                                    },
                                    onMicClick = {
                                        val intent =
                                            Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                                putExtra(
                                                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                                                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
                                                )
                                                putExtra(
                                                    RecognizerIntent.EXTRA_PROMPT,
                                                    "Speak now..."
                                                )
                                            }
                                        try {
                                            sttLauncher.launch(intent)
                                        } catch (e: Exception) {
                                            scope.launch {
                                                snackbarHostState.showSnackbar("Speech recognition not available")
                                            }
                                        }
                                    }
                                )
                            }
                        }
                    }
                }
            }
        )
        AnimatedVisibility(
            visible = selectedMessage != null,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            val (model, bounds) = selectedMessage ?: return@AnimatedVisibility

            val density = LocalDensity.current
            val windowInfo = LocalWindowInfo.current
            val windowHeight = windowInfo.containerSize.height
            val scrollState = rememberScrollState()

            var menuHeight by remember { mutableFloatStateOf(0f) }
            val spacing = with(density) { 12.dp.toPx() }
            val showAbove =
                (windowHeight - bounds.bottom) < (menuHeight + spacing) && bounds.top > (menuHeight + spacing)

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable { selectedMessage = null }
                    .verticalScroll(scrollState),
            ) {
                val totalExtraHeight = if (menuHeight > 0) menuHeight + spacing else 300f
                val contentBottom = with(density) { (bounds.bottom + totalExtraHeight).toDp() }

                Spacer(modifier = Modifier.height(contentBottom + 200.dp))

                ChatMessage(
                    model = model,
                    modifier = Modifier
                        .offset { IntOffset(0, bounds.top.toInt()) }
                        .padding(horizontal = 16.dp)
                )

                val menuOffset = if (showAbove) {
                    bounds.top - menuHeight - spacing
                } else {
                    bounds.bottom + spacing
                }

                Box(
                    modifier = Modifier
                        .offset { IntOffset(0, menuOffset.toInt().coerceAtLeast(0)) }
                        .onGloballyPositioned { menuHeight = it.size.height.toFloat() }
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    contentAlignment = if (model.isMine) Alignment.CenterEnd else Alignment.CenterStart
                ) {
                    ContextMenu { action ->
                        when (action) {
                            "Copy" -> {
                                scope.launch {
                                    val content = model.annotatedContent
                                    val data = ClipData.newPlainText(content, content)
                                    clipboardManager.setClipEntry(ClipEntry(data))
                                }
                            }

                            "Reply" -> {
                                replyingTo = model
                            }

                            else -> {}
                        }
                        selectedMessage = null
                    }
                }
            }
        }
    }
}

@Composable
private fun ReplyBox(model: MessageModel, onDismiss: () -> Unit) {
    val profileCache = LocalProfileCache.current
    val profileFlow = remember(model) { profileCache.getMetadata(model.author) }
    val profile by profileFlow.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() },
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = RoundedCornerShape(16.dp),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                Text(
                    text = "Replying to ${profile?.name}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer.copy(
                        alpha = 0.6f
                    ),
                )
                Text(
                    text = model.annotatedContent.toString().ifBlank {
                        if (model.images.isNotEmpty()) "[Image]" else ""
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ReplyPreview(
    event: UnsignedEvent,
    isMine: Boolean = false,
    onClick: () -> Unit
) {
    val profileCache = LocalProfileCache.current
    val profileFlow = remember(event) { profileCache.getMetadata(event.author()) }
    val profile by profileFlow.collectAsStateWithLifecycle()

    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    Box(
        modifier = Modifier.fillMaxWidth(),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Surface(
            modifier = Modifier
                .widthIn(max = 280.dp)
                .clickable(onClick = onClick),
            color = MaterialTheme.colorScheme.tertiaryContainer,
            shape = bubbleShape,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(
                    text = profile?.name ?: "Unknown",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
                Text(
                    text = event.content(),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                    maxLines = 1,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun ContextMenu(onAction: (String) -> Unit) {
    val menuItems = listOf(
        "Copy" to Res.drawable.ic_copy,
        "Reply" to Res.drawable.ic_reply
    )

    DropdownMenuGroup(
        shapes = MenuDefaults.groupShape(1, 1),
        containerColor = MenuDefaults.groupVibrantContainerColor,
        modifier = Modifier.width(220.dp)
    ) {
        val itemCount = menuItems.size

        menuItems.forEachIndexed { index, (label, icon) ->
            DropdownMenuItem(
                shapes = MenuDefaults.itemShape(index, itemCount),
                colors = MenuDefaults.selectableItemVibrantColors(),
                text = { Text(label) },
                leadingIcon = {
                    Icon(
                        painter = painterResource(icon),
                        contentDescription = label,
                    )
                },
                checked = false,
                onCheckedChange = { _ -> onAction(label) },
            )
        }
    }
}
