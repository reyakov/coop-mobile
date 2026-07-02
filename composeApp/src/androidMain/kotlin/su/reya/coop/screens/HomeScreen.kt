package su.reya.coop.screens

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_close
import coop.composeapp.generated.resources.ic_new_chat
import coop.composeapp.generated.resources.ic_qr
import coop.composeapp.generated.resources.ic_request
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalAuthViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalScanResult
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.RoomKind
import su.reya.coop.Screen
import su.reya.coop.ago
import su.reya.coop.rememberUiState
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val navigator = LocalNavigator.current
    val qrScanResult = LocalScanResult.current
    val snackbarHostState = LocalSnackbarHostState.current
    val clipboardManager = LocalClipboard.current
    val nostrViewModel = LocalNostrViewModel.current
    val authViewModel = LocalAuthViewModel.current

    val scope = rememberCoroutineScope()
    val sheetState = rememberModalBottomSheetState(true)
    val listState = rememberLazyListState()
    val pullToRefreshState = rememberPullToRefreshState()

    val userProfile by nostrViewModel.currentUserProfile.collectAsStateWithLifecycle()
    val chatRooms by nostrViewModel.chatRooms.collectAsStateWithLifecycle()

    val isRelayListEmpty by nostrViewModel.isRelayListEmpty.collectAsStateWithLifecycle()
    val isSyncing by nostrViewModel.isSyncing.collectAsStateWithLifecycle()
    val isPartialProcessedGiftWrap by nostrViewModel.isPartialProcessedGiftWrap.collectAsStateWithLifecycle()
    
    val authState by authViewModel.state.collectAsStateWithLifecycle()
    val isBannerDismissed = authState.isNotificationBannerDismissed

    val expandedFab by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    var showBottomSheet by remember { mutableStateOf(false) }
    var isRefreshing by remember { mutableStateOf(false) }
    var isBusy by remember { mutableStateOf(false) }

    var isNotificationEnabled by remember {
        mutableStateOf(NotificationManagerCompat.from(context).areNotificationsEnabled())
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // State will be updated by LifecycleResumeEffect
    }

    // Partition chat rooms into requests and ongoing
    val (requests, ongoing) = remember(chatRooms) {
        chatRooms.partition { it.kind == RoomKind.Request }
    }

    LifecycleResumeEffect(context) {
        isNotificationEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
        onPauseOrDispose { }
    }

    LaunchedEffect(Unit) {
        nostrViewModel.getChatRooms()
    }

    LaunchedEffect(qrScanResult.content) {
        qrScanResult.content?.let { result ->
            runCatching { PublicKey.parse(result) }
                .onSuccess { pubkey ->
                    try {
                        val roomId = nostrViewModel.createChatRoom(listOf(pubkey))
                        navigator.navigate(Screen.Chat(roomId))
                    } catch (e: Exception) {
                        e.message?.let { snackbarHostState.showSnackbar(it) }
                    }
                }
                .onFailure { e -> println("Failed to parse QR: ${e.message}") }
            // Clear the nav state
            qrScanResult.clear()
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Coop",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                        if (isSyncing) {
                            LoadingIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                },
                actions = {
                    // QR Scanner
                    IconButton(onClick = { navigator.navigate(Screen.Scan) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_scanner),
                            contentDescription = "Scanner"
                        )
                    }
                    // User
                    IconButton(onClick = { showBottomSheet = true }) {
                        Avatar(
                            picture = userProfile?.picture,
                            description = userProfile?.name ?: "No name",
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
                    onClick = { navigator.navigate(Screen.NewChat) },
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
            Column(
                modifier = Modifier.padding(top = innerPadding.calculateTopPadding()),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (!isNotificationEnabled && !isBannerDismissed) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp),
                        shape = RoundedCornerShape(24.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = "Get message notifications",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                    color = MaterialTheme.colorScheme.onSecondaryFixed,
                                )
                                Text(
                                    text = "Make sure you know when you have new messages.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                                )
                            }
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                TextButton(
                                    onClick = { authViewModel.dismissNotificationBanner() },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(text = "Maybe later")
                                }
                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                        } else {
                                            // For older versions, navigate the user directly to App Notification Settings
                                            val intent =
                                                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                                    putExtra(
                                                        Settings.EXTRA_APP_PACKAGE,
                                                        context.packageName
                                                    )
                                                }
                                            context.startActivity(intent)
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(text = "Turn on")
                                }
                            }
                        }
                    }
                }
                Surface(
                    modifier = Modifier.fillMaxSize(),
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
                                nostrViewModel.refreshChatRooms()
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
                        if (!isPartialProcessedGiftWrap) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                LoadingIndicator()
                            }
                        } else if (chatRooms.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Text(
                                        text = "No chats yet",
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
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                if (requests.isNotEmpty()) {
                                    item { NewRequests(requests) }
                                }

                                items(ongoing, key = { it.id }) { room ->
                                    ChatRoom(
                                        room = room,
                                        onClick = { navigator.navigate(Screen.Chat(room.id)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        },
    )

    if (showBottomSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBottomSheet = false },
            sheetState = sheetState,
        ) {
            val dismissAndRun: (suspend () -> Unit) -> Unit = { action ->
                scope.launch {
                    sheetState.hide()
                    showBottomSheet = false
                    action()
                }
            }

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
                            picture = userProfile?.picture,
                            description = userProfile?.name ?: "No name",
                            shape = MaterialShapes.Cookie9Sided.toShape(),
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Box(
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = userProfile?.name ?: "No name",
                            style = MaterialTheme.typography.titleLargeEmphasized,
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        OutlinedButton(
                            onClick = {
                                scope.launch {
                                    userProfile?.publicKey?.let {
                                        val bech32 = it.toBech32()
                                        val data = ClipData.newPlainText(bech32, bech32)
                                        clipboardManager.setClipEntry(ClipEntry(data))
                                    }
                                }
                            },
                        ) {
                            Text(text = userProfile?.shortPublicKey ?: "Unknown")
                        }
                        FilledIconButton(
                            onClick = {
                                dismissAndRun { navigator.navigate(Screen.MyQr) }
                            },
                            shape = MaterialShapes.Square.toShape()
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_qr),
                                contentDescription = "My QR"
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                BottomMenuList(onDismiss = dismissAndRun)
            }
        }
    }

    // Show the relay setup dialog if the msg relay list is empty
    if (isRelayListEmpty) {
        ModalBottomSheet(
            onDismissRequest = { nostrViewModel.dismissRelayWarning() },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.5f)
                    .padding(horizontal = 24.dp)
                    .navigationBarsPadding(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = "Messaging Relays are missing",
                    style = MaterialTheme.typography.titleLargeEmphasized.copy(
                        fontWeight = FontWeight.SemiBold,
                        fontFamily = getExpressiveFontFamily()
                    ),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = MaterialShapes.Circle.toShape(),
                        color = MaterialTheme.colorScheme.error,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "X",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                    Text(
                        text = "Other people won't be able to send you messages.",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Surface(
                        modifier = Modifier.size(24.dp),
                        shape = MaterialShapes.Circle.toShape(),
                        color = MaterialTheme.colorScheme.error,
                    ) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "X",
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                    Text(
                        text = "You cannot store your messages.",
                        style = MaterialTheme.typography.titleSmallEmphasized,
                    )
                }
                Text(
                    text = "Please click the button below to continue with the default set of relays. You can always change them later in the settings.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                )
                Text(
                    text = "If you believe this is a mistake, please click the Retry button to check again.",
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontStyle = FontStyle.Italic,
                    ),
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isBusy) {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        TextButton(
                            enabled = !isBusy,
                            onClick = {
                                scope.launch {
                                    isBusy = true
                                    try {
                                        nostrViewModel.refetchMsgRelays()
                                    } catch (e: Exception) {
                                        snackbarHostState.showSnackbar("Failed to refresh metadata: ${e.message}")
                                    }
                                    isBusy = false
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(ButtonDefaults.MediumContainerHeight),
                        ) {
                            Text(
                                text = "Retry",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                        Button(
                            enabled = !isBusy,
                            onClick = {
                                scope.launch {
                                    nostrViewModel.useDefaultMsgRelayList()
                                    sheetState.hide()
                                }
                            },
                            modifier = Modifier
                                .weight(1f)
                                .height(ButtonDefaults.MediumContainerHeight),
                        ) {
                            Text(
                                text = "Use Default",
                                style = MaterialTheme.typography.titleMediumEmphasized,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun NewRequests(requests: List<Room>) {
    val navigator = LocalNavigator.current
    val nostrViewModel = LocalNostrViewModel.current
    val authViewModel = LocalAuthViewModel.current

    val total = requests.size
    val firstRoom = requests.getOrNull(0)
    val secondRoom = requests.getOrNull(1)

    val firstRoomState by (firstRoom as Room).rememberUiState(nostrViewModel)
    val secondRoomState by (secondRoom as Room).rememberUiState(nostrViewModel)

    val supportingText = when {
        total == 1 -> {
            val message = firstRoom.lastMessage ?: ""
            "${firstRoomState.name}: $message"
        }

        total == 2 -> {
            "${firstRoomState.name} and ${secondRoomState.name}"
        }

        total > 2 -> {
            val othersCount = total - 2
            val othersText = if (othersCount == 1) "1 other" else "$othersCount others"
            "${firstRoomState.name}, ${secondRoomState.name} and $othersText"
        }

        else -> ""
    }

    ListItem(
        modifier = Modifier.clickable {
            navigator.navigate(Screen.RequestList)
        },
        leadingContent = {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(MaterialShapes.Clover4Leaf.toShape()),
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    modifier = Modifier.size(48.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_request),
                            contentDescription = "Requests",
                            tint = MaterialTheme.colorScheme.onTertiaryFixed
                        )
                    }
                }
            }
        },
        headlineContent = {
            Text(
                text = "Requests",
                style = MaterialTheme.typography.titleMediumEmphasized
            )
        },
        supportingContent = {
            if (supportingText.isNotEmpty()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatRoom(room: Room, onClick: () -> Unit) {
    val nostrViewModel = LocalNostrViewModel.current
    val authViewModel = LocalAuthViewModel.current
    val roomState by room.rememberUiState(nostrViewModel)

    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Avatar(picture = roomState.picture, description = roomState.picture)
        },
        headlineContent = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = roomState.name,
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
                    text = room.lastMessage ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    overflow = TextOverflow.Ellipsis,
                    maxLines = 1,
                )
            }
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun BottomMenuList(
    onDismiss: (suspend () -> Unit) -> Unit
) {
    val navigator = LocalNavigator.current
    val nostrViewModel = LocalNostrViewModel.current
    val authViewModel = LocalAuthViewModel.current

    val defaultMenuList = listOf(
        "Update Profile" to { navigator.navigate(Screen.UpdateProfile) },
        "Contact List" to { navigator.navigate(Screen.ContactList) },
        "Relay Management" to { navigator.navigate(Screen.Relay) },
        "Settings" to { }
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
        ) {
            defaultMenuList.forEachIndexed { index, (title, action) ->
                SegmentedListItem(
                    onClick = { onDismiss { action() } },
                    shapes = ListItemDefaults.segmentedShapes(
                        index = index,
                        count = defaultMenuList.size
                    ),
                    content = { Text(text = title) },
                )
            }
        }
        Spacer(modifier = Modifier.size(16.dp))
        FilledTonalButton(
            onClick = { onDismiss { authViewModel.logout(onLogout = nostrViewModel::resetInternalState) } },
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError
            )
        ) {
            Text(text = "Logout")
        }
    }
}
