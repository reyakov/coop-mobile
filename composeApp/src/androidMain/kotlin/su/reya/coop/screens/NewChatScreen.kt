package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_close_small
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavController
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun NewChatScreen(
    onBack: () -> Unit,
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val navController = LocalNavController.current
    val viewModel = LocalNostrViewModel.current

    val selectedReceivers = remember { mutableStateListOf<PublicKey>() }
    var query by remember { mutableStateOf("") }

    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val qrResult by savedStateHandle?.getStateFlow<String?>("qr_result", null)?.collectAsState()
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(query) {
        if (query.length >= 3) {
            delay(500) // 500ms debounce
            // TODO: Implement search
        }
    }

    LaunchedEffect(qrResult) {
        qrResult?.let {
            println("QR result: $it")
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("qr_result")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("New Chat") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { navController.navigate(Screen.Scan) }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_scanner),
                            contentDescription = "Scanner"
                        )
                    }
                }
            )
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
                    shape = RoundedCornerShape(28.dp),
                    color = MaterialTheme.colorScheme.surface,
                ) {
                    FlowRow(
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "To:",
                            modifier = Modifier.align(Alignment.Top),
                            style = MaterialTheme.typography.labelMediumEmphasized,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        selectedReceivers.forEach { receiver ->
                            ReceiverChip(
                                pubkey = receiver,
                                onRemove = { selectedReceivers.remove(receiver) }
                            )
                        }
                        BasicTextField(
                            value = query,
                            onValueChange = { query = it },
                            modifier = Modifier
                                .widthIn(min = 50.dp)
                                .align(Alignment.CenterVertically),
                            textStyle = MaterialTheme.typography.bodyMedium.copy(
                                color = MaterialTheme.colorScheme.onSurface
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (query.isEmpty() && selectedReceivers.isEmpty()) {
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
                Spacer(modifier = Modifier.size(16.dp))
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                ) {
                    // TODO: add result list
                    ContactList(
                        selectedReceivers = selectedReceivers,
                        onContactClick = { pubkey ->
                            val roomId = viewModel.createChatRoom(listOf(pubkey))
                            navController.navigate(Screen.Chat(roomId))
                        }
                    )
                    Spacer(modifier = Modifier.size(16.dp))
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
    val viewModel = LocalNostrViewModel.current
    val metadataFlow = remember(pubkey) { viewModel.getMetadata(pubkey) }
    val metadata by metadataFlow.collectAsState(initial = null)

    val profile = metadata?.asRecord()
    val displayName = profile?.name ?: profile?.displayName ?: pubkey.short()
    val picture = profile?.picture

    InputChip(
        selected = true,
        onClick = onRemove,
        label = { Text(displayName) },
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
        }
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactList(
    selectedReceivers: SnapshotStateList<PublicKey>,
    onContactClick: (PublicKey) -> Unit
) {
    val viewModel = LocalNostrViewModel.current
    val contactList by viewModel.contactList.collectAsState(initial = emptySet())

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
    ) {
        Text(
            text = "Contacts",
            style = MaterialTheme.typography.titleLargeEmphasized,
        )
        Spacer(modifier = Modifier.size(8.dp))
        contactList.forEachIndexed { index, item ->
            ContactListItem(
                pubkey = item,
                index = index,
                total = contactList.size,
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
    val viewModel = LocalNostrViewModel.current
    val metadataFlow = remember(pubkey) { viewModel.getMetadata(pubkey) }
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
