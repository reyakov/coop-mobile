package su.reya.coop.screens

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_add_circle
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_audio
import coop.composeapp.generated.resources.ic_cancel
import coop.composeapp.generated.resources.ic_check_circle
import coop.composeapp.generated.resources.ic_send
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalChatViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.Screen
import su.reya.coop.extractUrls
import su.reya.coop.formatAsGroupHeader
import su.reya.coop.formatAsTime
import su.reya.coop.humanReadable
import su.reya.coop.isImageUrl
import su.reya.coop.rememberUiState
import su.reya.coop.removeImageUrls
import su.reya.coop.roomId
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(id: Long, screening: Boolean = false) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val nostrViewModel = LocalNostrViewModel.current
    val chatViewModel = LocalChatViewModel.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Get current user
    val currentUser by nostrViewModel.currentUserProfile.collectAsStateWithLifecycle()

    // Get chat room by ID
    val chatRooms by chatViewModel.chatRooms.collectAsStateWithLifecycle()
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

    val roomState by (room as Room).rememberUiState(nostrViewModel, currentUser?.publicKey)
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var newOtherMessages by remember { mutableIntStateOf(0) }
    var requireScreening by remember { mutableStateOf(screening) }

    val messages = remember { mutableStateListOf<UnsignedEvent>() }
    val groupedMessages = remember(messages.toList()) {
        messages.groupBy { it.createdAt().formatAsGroupHeader() }
    }

    val sendFile = { uri: Uri ->
        scope.launch {
            try {
                // Read file
                val file = withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                }

                // Parse the file content type
                val type = context.contentResolver.getType(uri)

                // Send message
                chatViewModel.sendFileMessage(id, file, type)
            } catch (e: Exception) {
                snackbarHostState.showSnackbar("Error: ${e.message}")
            }
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

    LaunchedEffect(id) {
        // Get messages
        val initialMessages = chatViewModel.getChatRoomMessages(id)
        messages.clear()
        messages.addAll(initialMessages)

        // Stop loading spinner
        loading = false

        // Get msg relays for each member
        chatViewModel.chatRoomConnect(id)

        // Handle new messages
        chatViewModel.newEvents.collect { event ->
            if (event.roomId() == id) {
                if (event.id() !in messages.map { it.id() }) {
                    messages.add(0, event)
                }
            } else {
                // If the event is not in the current room, it's a new message from another user
                newOtherMessages++
            }
        }
    }

    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(0)
        }
    }

    Scaffold(
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
                    if (requireScreening) {
                        room?.let { ScreenerCard(it) }
                    }

                    when (messages.isNotEmpty()) {
                        true -> {
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentPadding = PaddingValues(16.dp),
                                reverseLayout = true,
                                state = listState,
                            ) {
                                groupedMessages.forEach { (dateHeader, messagesInGroup) ->
                                    items(
                                        items = messagesInGroup,
                                        key = { it.id()?.toBech32() ?: it.hashCode() }
                                    ) {
                                        ChatMessage(
                                            rumor = it,
                                            isMine = currentUser?.publicKey == it.author()
                                        )
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

                    when (requireScreening) {
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
                                    onClick = { requireScreening = false },
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
                            ChatInput(
                                value = text,
                                onValueChange = { text = it },
                                onSend = {
                                    chatViewModel.sendMessage(id, text)
                                    text = ""
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
                                            putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak now...")
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
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenerCard(room: Room) {
    val pubkey = room.members.firstOrNull() ?: return

    val nostrViewModel = LocalNostrViewModel.current
    val scope = rememberCoroutineScope()

    var isContact by remember { mutableStateOf(false) }
    var mutualContacts by remember { mutableStateOf<Set<PublicKey>>(emptySet()) }
    var lastActivity by remember { mutableStateOf<Timestamp?>(null) }

    val profileFlow = remember(pubkey) { nostrViewModel.getMetadata(pubkey) }
    val profile by profileFlow.collectAsStateWithLifecycle()

    LaunchedEffect(pubkey) {
        scope.launch {
            // Check contact
            nostrViewModel.verifyContact(pubkey).let { isContact = it }
            // Get mutual contacts
            nostrViewModel.mutualContacts(pubkey).let { mutualContacts = it }
            // Get the last activity
            nostrViewModel.verifyActivity(pubkey)?.let { lastActivity = it }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(
                picture = profile?.picture,
                description = "Profile picture",
                modifier = Modifier.size(120.dp),
                shape = MaterialShapes.Cookie12Sided.toShape(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = profile?.name ?: "No name",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLargeEmphasized.copy(
                        fontFamily = getExpressiveFontFamily()
                    ),
                )
                Text(
                    text = pubkey.short(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (isContact) Res.drawable.ic_check_circle else Res.drawable.ic_cancel
                    ),
                    contentDescription = "Warning",
                    tint = if (isContact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isContact) "Contact" else "Not a contact",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (mutualContacts.isNotEmpty()) Res.drawable.ic_check_circle else Res.drawable.ic_cancel
                    ),
                    contentDescription = "Warning",
                    tint = if (mutualContacts.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (mutualContacts.isEmpty()) "No contacts in common" else "${mutualContacts.size} contacts in common",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_check_circle),
                    contentDescription = "Warning",
                    tint = if (lastActivity != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Text(
                    text = if (lastActivity == null) "Don't have any public activities" else "Last activity at ${lastActivity?.humanReadable()}",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
        }
    }
}

@Composable
fun DateSeparator(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}

@Composable
fun ChatMessage(
    rumor: UnsignedEvent,
    isMine: Boolean = false,
) {
    val content = rumor.content()
    val images = remember(content) { content.extractUrls().filter { it.isImageUrl() } }
    val timestamp = remember(rumor) { rumor.createdAt().formatAsTime() }

    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 4.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 20.dp)
    }

    val containerColor =
        if (!isMine) MaterialTheme.colorScheme.surfaceContainer else MaterialTheme.colorScheme.primaryContainer

    val contentColor =
        if (!isMine) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onPrimaryContainer

    val annotatedContent = remember(content, contentColor) {
        buildAnnotatedString {
            val cleanedContent = content.removeImageUrls()
            val urlRegex = Regex("(https?://\\S+)", RegexOption.IGNORE_CASE)
            var lastIndex = 0
            urlRegex.findAll(cleanedContent).forEach { matchResult ->
                append(cleanedContent.substring(lastIndex, matchResult.range.first))
                val url = matchResult.value
                pushLink(
                    LinkAnnotation.Url(
                        url = url,
                        styles = TextLinkStyles(
                            style = SpanStyle(
                                color = contentColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = FontWeight.Medium
                            )
                        )
                    )
                )
                append(url)
                pop()
                lastIndex = matchResult.range.last + 1
            }
            append(cleanedContent.substring(lastIndex))
        }
    }

    var isMessageClicked by remember { mutableStateOf(false) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            modifier = Modifier.clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                isMessageClicked = !isMessageClicked
            },
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (annotatedContent.isNotBlank()) {
                Surface(
                    color = containerColor,
                    contentColor = contentColor,
                    shape = bubbleShape,
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    Text(
                        text = annotatedContent,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
            images.forEach { imageUrl ->
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.widthIn(max = 280.dp)
                ) {
                    AsyncImage(
                        model = imageUrl,
                        contentDescription = "Image from chat",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.FillWidth
                    )
                }
            }
            AnimatedVisibility(
                visible = isMessageClicked,
                enter = fadeIn() + expandVertically(),
                exit = fadeOut() + shrinkVertically()
            ) {
                Text(
                    text = timestamp,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline,
                    modifier = Modifier.align(
                        if (isMine) Alignment.End else Alignment.Start
                    )
                )
            }
        }
    }
}

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onUpload: () -> Unit,
    onMicClick: () -> Unit
) {
    Row(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom
    ) {
        TextField(
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(28.dp),
            colors = TextFieldDefaults.colors(
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text("Message") },
            leadingIcon = {
                IconButton(onClick = onUpload) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_add_circle),
                        contentDescription = "Upload",
                    )
                }
            },
        )
        Spacer(modifier = Modifier.size(8.dp))
        AnimatedContent(
            targetState = value.isNotEmpty(),
            transitionSpec = { (scaleIn() + fadeIn()) togetherWith (scaleOut() + fadeOut()) },
            label = "send_mic_transition"
        ) { isNotEmpty ->
            if (isNotEmpty) {
                IconButton(
                    onClick = onSend,
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.iconButtonColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                        contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_send),
                        contentDescription = "Send"
                    )
                }
            } else {
                FilledTonalIconButton(
                    onClick = onMicClick,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_audio),
                        contentDescription = "Speech to Text"
                    )
                }
            }
        }
    }
}
