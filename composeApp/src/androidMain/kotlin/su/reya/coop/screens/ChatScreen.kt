package su.reya.coop.screens

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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_avatar
import coop.composeapp.generated.resources.ic_send
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.Event
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.humanReadable
import su.reya.coop.roomId
import su.reya.coop.shared.displayNameFlow
import su.reya.coop.shared.pictureFlow

@Composable
fun ChatScreen(
    id: Long,
    onBack: () -> Unit,
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val viewModel = LocalNostrViewModel.current

    val room = viewModel.getChatRoom(id)
    val displayName by remember(room) { room.displayNameFlow(viewModel) }.collectAsState("Loading...")
    val picture by remember(room) { room.pictureFlow(viewModel) }.collectAsState(null)

    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    val messages = remember { mutableStateListOf<Event>() }

    fun setLoading(value: Boolean) {
        loading = value
    }

    LaunchedEffect(id) {
        // Start loading spinner
        setLoading(true)

        // Get messages
        val initialMessages = viewModel.getChatRoomMessages(id)
        messages.clear()
        messages.addAll(initialMessages)

        // Get msg relays for each member
        viewModel.chatRoomConnect(id)

        // Stop loading spinner
        setLoading(false)

        // Handle new messages
        viewModel.newEvents.collect { event ->
            if (event.roomId() == id) {
                messages.add(0, event)
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box {
                            if (!picture.isNullOrBlank()) {
                                AsyncImage(
                                    model = picture,
                                    contentDescription = "Room Avatar",
                                    modifier = Modifier
                                        .size(32.dp)
                                        .clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_avatar),
                                    contentDescription = "User"
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(8.dp))
                        Text(
                            text = displayName,
                            style = MaterialTheme.typography.titleMediumEmphasized,
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
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
                if (loading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(bottom = innerPadding.calculateBottomPadding())
                    ) {
                        LazyColumn(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentPadding = PaddingValues(16.dp),
                            reverseLayout = true
                        ) {
                            items(messages.toList(), key = { it.id().toBech32() }) { event ->
                                ChatMessage(event)
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
        }
    )
}

@Composable
fun ChatMessage(
    event: Event
) {
    val viewModel = LocalNostrViewModel.current
    val currentUser = viewModel.currentUser()
    val isMine = event.author() == currentUser

    val bubbleShape = if (isMine) {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 20.dp, bottomEnd = 4.dp)
    } else {
        RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 4.dp, bottomEnd = 20.dp)
    }

    val containerColor =
        if (isMine) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer

    val contentColor =
        if (isMine) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSecondaryContainer

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        contentAlignment = if (isMine) Alignment.CenterEnd else Alignment.CenterStart
    ) {
        Column(
            horizontalAlignment = if (isMine) Alignment.End else Alignment.Start
        ) {
            Text(
                text = event.createdAt().humanReadable(),
                style = MaterialTheme.typography.labelSmall,
                textAlign = if (isMine) TextAlign.End else TextAlign.Start,
            )
            Spacer(modifier = Modifier.size(4.dp))
            Surface(
                color = containerColor,
                contentColor = contentColor,
                shape = bubbleShape,
                modifier = Modifier.widthIn(max = 280.dp)
            ) {
                Text(
                    text = event.content(),
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
