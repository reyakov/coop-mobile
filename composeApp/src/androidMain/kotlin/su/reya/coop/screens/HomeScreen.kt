package su.reya.coop.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTooltipState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_new_chat
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavController
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.Screen
import su.reya.coop.ago
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.displayNameFlow
import su.reya.coop.shared.pictureFlow
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenChat: (Long) -> Unit,
    onNewChat: () -> Unit,
) {
    val clipboard = LocalClipboard.current
    val navController = LocalNavController.current
    val snackbarHostState = LocalSnackbarHostState.current
    val viewModel = LocalNostrViewModel.current

    val currentUser = viewModel.currentUser() ?: return
    val currentUserProfile = viewModel.getMetadata(currentUser) ?: return

    val userProfile by currentUserProfile.collectAsState(initial = null)
    val chatRooms by viewModel.chatRooms.collectAsState(initial = emptyList())

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState()
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()
    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var showBottomSheet by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }

    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val qrResult by savedStateHandle
        ?.getStateFlow<String?>("qr_result", null)
        ?.collectAsState()
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(Unit) {
        viewModel.getChatRooms()
    }

    LaunchedEffect(qrResult) {
        qrResult?.let { result ->
            runCatching { PublicKey.parse(result) }
                .onSuccess { pubkey ->
                    val roomId = viewModel.createChatRoom(listOf(pubkey))
                    navController.navigate(Screen.Chat(roomId))
                }
                .onFailure { e -> println("Failed to parse QR: ${e.message}") }

            // Clear the nav state
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("qr_result")
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Text(
                        text = "Coop",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
                actions = {
                    // QR Scanner
                    IconButton(onClick = { navController.navigate(Screen.Scan) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_scanner),
                            contentDescription = "Scanner"
                        )
                    }
                    // User
                    IconButton(onClick = { showBottomSheet = true }) {
                        Avatar(
                            picture = userProfile?.asRecord()?.picture,
                            description = userProfile?.asRecord()?.displayName,
                            size = 32.dp,
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above,
                    spacingBetweenTooltipAndAnchor = 8.dp,
                ),
                tooltip = {
                    if (!expandedFab) {
                        PlainTooltip { Text("New Chat") }
                    }
                },
                state = rememberTooltipState(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = onNewChat,
                    expanded = expandedFab,
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.ic_new_chat),
                            contentDescription = "New Chat"
                        )
                    },
                    text = { Text("New Chat") },
                )
            }
        },
        content = { innerPadding ->
            Surface(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding()),
                color = MaterialTheme.colorScheme.surface,
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            ) {
                PullToRefreshBox(
                    modifier = Modifier.fillMaxSize(),
                    isRefreshing = isRefreshing,
                    state = pullToRefreshState,
                    onRefresh = {
                        scope.launch {
                            isRefreshing = true
                            viewModel.refreshChatRooms()
                            isRefreshing = false
                        }
                    },
                    indicator = {
                        PullToRefreshDefaults.LoadingIndicator(
                            state = pullToRefreshState,
                            isRefreshing = isRefreshing,
                            modifier = Modifier.align(Alignment.TopCenter),
                        )
                    }
                ) {
                    if (chatRooms.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "No chats yet",
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Your conversations will appear here.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.outline
                                )
                            }
                        }
                    } else {
                        LazyColumn(
                            state = listState,
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(chatRooms.toList(), key = { it.id }) { room ->
                                ChatRoom(
                                    room = room,
                                    onClick = { onOpenChat(room.id) }
                                )
                            }
                        }
                    }
                }

                if (showBottomSheet) {
                    ModalBottomSheet(
                        onDismissRequest = { showBottomSheet = false },
                        sheetState = sheetState,
                    ) {
                        val pubkey = viewModel.currentUser()
                        val shortPubkey = pubkey?.short() ?: "Not available"
                        val userName =
                            userProfile?.asRecord()?.displayName
                                ?: userProfile?.asRecord()?.name
                                ?: "No name"

                        Column(
                            modifier = Modifier
                                .padding(16.dp)
                                .fillMaxWidth(),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(84.dp)
                                        .clip(MaterialShapes.Cookie9Sided.toShape()),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Avatar(
                                        picture = userProfile?.asRecord()?.picture,
                                        description = userProfile?.asRecord()?.displayName,
                                        shape = MaterialShapes.Cookie9Sided.toShape(),
                                        modifier = Modifier.fillMaxSize()
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Box(
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = userName,
                                        style = MaterialTheme.typography.titleLargeEmphasized,
                                    )
                                }
                                Spacer(modifier = Modifier.size(8.dp))
                                Box(
                                    contentAlignment = Alignment.Center
                                ) {
                                    OutlinedButton(
                                        onClick = {
                                            scope.launch {
                                                if (pubkey != null) {
                                                    val text = pubkey.toBech32();
                                                    val entry = ClipData.newPlainText("text", text)
                                                    clipboard.setClipEntry(entry.toClipEntry())
                                                }
                                            }
                                        },
                                    ) {
                                        Text(text = shortPubkey)
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.size(16.dp))
                            BottomMenuList()
                        }
                    }
                }
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatRoom(room: Room, onClick: () -> Unit) {
    val viewModel = LocalNostrViewModel.current
    val displayName by remember(room) { room.displayNameFlow(viewModel) }.collectAsState("Loading...")
    val picture by remember(room) { room.pictureFlow(viewModel) }.collectAsState(null)

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Avatar(picture = picture, description = displayName)
        },
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMediumEmphasized,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = room.createdAt.ago(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        },
        supportingContent = {
            if (!room.lastMessage.isNullOrBlank()) {
                Text(
                    text = room.lastMessage!!,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

val defaultMenuList = listOf(
    "Messaging Relays",
    "Spam Filter",
    "Contacts",
    "Settings",
    "About"
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomMenuList() {
    val viewModel = LocalNostrViewModel.current

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            defaultMenuList.forEachIndexed { index, item ->
                SegmentedListItem(
                    checked = false,
                    onCheckedChange = { },
                    shapes = ListItemDefaults.segmentedShapes(
                        index = index,
                        count = defaultMenuList.size
                    ),
                    content = { Text(text = item) },
                )
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
        FilledTonalButton(
            onClick = { viewModel.logout() },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(text = "Logout")
        }
    }
}
