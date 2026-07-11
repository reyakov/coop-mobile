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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import coop.composeapp.generated.resources.ic_scanner
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.Keys
import rust.nostr.sdk.NostrConnectUri
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalScanResult
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ImportScreen() {
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val qrScanResult = LocalScanResult.current
    val focusManager = LocalFocusManager.current
    val accountViewModel = LocalAccountViewModel.current

    val accountState by accountViewModel.state.collectAsStateWithLifecycle()

    var secret by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var requirePassword by remember { mutableStateOf(false) }

    LaunchedEffect(qrScanResult.content) {
        qrScanResult.content?.let { result ->
            runCatching {
                if (result.startsWith("nsec1") || result.startsWith("ncryptsec1")) {
                    Keys.parse(result)
                } else if (result.startsWith("bunker://")) {
                    NostrConnectUri.parse(result)
                } else {
                    throw IllegalArgumentException("Unsupported secret format")
                }
            }.onSuccess {
                secret = result
            }.onFailure { e ->
                e.message?.let { snackbarHostState.showSnackbar(it) }
            }
            // Clear the nav state
            qrScanResult.clear()
        }
    }

    LaunchedEffect(secret) {
        if (secret.startsWith("ncryptsec1")) {
            requirePassword = true
        }
    }

    // Navigate to Home on successful import (signerRequired becomes false)
    LaunchedEffect(accountState.signerRequired) {
        if (accountState.signerRequired == false) {
            navigator.navigate(Screen.Home)
        }
    }

    // Show import errors via snackbar
    LaunchedEffect(accountState.importError) {
        accountState.importError?.let {
            snackbarHostState.showSnackbar(it)
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
                            .padding(24.dp),
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
                                enabled = !accountState.isImporting,
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
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
                            Spacer(modifier = Modifier.size(8.dp))
                            if (requirePassword) {
                                Text(
                                    text = "Decrypt Password:",
                                    style = MaterialTheme.typography.titleMediumEmphasized.copy(
                                        fontWeight = FontWeight.SemiBold,
                                    ),
                                )
                                BasicTextField(
                                    value = password,
                                    onValueChange = { password = it },
                                    enabled = !accountState.isImporting && requirePassword,
                                    modifier = Modifier.fillMaxWidth(),
                                    singleLine = true,
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
                                            innerTextField()
                                        }
                                    }
                                )
                            }
                        }
                        Spacer(modifier = Modifier.size(16.dp))
                        Button(
                            onClick = {
                                accountViewModel.importIdentity(secret, password)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(ButtonDefaults.MediumContainerHeight),
                            enabled = secret.isNotBlank() && !accountState.isImporting,
                        ) {
                            if (accountState.isImporting) {
                                LoadingIndicator()
                            } else {
                                Text(
                                    text = "Continue",
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
