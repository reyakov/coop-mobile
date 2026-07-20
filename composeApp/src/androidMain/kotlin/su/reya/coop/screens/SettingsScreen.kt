package su.reya.coop.screens

import android.content.Intent
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedListItem
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.MediaConfig
import su.reya.coop.Theme
import su.reya.coop.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel) {
    val navigator = LocalNavigator.current
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val settings by viewModel.settings.collectAsState()

    var showThemeDialog by remember { mutableStateOf(false) }
    var showMediaDialog by remember { mutableStateOf(false) }
    var showBlossomDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Settings",
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
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "General",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    SegmentedListItem(
                        onClick = { viewModel.update { it.copy(screening = !it.screening) } },
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 4),
                        content = { Text("Screening") },
                        supportingContent = { Text("Filter unknown contacts") },
                        trailingContent = {
                            Switch(
                                checked = settings.screening,
                                onCheckedChange = { viewModel.update { s -> s.copy(screening = it) } }
                            )
                        }
                    )
                    SegmentedListItem(
                        onClick = { showMediaDialog = true },
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 4),
                        content = { Text("Media Preview") },
                        supportingContent = {
                            Text(
                                when (settings.media) {
                                    MediaConfig.Disabled -> "Disabled"
                                    MediaConfig.DisabledForMobileData -> "Disabled for Mobile Data"
                                    MediaConfig.AlwaysEnabled -> "Always Enabled"
                                }
                            )
                        }
                    )
                    SegmentedListItem(
                        onClick = { showBlossomDialog = true },
                        shapes = ListItemDefaults.segmentedShapes(index = 2, count = 4),
                        content = { Text("Blossom Server") },
                        supportingContent = { Text(settings.blossomServer ?: "Default") }
                    )
                    SegmentedListItem(
                        onClick = {
                            val intent = Intent(AndroidSettings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                putExtra(AndroidSettings.EXTRA_APP_PACKAGE, context.packageName)
                            }
                            context.startActivity(intent)
                        },
                        shapes = ListItemDefaults.segmentedShapes(index = 3, count = 4),
                        content = { Text("Notifications") },
                        supportingContent = { Text("System notification settings") }
                    )
                }
            }
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Appearance",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )
                Column(
                    verticalArrangement = Arrangement.spacedBy(ListItemDefaults.SegmentedGap)
                ) {
                    SegmentedListItem(
                        onClick = { showThemeDialog = true },
                        shapes = ListItemDefaults.segmentedShapes(index = 0, count = 2),
                        content = { Text("Theme") },
                        supportingContent = { Text(settings.theme.name) }
                    )
                    SegmentedListItem(
                        onClick = { viewModel.update { it.copy(dynamicColor = !it.dynamicColor) } },
                        shapes = ListItemDefaults.segmentedShapes(index = 1, count = 2),
                        content = { Text("Dynamic Color") },
                        trailingContent = {
                            Switch(
                                checked = settings.dynamicColor,
                                onCheckedChange = {
                                    viewModel.update { s -> s.copy(dynamicColor = it) }
                                }
                            )
                        }
                    )
                }
            }
        }
    }

    if (showThemeDialog) {
        OptionDialog(
            title = "Select Theme",
            options = Theme.entries.map { it.name },
            selected = settings.theme.name,
            onSelected = { name ->
                viewModel.update { it.copy(theme = Theme.valueOf(name)) }
                showThemeDialog = false
            },
            onDismiss = { showThemeDialog = false }
        )
    }

    if (showMediaDialog) {
        OptionDialog(
            title = "Media Preview",
            options = listOf("Disabled", "Disabled for Mobile Data", "Always Enabled"),
            selected = when (settings.media) {
                MediaConfig.Disabled -> "Disabled"
                MediaConfig.DisabledForMobileData -> "Disabled for Mobile Data"
                MediaConfig.AlwaysEnabled -> "Always Enabled"
            },
            onSelected = { choice ->
                val newConfig = when (choice) {
                    "Disabled" -> MediaConfig.Disabled
                    "Disabled for Mobile Data" -> MediaConfig.DisabledForMobileData
                    else -> MediaConfig.AlwaysEnabled
                }
                viewModel.update { it.copy(media = newConfig) }
                showMediaDialog = false
            },
            onDismiss = { showMediaDialog = false }
        )
    }

    if (showBlossomDialog) {
        var text by remember { mutableStateOf(settings.blossomServer ?: "") }
        AlertDialog(
            onDismissRequest = { showBlossomDialog = false },
            title = { Text("Blossom Server URL") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("https://...") },
                    singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.update { it.copy(blossomServer = text.ifBlank { null }) }
                    showBlossomDialog = false
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showBlossomDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
fun OptionDialog(
    title: String,
    options: List<String>,
    selected: String,
    onSelected: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(modifier = Modifier.selectableGroup()) {
                options.forEach { text ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(
                                selected = (text == selected),
                                onClick = { onSelected(text) },
                                role = Role.RadioButton
                            )
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(selected = (text == selected), onClick = null)
                        Spacer(Modifier.size(16.dp))
                        Text(text = text, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {}
    )
}
