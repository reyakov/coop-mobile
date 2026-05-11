package su.reya.coop.screens

import android.content.ClipData
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_avatar
import coop.composeapp.generated.resources.ic_search
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Room
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenChat: (Long) -> Unit) {
    val clipboard = LocalClipboard.current
    val snackbarHostState = LocalSnackbarHostState.current
    val viewModel = LocalNostrViewModel.current
    val scope = rememberCoroutineScope()

    val currentUser = viewModel.currentUser() ?: return
    val currentUserProfile = viewModel.getMetadata(currentUser) ?: return

    val userProfile by currentUserProfile.collectAsState(initial = null)
    val chatRooms by viewModel.chatRooms.collectAsState(initial = emptyList())

    val sheetState = rememberModalBottomSheetState()
    var showBottomSheet by remember { mutableStateOf(false) }

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
                    // Search
                    IconButton(onClick = { /* TODO: Open search */ }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_search),
                            contentDescription = "Search"
                        )
                    }
                    // User
                    IconButton(onClick = { showBottomSheet = true }) {
                        if (userProfile?.asRecord()?.picture != null) {
                            AsyncImage(
                                model = userProfile?.asRecord()?.picture,
                                contentDescription = "User Avatar",
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
                }
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
                                    if (userProfile?.asRecord()?.picture != null) {
                                        AsyncImage(
                                            model = userProfile?.asRecord()?.picture,
                                            contentDescription = "User Avatar",
                                            modifier = Modifier.fillMaxSize(),
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

    val memberMetadataList = room.members.map { pubkey ->
        viewModel.getMetadata(pubkey).collectAsState()
    }

    val displayName = if (!room.subject.isNullOrBlank()) {
        room.subject
    } else if (room.isGroup()) {
        val profiles = memberMetadataList.map { it.value?.asRecord() }
        val names = profiles.take(2).mapNotNull { it?.name ?: it?.displayName }

        var combined = names.joinToString(", ")
        if (profiles.size > 2) {
            combined += ", +${profiles.size - 2}"
        }
        combined.ifBlank { "Unknown group" }
    } else {
        val firstMember = room.members.firstOrNull()
        val profile = memberMetadataList.firstOrNull()?.value?.asRecord()
        profile?.name ?: profile?.displayName ?: firstMember?.short() ?: "Unknown"
    }

    val firstMemberMetadata by if (room.members.isNotEmpty()) {
        viewModel.getMetadata(room.members.first()).collectAsState()
    } else {
        remember { mutableStateOf(null) }
    }
    val picture = firstMemberMetadata?.asRecord()?.picture

    ListItem(
        modifier = Modifier.clickable { onClick },
        leadingContent = {
            if (!picture.isNullOrBlank()) {
                AsyncImage(
                    model = picture,
                    contentDescription = "Room Avatar",
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Icon(
                    painter = painterResource(Res.drawable.ic_avatar),
                    contentDescription = "User"
                )
            }
        },
        headlineContent = {
            Text(
                text = displayName ?: "Unknown",
                style = MaterialTheme.typography.titleMediumEmphasized
            )
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
}
