package su.reya.coop.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_arrow_back
import org.jetbrains.compose.resources.painterResource
import su.reya.coop.LocalNavController

@Composable
fun ScanScreen(
    onBack: () -> Unit
) {
    val navController = LocalNavController.current
    
    val onResult: (String) -> Unit = { result ->
        navController.previousBackStackEntry
            ?.savedStateHandle
            ?.set("qr_result", result)
        navController.popBackStack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Scan QR") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_back),
                            contentDescription = "Back"
                        )
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.padding(innerPadding)) {
            Text("Scan QR")
        }
    }
}