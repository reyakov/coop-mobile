package su.reya.coop.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_scanner
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnectUri
import rust.nostr.sdk.PublicKey
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.LocalScanResult
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportScreen() {
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val qrScanResult = LocalScanResult.current
    val focusManager = LocalFocusManager.current
    val viewModel = LocalNostrViewModel.current

    val scope = rememberCoroutineScope()

    val isLoggedIn by viewModel.isLoggedIn.collectAsStateWithLifecycle(false)
    var secret by remember { mutableStateOf("") }
    var pubkey by remember { mutableStateOf<PublicKey?>(null) }

    // Get metadata when pubkey changes
    val metadata by remember(pubkey) {
        pubkey?.let(viewModel::getMetadata) ?: flowOf(null)
    }.collectAsStateWithLifecycle(null)

    val profile = metadata?.asRecord()
    val displayName = profile?.displayName ?: profile?.name ?: pubkey?.short() ?: "Unknown"
    val picture = profile?.picture
    
    LaunchedEffect(qrScanResult.content) {
        qrScanResult.content?.let { result ->
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
                        text = "Import",
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
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = innerPadding.calculateTopPadding())
                    .imePadding(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
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
                    Text(
                        text = displayName,
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleLargeEmphasized.copy(
                            fontFamily = getExpressiveFontFamily()
                        ),
                    )
                }
                Surface(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false),
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
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
                                enabled = !isLoggedIn,
                                modifier = Modifier.fillMaxWidth(),
                                maxLines = 4,
                                keyboardOptions = KeyboardOptions(
                                    imeAction = ImeAction.Done,
                                ),
                                keyboardActions = KeyboardActions(
                                    onDone = {
                                        focusManager.clearFocus()
                                    }
                                ),
                                visualTransformation = PasswordVisualTransformation('*'),
                                textStyle = MaterialTheme.typography.bodyMediumEmphasized.copy(
                                    color = MaterialTheme.colorScheme.tertiaryFixedDim,
                                    fontWeight = FontWeight.SemiBold,
                                ),
                                cursorBrush = SolidColor(MaterialTheme.colorScheme.tertiaryContainer),
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
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        Button(
                            onClick = {
                                scope.launch {
                                    if (pubkey == null) {
                                        viewModel.verifyIdentity(secret).let { pubkey = it }
                                    } else {
                                        // Import the identity
                                        viewModel.importIdentity(secret)
                                        // Navigate to the home screen
                                        navigator.navigate(Screen.Home)
                                    }
                                }

                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonDefaults.MediumContainerHeight),
                            enabled = secret.isNotBlank() && !isLoggedIn,
                        ) {
                            if (isLoggedIn) {
                                LoadingIndicator()
                            } else {
                                Text(
                                    text = if (pubkey == null) "Verify" else "Click again to Continue",
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
