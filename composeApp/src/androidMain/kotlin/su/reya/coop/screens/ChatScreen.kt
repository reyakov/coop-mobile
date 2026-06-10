package su.reya.coop.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_send
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.formatAsGroupHeader
import su.reya.coop.roomId
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.displayNameFlow
import su.reya.coop.shared.pictureFlow

@Composable
fun ChatScreen(id: Long) {
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

    val displayName by remember(room) { room!!.displayNameFlow(viewModel) }.collectAsState("Loading...")
    val picture by remember(room) { room!!.pictureFlow(viewModel) }.collectAsState(null)

    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var newOtherMessages by remember { mutableIntStateOf(0) }

    val listState = rememberLazyListState()
    val messages = remember { mutableStateListOf<UnsignedEvent>() }

    val groupedMessages = remember(messages.toList()) {
        messages.groupBy { it.createdAt().formatAsGroupHeader() }
    }

    LaunchedEffect(id) {
        // Start loading spinner
        loading = true

        // Get msg relays for each member
        viewModel.chatRoomConnect(id)

        // Get messages
        val initialMessages = viewModel.getChatRoomMessages(id)
        messages.clear()
        messages.addAll(initialMessages)
        
        // Stop loading spinner
        loading = false

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
                            room!!.members.firstOrNull()?.let { pubkey ->
                                navigator.navigate(Screen.Profile(pubkey.toBech32()))
                            }
                        }
                    ) {
                        if (loading) {
                            LoadingIndicator(
                                modifier = Modifier.size(32.dp),
                            )
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
                    if (messages.isNotEmpty()) {
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
                                    messagesInGroup,
                                    key = { it.id()?.toBech32()!! }) { event ->
                                    ChatMessage(event)
                                }
                                item {
                                    DateSeparator(dateHeader)
                                }
                            }
                        }
                    } else {
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
    )
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
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .height(IntrinsicSize.Min),
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
                modifier = Modifier
                    .fillMaxHeight()
                    .aspectRatio(1f),
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
