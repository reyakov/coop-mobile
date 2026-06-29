package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_arrow_next
import coop.composeapp.generated.resources.ic_close_small
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalChatViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalProfileViewModel
import su.reya.coop.LocalScanResult
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.short
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewChatScreen() {
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val qrScanResult = LocalScanResult.current
    val chatViewModel = LocalChatViewModel.current
    val profileViewModel = LocalProfileViewModel.current

    val contactList by profileViewModel.contactList.collectAsStateWithLifecycle()
    var query by remember { mutableStateOf("") }

    val createGroup = remember { mutableStateOf(false) }
    val searchResults = remember { mutableStateListOf<PublicKey>() }
    val selectedReceivers = remember { mutableStateListOf<PublicKey>() }

    LaunchedEffect(query) {
        if (query.length >= 3) {
            delay(500.milliseconds)

            if (query.startsWith("npub1")) {
                val pubkey = try {
                    PublicKey.parse(query)
                } catch (e: Exception) {
                    println("Failed to parse npub: ${e.message}")
                    null
                }
                if (pubkey != null) {
                    selectedReceivers.add(pubkey)
                }
            } else if (query.contains("@")) {
                val pubkey = profileViewModel.searchByAddress(query)
                if (pubkey != null) {
                    selectedReceivers.add(pubkey)
                }
            } else {
                val results = profileViewModel.searchByNostr(query)
                searchResults.clear()
                searchResults.addAll(results)
            }

            query = ""
        }
    }

    LaunchedEffect(qrScanResult.content) {
        qrScanResult.content?.let { result ->
            // Verify the content
            runCatching {
                PublicKey.parse(result)
            }.onSuccess { pubkey ->
                selectedReceivers.add(pubkey)
            }.onFailure { e ->
                snackbarHostState.showSnackbar("Invalid address: ${e.message}")
            }
            // Clear the nav state
            qrScanResult.clear()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (createGroup.value) "New group chat" else "New chat",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    IconButton(onClick = { navigator.goBack() }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navigator.navigate(Screen.Scan) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_scanner),
                            contentDescription = "Scanner"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (selectedReceivers.isNotEmpty()) {
                TooltipBox(
                    positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                        TooltipAnchorPosition.Above,
                        spacingBetweenTooltipAndAnchor = 8.dp,
                    ),
                    tooltip = {
                        PlainTooltip { Text("Next") }
                    },
                    state = rememberTooltipState(),
                ) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            val roomId = chatViewModel.createChatRoom(selectedReceivers.toList())
                            navigator.navigate(Screen.Chat(roomId))
                        },
                        expanded = false,
                        icon = {
                            Icon(
                                painter = painterResource(Res.drawable.ic_arrow_next),
                                contentDescription = "Next"
                            )
                        },
                        text = { Text("Next") },
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        contentColor = MaterialTheme.colorScheme.onTertiary,
                    )
                }
            }
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.Top,
                    ) {
                        Text(
                            text = "To:",
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.size(16.dp))
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            selectedReceivers.forEach { receiver ->
                                ReceiverChip(
                                    pubkey = receiver,
                                    onRemove = { selectedReceivers.remove(receiver) }
                                )
                            }
                            BasicTextField(
                                value = query,
                                onValueChange = { query = it },
                                modifier = Modifier.fillMaxWidth(),
                                textStyle = MaterialTheme.typography.bodyMedium.copy(
                                    color = MaterialTheme.colorScheme.onSurface
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                                decorationBox = { innerTextField ->
                                    Box(contentAlignment = Alignment.CenterStart) {
                                        if (query.isEmpty()) {
                                            Text(
                                                "Type a npub or address",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                    alpha = 0.5f
                                                )
                                            )
                                        }
                                        innerTextField()
                                    }
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.size(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    if (searchResults.isNotEmpty()) {
                        ContactList(
                            items = searchResults,
                            selectedReceivers = selectedReceivers,
                            onContactClick = { pubkey ->
                                val roomId = chatViewModel.createChatRoom(listOf(pubkey))
                                navigator.navigate(Screen.Chat(roomId))
                            },
                        )
                        Spacer(modifier = Modifier.size(16.dp))
                    } else {
                        ContactList(
                            title = "Contacts",
                            items = contactList.toList(),
                            selectedReceivers = selectedReceivers,
                            onContactClick = { pubkey ->
                                val roomId = chatViewModel.createChatRoom(listOf(pubkey))
                                navigator.navigate(Screen.Chat(roomId))
                            }
                        )
                        Spacer(modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    )
}

@Composable
fun ReceiverChip(
    pubkey: PublicKey,
    onRemove: () -> Unit
) {
    val profileViewModel = LocalProfileViewModel.current
    val metadataFlow = remember(pubkey) { profileViewModel.getMetadata(pubkey) }
    val metadata by metadataFlow.collectAsState(initial = null)

    val profile = metadata?.asRecord()
    val displayName = profile?.name ?: profile?.displayName ?: pubkey.short()
    val picture = profile?.picture

    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides 0.dp) {
        InputChip(
            selected = true,
            onClick = onRemove,
            label = {
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )
            },
            avatar = {
                Avatar(
                    picture = picture,
                    description = displayName,
                    size = 24.dp
                )
            },
            trailingIcon = {
                Icon(
                    painter = painterResource(Res.drawable.ic_close_small),
                    contentDescription = "Close"
                )
            },
            shape = RoundedCornerShape(24.dp),
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactList(
    title: String? = null,
    items: List<PublicKey>,
    selectedReceivers: SnapshotStateList<PublicKey>,
    onContactClick: (PublicKey) -> Unit
) {
    if (items.isEmpty()) return

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
            Spacer(modifier = Modifier.size(8.dp))
        }
        items.forEachIndexed { index, item ->
            ContactListItem(
                pubkey = item,
                index = index,
                total = items.size,
                isSelected = selectedReceivers.contains(item),
                onClick = { onContactClick(item) },
                onLongClick = {
                    if (!selectedReceivers.contains(item)) {
                        selectedReceivers.add(item)
                    }
                }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactListItem(
    pubkey: PublicKey,
    index: Int,
    total: Int = 0,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val profileViewModel = LocalProfileViewModel.current
    val metadataFlow = remember(pubkey) { profileViewModel.getMetadata(pubkey) }
    val metadata by metadataFlow.collectAsState(initial = null)

    val profile = metadata?.asRecord()
    val displayName = profile?.name ?: profile?.displayName ?: pubkey.short()
    val picture = profile?.picture

    SegmentedListItem(
        selected = isSelected,
        onClick = onClick,
        onLongClick = onLongClick,
        shapes = ListItemDefaults.segmentedShapes(
            index = index,
            count = total
        ),
        leadingContent = {
            Avatar(
                picture = picture,
                description = displayName,
                size = 36.dp
            )
        },
        supportingContent = { Text(text = pubkey.short()) },
        content = {
            Text(
                text = displayName,
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
        }
    )
}
