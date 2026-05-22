package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RelayScreen(
    onBack: () -> Unit
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val viewModel = LocalNostrViewModel.current

    val msgRelayList = remember { mutableStateListOf<RelayUrl>() }
    val relayList = remember { mutableStateMapOf<RelayUrl, RelayMetadata?>() }

    val inboxRelays by remember {
        derivedStateOf {
            relayList.filter { it.value == RelayMetadata.READ || it.value == null }.keys.toList()
        }
    }

    val outboxRelays by remember {
        derivedStateOf {
            relayList.filter { it.value == RelayMetadata.WRITE || it.value == null }.keys.toList()
        }
    }

    LaunchedEffect(Unit) {
        relayList.putAll(viewModel.currentUserRelayList())
        msgRelayList.addAll(viewModel.currentUserMsgRelayList())
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
                        text = "Relay Management",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
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
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Messaging Relay List",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        if (msgRelayList.isNotEmpty()) {
                            msgRelayList.forEachIndexed { index, relayUrl ->
                                SegmentedListItem(
                                    onClick = { },
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = msgRelayList.size
                                    ),
                                    content = { Text(text = relayUrl.toString()) },
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No relays configured",
                                        style = MaterialTheme.typography.labelMediumEmphasized,
                                    )
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Inbox Relays",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        if (inboxRelays.isNotEmpty()) {
                            inboxRelays.forEachIndexed { index, relayUrl ->
                                SegmentedListItem(
                                    onClick = { },
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = inboxRelays.size
                                    ),
                                    content = { Text(text = relayUrl.toString()) },
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No relays configured",
                                        style = MaterialTheme.typography.labelMediumEmphasized,
                                    )
                                }
                            }
                        }
                    }
                }
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = "Outbox Relays",
                        style = MaterialTheme.typography.titleMediumEmphasized,
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        if (outboxRelays.isNotEmpty()) {
                            outboxRelays.forEachIndexed { index, relayUrl ->
                                SegmentedListItem(
                                    onClick = { },
                                    shapes = ListItemDefaults.segmentedShapes(
                                        index = index,
                                        count = outboxRelays.size
                                    ),
                                    content = { Text(text = relayUrl.toString()) },
                                )
                            }
                        } else {
                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "No relays configured",
                                        style = MaterialTheme.typography.labelMediumEmphasized,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    )
}