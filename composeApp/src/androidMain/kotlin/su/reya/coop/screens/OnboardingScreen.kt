package su.reya.coop.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.coop
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalSnackbarHostState

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun OnboardingScreen(onOpenImport: () -> Unit, onOpenNew: () -> Unit) {
    val snackbarHostState = LocalSnackbarHostState.current
    val logoPainter = painterResource(Res.drawable.coop)

    Scaffold(
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        content = { innerPadding ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(bottom = innerPadding.calculateBottomPadding())
            ) {
                LogoRepeatingBackground(
                    painter = logoPainter,
                    logosPerRow = 6,
                    rotationDegrees = -25f,
                    horizontalOffset = 0.5f
                )
                Column(
                    modifier = Modifier.fillMaxSize(),
                ) {
                    Box(
                        modifier = Modifier
                            .weight(2f)
                            .fillMaxWidth(),
                        contentAlignment = Alignment.Center,
                    ) {
                        // TODO: Add headline
                    }
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(bottom = innerPadding.calculateBottomPadding()),
                        contentAlignment = Alignment.BottomEnd,
                    ) {
                        Column(
                            modifier = Modifier.padding(horizontal = innerPadding.calculateBottomPadding()),
                        ) {
                            Button(
                                onClick = onOpenNew,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .size(ButtonDefaults.LargeContainerHeight),
                            ) {
                                Text(
                                    text = "Start messaging",
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                )
                            }
                            Spacer(modifier = Modifier.size(16.dp))
                            FilledTonalButton(
                                onClick = onOpenImport,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(ButtonDefaults.LargeContainerHeight),
                                colors = ButtonDefaults.filledTonalButtonColors(
                                    containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer
                                ),
                            ) {
                                Text(
                                    text = "Import identity",
                                    style = MaterialTheme.typography.titleLargeEmphasized,
                                )
                            }
                        }
                    }
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
    val tintColor = MaterialTheme.colorScheme.primary

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
