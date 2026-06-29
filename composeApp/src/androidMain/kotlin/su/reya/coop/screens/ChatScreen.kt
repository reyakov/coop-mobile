package su.reya.coop.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Button
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_cancel
import coop.composeapp.generated.resources.ic_check_circle
import coop.composeapp.generated.resources.ic_send
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.nostr.Room
import su.reya.coop.nostr.formatAsGroupHeader
import su.reya.coop.nostr.humanReadable
import su.reya.coop.nostr.roomId
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily
import su.reya.coop.shared.nameFlow
import su.reya.coop.shared.pictureFlow
import su.reya.coop.short

@Composable
fun ChatScreen(id: Long, screening: Boolean = false) {
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val viewModel = LocalNostrViewModel.current

    // Get chat room by ID
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

    val displayName by remember(room) {
        room?.nameFlow(viewModel) ?: flowOf("Loading...")
    }.collectAsStateWithLifecycle("Loading...")

    val picture by remember(room) {
        room?.pictureFlow(viewModel) ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)

    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var newOtherMessages by remember { mutableIntStateOf(0) }
    var requireScreening by remember { mutableStateOf(screening) }

    val listState = rememberLazyListState()
    val messages = remember { mutableStateListOf<UnsignedEvent>() }

    val groupedMessages = remember(messages.toList()) {
        messages.groupBy { it.createdAt().formatAsGroupHeader() }
    }

    LaunchedEffect(id) {
        // Start loading spinner
        loading = true

        // Get messages
        val initialMessages = viewModel.getChatRoomMessages(id)
        messages.clear()
        messages.addAll(initialMessages)

        // Stop loading spinner
        loading = false

        // Get msg relays for each member
        viewModel.chatRoomConnect(id)

        // Handle new messages
        viewModel.newEvents.collect { event ->
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
                                picture = picture,
                                description = displayName,
                                size = 32.dp,
                            )
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = displayName,
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
                                        ChatMessage(it)
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
                                    .padding(16.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Button(
                                    onClick = { navigator.goBack() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "Reject",
                                        style = MaterialTheme.typography.titleMedium,
                                    )
                                }
                                FilledTonalButton(
                                    onClick = { requireScreening = false },
                                    modifier = Modifier.weight(1f)
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
                                    viewModel.sendMessage(id, text)
                                    text = ""
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

    val viewModel = LocalNostrViewModel.current
    val scope = rememberCoroutineScope()

    var isContact by remember { mutableStateOf(false) }
    var mutualContacts by remember { mutableStateOf<Set<PublicKey>>(emptySet()) }
    var lastActivity by remember { mutableStateOf<Timestamp?>(null) }

    val metadataFlow = remember(pubkey) { viewModel.getMetadata(pubkey) }
    val metadata by metadataFlow.collectAsStateWithLifecycle()

    val profile = metadata?.asRecord()
    val displayName = profile?.displayName ?: profile?.name ?: "No name"
    val picture = profile?.picture

    LaunchedEffect(pubkey) {
        scope.launch {
            // Check contact
            viewModel.verifyContact(pubkey).let { isContact = it }
            // Get mutual contacts
            viewModel.mutualContacts(pubkey).let { mutualContacts = it }
            // Get the last activity
            viewModel.verifyActivity(pubkey)?.let { lastActivity = it }
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
                picture = picture,
                description = "Profile picture",
                modifier = Modifier.size(120.dp),
                shape = MaterialShapes.Cookie12Sided.toShape(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = displayName,
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
    rumor: UnsignedEvent
) {
    val viewModel = LocalNostrViewModel.current
    val currentUser = viewModel.currentUser()
    val isMine = rumor.author() == currentUser

    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    val containerColor =
        if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.tertiaryContainer

    val contentColor =
        if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onTertiaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = bubbleShape,
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .clickable(
                        onClick = {
                            val id = rumor.id()
                            if (id != null) {
                                val sent = viewModel.isMessageSent(id)
                                println("Sent: $sent")
                            }
                        }
                    )
            ) {
                Text(
                    text = rumor.content(),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
    }
}

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit
) {
    Surface(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            TextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = { Text("Message") },
                shape = RoundedCornerShape(28.dp),
                colors = TextFieldDefaults.colors(
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.size(8.dp))
            FilledTonalIconButton(
                onClick = onSend,
                modifier = Modifier.size(56.dp),
                colors = IconButtonDefaults.filledTonalIconButtonColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurfaceVariant
                )
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_send),
                    contentDescription = "Send"
                )
            }
        }
    }
}
