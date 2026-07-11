package su.reya.coop.screens

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.coop
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalSnackbarHostState
import su.reya.coop.Screen
import su.reya.coop.shared.getExpressiveFontFamily

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen() {
    val context = LocalContext.current
    val snackbarHostState = LocalSnackbarHostState.current
    val navigator = LocalNavigator.current
    val accountViewModel = LocalAccountViewModel.current

    val accountState by accountViewModel.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    // Navigate to Home on successful external signer connection
    LaunchedEffect(accountState.signerRequired) {
        if (accountState.signerRequired == false) {
            navigator.navigate(Screen.Home)
        }
    }

    // Show connection errors
    LaunchedEffect(accountState.importError) {
        accountState.importError?.let {
            snackbarHostState.showSnackbar(it)
        }
    }

    val logoPainter = painterResource(Res.drawable.coop)
    val expressiveFont = getExpressiveFontFamily()

    val annotatedText = buildAnnotatedString {
        append("By using Coop, you agree to accept\nour ")
        // Push "Terms of Use" link
        pushLink(
            LinkAnnotation.Url(
                url = "https://coop.free/terms",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                )
            )
        )
        append("Terms of Use")
        pop()
        append(" and ")
        // Push "Privacy Policy" link
        pushLink(
            LinkAnnotation.Url(
                url = "https://coop.free/privacy",
                styles = TextLinkStyles(
                    style = SpanStyle(
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        fontWeight = FontWeight.SemiBold,
                    )
                )
            )
        )
        append("Privacy Policy")
        pop()
        append(".")
    }


    Scaffold(
        containerColor = MaterialTheme.colorScheme.secondaryContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = { innerPadding ->
            Box(modifier = Modifier.fillMaxSize()) {
                LogoRepeatingBackground(
                    painter = logoPainter,
                    logosPerRow = 6,
                    rotationDegrees = -25f,
                    horizontalOffset = 0.5f
                )
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(bottom = innerPadding.calculateBottomPadding() + 16.dp),
                ) {
                    Spacer(modifier = Modifier.weight(2f))
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp),
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.surface,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        shadowElevation = 4.dp,
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                        ) {
                            Text(
                                text = "Get Started",
                                style = MaterialTheme.typography.headlineSmallEmphasized.copy(
                                    fontFamily = expressiveFont,
                                ),
                                fontWeight = FontWeight.SemiBold,
                            )
                            Spacer(modifier = Modifier.size(8.dp))
                            Text(
                                text = "Coop is a secure and easy to use messaging app. All your communications are encrypted and private by default.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(modifier = Modifier.size(24.dp))
                            Button(
                                onClick = { navigator.navigate(Screen.NewIdentity) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .size(ButtonDefaults.MediumContainerHeight),
                            ) {
                                Text(
                                    text = "Start Messaging",
                                    style = MaterialTheme.typography.titleMediumEmphasized,
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            FilledTonalButton(
                                onClick = {
                                    if (accountViewModel.isExternalSignerAvailable()) {
                                        // Connect to the external signer
                                        // TODO: show all available signers?
                                        accountViewModel.connectExternalSigner()
                                    } else {
                                        scope.launch {
                                            val result = snackbarHostState.showSnackbar(
                                                message = "External signer not installed. Please install Amber or alternatives.",
                                                actionLabel = "Install",
                                                withDismissAction = true,
                                                duration = SnackbarDuration.Long
                                            )
                                            if (result == SnackbarResult.ActionPerformed) {
                                                val intent = Intent(
                                                    Intent.ACTION_VIEW,
                                                    "https://zapstore.dev/apps/com.greenart7c3.nostrsigner".toUri()
                                                )
                                                context.startActivity(intent)
                                            }
                                        }
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.MediumContainerHeight),
                            ) {
                                Text(
                                    text = "Connect with Amber",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                            Spacer(modifier = Modifier.size(8.dp))
                            TextButton(
                                onClick = { navigator.navigate(Screen.Import) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.MediumContainerHeight),
                            ) {
                                Text(
                                    text = "Add an Existing Identity",
                                    style = MaterialTheme.typography.titleMedium,
                                )
                            }
                        }
                    }
                    Text(
                        text = annotatedText,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    )
}

@Composable
fun LogoRepeatingBackground(
    painter: Painter,
    logosPerRow: Int,
    rotationDegrees: Float = 0f,
    horizontalOffset: Float = 0.5f
) {
    val tintColor = MaterialTheme.colorScheme.onSecondaryContainer

    Canvas(modifier = Modifier.fillMaxSize()) {
        val canvasWidth = size.width
        val canvasHeight = size.height
        val logoSize = canvasWidth / logosPerRow

        val offsetX = logoSize * horizontalOffset
        val extraPadding = 2

        val cols = logosPerRow + (extraPadding * 2)
        val rows = (canvasHeight / logoSize).toInt() + 1

        for (row in 0 until rows) {
            for (col in -extraPadding until cols) {
                val px = (col * logoSize) - offsetX
                val py = row * logoSize

                rotate(
                    degrees = rotationDegrees,
                    pivot = Offset(
                        px + logoSize / 2,
                        py + logoSize / 2
                    )
                ) {
                    translate(left = px, top = py) {
                        with(painter) {
                            draw(
                                size = Size(logoSize, logoSize),
                                alpha = 0.1f,
                                colorFilter = ColorFilter.tint(
                                    tintColor
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}
