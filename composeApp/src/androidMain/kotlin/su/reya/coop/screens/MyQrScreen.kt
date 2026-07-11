package su.reya.coop.screens

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import io.github.alexzhirkevich.qrose.rememberQrCodePainter
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNavigator
import su.reya.coop.LocalSnackbarHostState

@Composable
fun MyQrScreen() {
    val navigator = LocalNavigator.current
    val snackbarHostState = LocalSnackbarHostState.current
    val accountViewModel = LocalAccountViewModel.current
    val currentUser by accountViewModel.currentUserProfile.collectAsStateWithLifecycle()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "My QR",
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
                }
            )
        },
        content = { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Image(
                    painter = rememberQrCodePainter(currentUser?.publicKey?.toBech32() ?: ""),
                    contentDescription = "My QR"
                )
            }
        }
    )
}