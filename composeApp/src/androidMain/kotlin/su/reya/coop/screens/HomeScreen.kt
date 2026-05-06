package su.reya.coop.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_avatar
import coop.composeapp.generated.resources.ic_search
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.Room

@OptIn(ExperimentalMaterial3ExpressiveApi::class, ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenChat: (Long) -> Unit) {
    val viewModel = LocalNostrViewModel.current
    val userProfile by viewModel.getUserProfile().collectAsState(initial = null)
    val chatRooms by viewModel.chatRooms.collectAsState(initial = emptyList())

    Scaffold(
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
                    IconButton(onClick = { /* TODO: Open profile */ }) {
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
            }
        },
    )
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ChatRoom(room: Room, onClick: () -> Unit) {
    val title = room.subject ?: "Room"

    ListItem(
        modifier = Modifier.clickable { onClick },
        headlineContent = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMediumEmphasized
            )
        },
        colors = ListItemDefaults.colors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    )
}

