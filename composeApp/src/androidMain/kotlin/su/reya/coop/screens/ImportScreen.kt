package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.toShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavController
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportScreen(
    isLoading: Boolean,
    onBack: () -> Unit,
    onSave: (secret: String) -> Unit
) {
    val snackbarHostState = LocalSnackbarHostState.current
    val navController = LocalNavController.current
    val viewModel = LocalNostrViewModel.current
    val scope = rememberCoroutineScope()

    var secret by remember { mutableStateOf("") }
    var pubkey by remember { mutableStateOf<PublicKey?>(null) }
    val metadata by remember(pubkey) {
        if (pubkey != null) {
            viewModel.getMetadata(pubkey!!)
        } else {
            MutableStateFlow(null)
        }
    }.collectAsState(null)


    val profile = metadata?.asRecord()
    val displayName = profile?.displayName ?: profile?.name ?: pubkey?.short() ?: "Unknown"
    val picture = profile?.picture

    val savedStateHandle = navController.currentBackStackEntry?.savedStateHandle
    val qrResult by savedStateHandle
        ?.getStateFlow<String?>("qr_result", null)
        ?.collectAsState()
        ?: remember { mutableStateOf(null) }

    LaunchedEffect(qrResult) {
        qrResult?.let { result ->
            runCatching {
                if (result.startsWith("nsec")) {
                    Keys.parse(result)
                } else if (result.startsWith("bunker://")) {
                    NostrConnectUri.parse(result)
                } else {
                    throw IllegalArgumentException("Invalid secret: $result")
                }
            }
                .onSuccess { it -> secret = result }
                .onFailure { e -> println("Failed to parse QR: ${e.message}") }
            // Clear the nav state
            navController.currentBackStackEntry?.savedStateHandle?.remove<String>("qr_result")
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Import",
                        style = MaterialTheme.typography.titleMediumEmphasized
                    )
                },
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
                modifier = Modifier.fillMaxSize(),
            ) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(top = innerPadding.calculateTopPadding()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(MaterialShapes.Pentagon.toShape()),
                        contentAlignment = Alignment.Center
                    ) {
                        Avatar(
                            picture = picture,
                            description = "Profile picture",
                            modifier = Modifier.fillMaxSize(),
                            shape = MaterialShapes.Pentagon.toShape(),
                        )
                    }
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(
                        text = displayName,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLargeEmphasized,
                    )
                }
                Surface(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text(
                            text = "Enter your Secret Key or Bunker URI:",
                            style = MaterialTheme.typography.titleMediumEmphasized.copy(
                                fontWeight = FontWeight.SemiBold,
                            ),
                        )
                        BasicTextField(
                            value = secret,
                            onValueChange = { secret = it },
                            modifier = Modifier.fillMaxWidth(),
                            maxLines = 4,
                            visualTransformation = PasswordVisualTransformation('*'),
                            textStyle = MaterialTheme.typography.bodyMediumEmphasized.copy(
                                color = MaterialTheme.colorScheme.primaryFixed,
                                fontWeight = FontWeight.SemiBold,
                            ),
                            cursorBrush = SolidColor(MaterialTheme.colorScheme.secondary),
                            decorationBox = { innerTextField ->
                                Box(contentAlignment = Alignment.CenterStart) {
                                    if (secret.isEmpty()) {
                                        Text(
                                            "bunker://",
                                            style = MaterialTheme.typography.bodyMediumEmphasized.copy(
                                                fontWeight = FontWeight.SemiBold,
                                            ),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                                alpha = 0.5f
                                            )
                                        )
                                    }
                                    innerTextField()
                                }
                            }
                        )
                        Spacer(modifier = Modifier.weight(1f))
                        Button(
                            onClick = {
                                if (pubkey == null) {
                                    scope.launch {
                                        viewModel.verifyIdentity(secret).let { pubkey = it }
                                    }
                                } else {
                                    onSave(secret)
                                }
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonDefaults.MediumContainerHeight),
                            enabled = secret.isNotBlank() && !isLoading,
                        ) {
                            if (isLoading) {
                                LoadingIndicator()
                            } else {
                                Text(
                                    text = if (pubkey == null) "Verify" else "Continue",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                            }
                        }
                    }
                }
            }
        }
    )
}
