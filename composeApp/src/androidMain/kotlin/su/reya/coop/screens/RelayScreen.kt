package su.reya.coop.screens

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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.RadioButton
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
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.Role
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
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.viewmodel.AccountViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun RelayScreen(viewModel: AccountViewModel) {
    val navigator = LocalNavigator.current
    val snackbarHostState = LocalSnackbarHostState.current

    val scope = rememberCoroutineScope()
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

    var openAddRelayDialog by remember { mutableStateOf(false) }
    var relayToDelete by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.loadCurrentUserRelayList()
        viewModel.loadCurrentUserMsgRelayList()
    }

    val loadedRelayList by viewModel.userRelayList.collectAsStateWithLifecycle()
    val loadedMsgRelayList by viewModel.userMsgRelayList.collectAsStateWithLifecycle()

    LaunchedEffect(loadedRelayList) {
        if (loadedRelayList.isNotEmpty()) {
            relayList.clear()
            relayList.putAll(loadedRelayList)
        }
    }

    LaunchedEffect(loadedMsgRelayList) {
        if (loadedMsgRelayList.isNotEmpty()) {
            msgRelayList.clear()
            msgRelayList.addAll(loadedMsgRelayList)
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
                        text = "Relay Management",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { navigator.goBack() }) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
            )
        },
        floatingActionButton = {
            TooltipBox(
                positionProvider = TooltipDefaults.rememberTooltipPositionProvider(
                    TooltipAnchorPosition.Above,
                    spacingBetweenTooltipAndAnchor = 8.dp,
                ),
                tooltip = {
                    PlainTooltip { Text("New Relay") }
                },
                state = rememberTooltipState(),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { openAddRelayDialog = true },
                    expanded = false,
                    icon = {
                        Icon(
                            painter = painterResource(Res.drawable.ic_plus),
                            contentDescription = "New Relay"
                        )
                    },
                    text = { Text("New Relay") },
                )
            }
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
                                    onClick = { /* No action */ },
                                    onLongClick = { relayToDelete = relayUrl.toString() },
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

    if (openAddRelayDialog) {
        AddRelayDialog(
            viewModel = viewModel,
            onDismissRequest = { openAddRelayDialog = false },
            onMsgRelayAdded = { newRelay ->
                msgRelayList.add(RelayUrl.parse(newRelay))
            },
            onRelayAdded = { newRelay, metadata ->
                relayList[RelayUrl.parse(newRelay)] = metadata
            }
        )
    }

    if (relayToDelete != null) {
        AlertDialog(
            onDismissRequest = { relayToDelete = null },
            title = { Text("Remove Relay") },
            text = { Text("Are you sure you want to remove $relayToDelete?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (msgRelayList.size == 1) {
                            scope.launch {
                                snackbarHostState.showSnackbar("You must have at least one relay")
                            }
                            relayToDelete = null
                            return@TextButton
                        }
                        viewModel.removeMsgRelay(relayToDelete!!)
                        msgRelayList.removeIf { it.toString() == relayToDelete }
                        relayToDelete = null
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = { relayToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun AddRelayDialog(
    viewModel: AccountViewModel,
    onDismissRequest: () -> Unit,
    onMsgRelayAdded: (newRelay: String) -> Unit,
    onRelayAdded: (newRelay: String, metadata: RelayMetadata?) -> Unit,
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val focusRequester = remember { FocusRequester() }

    var relayAddress by remember { mutableStateOf("") }
    var isError by remember { mutableStateOf(false) }
    val roles = listOf("Messaging", "Inbox", "Outbox")
    val (selected, onSelected) = remember { mutableStateOf(roles[0]) }

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
                            text = "New Relay",
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
                            if (!isError) {
                                when (selected) {
                                    "Messaging" -> {
                                        viewModel.addMsgRelay(relayAddress)
                                        onMsgRelayAdded(relayAddress)
                                    }

                                    "Inbox" -> {
                                        viewModel.addInboxRelay(relayAddress)
                                        onRelayAdded(relayAddress, RelayMetadata.WRITE)
                                    }

                                    "Outbox" -> {
                                        viewModel.addOutboxRelay(relayAddress)
                                        onRelayAdded(relayAddress, RelayMetadata.READ)
                                    }
                                }
                                onDismissRequest()
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
                        value = relayAddress,
                        onValueChange = { relayAddress = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .focusRequester(focusRequester),
                        isError = isError,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(
                            onDone = {
                                isError = relayAddress.isNotEmpty() && !verifyRelayUrl(relayAddress)
                            }
                        ),
                        singleLine = true,
                        label = { Text(text = "Relay Address") },
                        placeholder = { Text(text = "wss://relay.example.com") },
                        supportingText = {
                            if (isError) {
                                Text(text = "Invalid format. Must start with wss://")
                            } else {
                                Text(text = "Only add relays you trust.")
                            }
                        },
                    )
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "Relay Roles",
                            style = MaterialTheme.typography.titleMediumEmphasized
                        )
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .selectableGroup(),
                        ) {
                            roles.forEach { text ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .selectable(
                                            onClick = { onSelected(text) },
                                            selected = (text == selected),
                                            role = Role.RadioButton
                                        )
                                        .padding(horizontal = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = (text == selected),
                                        onClick = null
                                    )
                                    Spacer(modifier = Modifier.size(16.dp))
                                    Text(
                                        text = text,
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        )
    }
}

fun verifyRelayUrl(url: String): Boolean {
    return try {
        RelayUrl.parse(url)
        true
    } catch (e: Exception) {
        println("Failed to parse relay url: ${e.message}")
        false
    }
}
