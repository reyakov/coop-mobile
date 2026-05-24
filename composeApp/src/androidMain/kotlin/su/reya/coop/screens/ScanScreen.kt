package su.reya.coop.screens

import android.annotation.SuppressLint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource
import org.publicvalue.multiplatform.qrcode.CameraPosition
import org.publicvalue.multiplatform.qrcode.CodeType
import org.publicvalue.multiplatform.qrcode.ScannerWithPermissions
import su.reya.coop.LocalNavController
import su.reya.coop.LocalSnackbarHostState

@SuppressLint("UnusedMaterial3ScaffoldPaddingParameter")
@Composable
fun ScanScreen(
    onBack: () -> Unit
) {
    val navController = LocalNavController.current
    val snackbarHostState = LocalSnackbarHostState.current

    val onResult: (String) -> Unit = { result ->
        navController.previousBackStackEntry?.savedStateHandle?.set("qr_result", result)
        navController.popBackStack()
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Scan QR",
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent,
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White,
                )
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize()) {
            ScannerWithPermissions(
                modifier = Modifier.fillMaxSize(),
                onScanned = {
                    onResult(it)
                    true
                },
                types = listOf(CodeType.QR),
                cameraPosition = CameraPosition.BACK,
                enableTorch = false
            )
            Canvas(modifier = Modifier.fillMaxSize()) {
                val scannerSize = 250.dp.toPx()
                val left = (size.width - scannerSize) / 2
                val top = (size.height - scannerSize) / 2
                drawRect(color = Color.Black.copy(alpha = 0.6f))
                drawRect(
                    color = Color.Transparent,
                    topLeft = Offset(left, top),
                    size = Size(scannerSize, scannerSize),
                    blendMode = BlendMode.Clear
                )
            }
            Box(
                modifier = Modifier
                    .size(250.dp)
                    .align(Alignment.Center)
                    .border(2.dp, Color.White, RoundedCornerShape(12.dp))
            )
            Text(
                text = "Scan a Nostr address",
                style = MaterialTheme.typography.titleSmallEmphasized,
                color = Color.White,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 64.dp)
            )
        }
    }
}