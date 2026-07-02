package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_check
import coop.composeapp.generated.resources.ic_close
import coop.composeapp.generated.resources.ic_plus
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.Nip05Address
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactListScreen() {
    val navigator = LocalNavigator.current
    val snackbarHostState = LocalSnackbarHostState.current
    val nostrViewModel = LocalNostrViewModel.current

    val contactList by nostrViewModel.contactList.collectAsStateWithLifecycle()
    var openAddContactDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "My Contacts",
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
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above,
                    spacingBetweenTooltipAndAnchor = 8.dp,
                ),
                tooltip = {
                    PlainTooltip { Text("New Contact") }
                },
                state = rememberTooltipState(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { openAddContactDialog = true },
                    expanded = false,
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.ic_plus),
                            contentDescription = "New Contact"
                        )
                    },
                    text = { Text("New Contact") },
                )
            }
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .padding(innerPadding),
                verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap),
            ) {
                if (contactList.isNotEmpty()) {
                    contactList.forEachIndexed { index, pubkey ->
                        ContactListItem(
                            pubkey = pubkey,
                            index = index,
                            total = contactList.size,
                            onClick = {})
                    }
                } else {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            Text(
                                text = "No contacts yet",
                                style = MaterialTheme.typography.titleLargeEmphasized.copy(
                                    fontWeight = FontWeight.SemiBold
                                ),
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "Your contacts will appear here",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.outline
                            )
                        }
                    }
                }
            }
        }
    )

    if (openAddContactDialog) {
        AddContactDialog(onDismissRequest = { openAddContactDialog = false })
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddContactDialog(onDismissRequest: () -> Unit) {
    val snackbarHostState = LocalSnackbarHostState.current
    val nostrViewModel = LocalNostrViewModel.current
    val focusRequester = remember { FocusRequester() }
    var contact by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = { onDismissRequest() },
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            decorFitsSystemWindows = false,
        ),
    ) {
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            snackbarHost = { SnackbarHost(snackbarHostState) },
            containerColor = MaterialTheme.colorScheme.surface,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                    title = {
                        Text(
                            text = "New Contact",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = { onDismissRequest() }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_close),
                                contentDescription = "Close"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            scope.launch {
                                val success = nostrViewModel.addContact(contact)
                                if (success) onDismissRequest()
                            }
                        }) {
                            Icon(
                                painter = painterResource(Res.drawable.ic_check),
                                contentDescription = "Add"
                            )
                        }
                    },
                )
            },
            content = { innerPadding ->
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(innerPadding)
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                ) {
                    OutlinedTextField(
                        value = contact,
                        onValueChange = { contact = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        isError = isError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                isError = contact.isNotEmpty() && !verifyContact(contact)
                            }
                        ),
                        singleLine = true,
                        label = { Text(text = "Contact Address") },
                        placeholder = { Text(text = "npub1... or user@example.com") },
                        supportingText = {
                            if (isError) {
                                Text(text = "Contact address is invalid")
                            } else {
                                Text(text = "Only add contact you trust.")
                            }
                        },
                    )
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ContactListItem(
    pubkey: PublicKey,
    index: Int,
    total: Int = 0,
    onClick: () -> Unit,
) {
    val nostrViewModel = LocalNostrViewModel.current
    val profileFlow = remember(pubkey) { nostrViewModel.getMetadata(pubkey) }
    val profile by profileFlow.collectAsState(initial = null)

    SegmentedListItem(
        onClick = onClick,
        onLongClick = { nostrViewModel.removeContact(pubkey) },
        shapes = ListItemDefaults.segmentedShapes(
            index = index,
            count = total
        ),
        leadingContent = {
            Avatar(
                picture = profile?.picture,
                description = profile?.name,
                size = 36.dp
            )
        },
        supportingContent = { Text(text = pubkey.short()) },
        content = {
            Text(
                text = profile?.name ?: "No name",
                style = MaterialTheme.typography.titleMediumEmphasized,
            )
        }
    )
}

fun verifyContact(address: String): Boolean {
    return try {
        if (address.contains("@")) Nip05Address.parse(address)
        else PublicKey.parse(address)
        true
    } catch (e: Exception) {
        println("Failed to parse contact: ${e.message}")
        false
    }
}
