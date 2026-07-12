package su.reya.coop.screens.chat

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalChatViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.RoomUiState
import su.reya.coop.Screen
import su.reya.coop.formatAsGroupHeader
import su.reya.coop.uiStateFlow
import su.reya.coop.roomId
import su.reya.coop.shared.Avatar

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatScreen(
    id: Long,
    screening: Boolean = false,
    coroutineDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val nostrViewModel = LocalNostrViewModel.current
    val chatViewModel = LocalChatViewModel.current
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()

    // Get current user
    val accountViewModel = LocalAccountViewModel.current
    val currentUser by accountViewModel.currentUserProfile.collectAsStateWithLifecycle()

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

    val roomState by (room as Room).uiStateFlow(nostrViewModel, currentUser?.publicKey)
        .collectAsStateWithLifecycle(RoomUiState())
    var text by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var newOtherMessages by remember { mutableIntStateOf(0) }
    var requireScreening by remember { mutableStateOf(screening) }

    val messages = remember { mutableStateListOf<UnsignedEvent>() }
    val groupedMessages =
        remember { derivedStateOf { messages.groupBy { it.createdAt().formatAsGroupHeader() } } }

    val sendFile = { uri: Uri ->
        scope.launch {
            // Read file on IO dispatcher
            val file = withContext(coroutineDispatcher) {
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }

            // Parse the file content type
            val type = context.contentResolver.getType(uri)

            // Send message (handles errors internally via ViewModel)
            chatViewModel.sendFileMessage(id, file, type)
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
        chatViewModel.loadChatRoomMessages(id) { initialMessages ->
            messages.clear()
            messages.addAll(initialMessages)

            // Stop loading spinner
            loading = false
        }

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

                    val mineColor = MaterialTheme.colorScheme.onPrimaryContainer
                    val otherColor = MaterialTheme.colorScheme.onSurface

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
                                groupedMessages.value.forEach { (dateHeader, messagesInGroup) ->
                                    items(
                                        items = messagesInGroup,
                                        key = { it.id()?.toHex() ?: it.hashCode().toString() }
                                    ) { event ->
                                        val isMine = currentUser?.publicKey == event.author()
                                        val uiModel = rememberMessageUiModel(
                                            event = event,
                                            currentUserPublicKey = currentUser?.publicKey,
                                            contentColor = if (isMine) mineColor else otherColor
                                        )
                                        ChatMessage(model = uiModel)
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
