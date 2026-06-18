package su.reya.coop.screens

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_chat
import coop.composeapp.generated.resources.ic_share
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProfileScreen(pubkey: String, screening: Boolean = false) {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val viewModel = LocalNostrViewModel.current
    val scope = rememberCoroutineScope()

    val pubkey = runCatching { PublicKey.parse(pubkey) }.getOrNull() ?: return

    val metadataFlow = remember(pubkey) { viewModel.getMetadata(pubkey) }
    val metadata by metadataFlow.collectAsState(initial = null)
    val profile = metadata?.asRecord()

    val displayName = profile?.displayName ?: profile?.name ?: "No name"
    val nip05 = profile?.nip05 ?: pubkey.short()
    val picture = profile?.picture

    val details = remember(profile) {
        listOf(
            "Username:" to (profile?.name ?: "None"),
            "Website:" to (profile?.website ?: "None"),
            "₿ Lightning Address:" to (profile?.lud16 ?: "None"),
        )
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    if (screening) {
                        Text(
                            text = "Screening",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.goBack() }) {
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1f),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(MaterialShapes.Cookie9Sided.toShape()),
                        contentAlignment = Alignment.Center
                    ) {
                        Avatar(
                            picture = picture,
                            description = "Profile picture",
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialShapes.Cookie9Sided.toShape(),
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        Text(
                            text = displayName,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                fontFamily = getExpressiveFontFamily()
                            ),
                        )
                        Text(
                            text = nip05,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.titleSmall
                        )
                    }
                    Spacer(modifier = Modifier.size(24.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    scope.launch {
                                        try {
                                            val roomId = viewModel.createChatRoom(listOf(pubkey))
                                            navigator.navigate(Screen.Chat(roomId))
                                        } catch (e: Exception) {
                                            e.message?.let { snackbarHostState.showSnackbar(it) }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.MediumContainerHeight),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_chat),
                                    contentDescription = "New Chat"
                                )
                            }
                            Text(
                                text = "Message",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            FilledTonalIconButton(
                                onClick = {
                                    val sendIntent = Intent().apply {
                                        action = Intent.ACTION_SEND
                                        putExtra(Intent.EXTRA_TEXT, pubkey.toBech32())
                                        type = "text/plain"
                                    }
                                    val shareIntent = Intent.createChooser(sendIntent, null)
                                    context.startActivity(shareIntent)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.MediumContainerHeight),
                            ) {
                                Icon(
                                    painter = painterResource(Res.drawable.ic_share),
                                    contentDescription = "Share"
                                )
                            }
                            Text(
                                text = "Share",
                                style = MaterialTheme.typography.labelMedium
                            )
                        }
                    }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .weight(1.5f),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                    ) {
                        details.forEachIndexed { index, (label, value) ->
                            SegmentedListItem(
                                onClick = { },
                                shapes = ListItemDefaults.segmentedShapes(
                                    index = index,
                                    count = details.size
                                ),
                                content = { Text(label) },
                                supportingContent = { Text(value) },
                            )
                        }
                    }

                    if (screening) {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
                        ) {
                            // TODO
                        }
                    }
                }
            }
        }
    )
}