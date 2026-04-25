package su.reya.coop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.serialization.Serializable

sealed interface Screen {
    @Serializable
    data object Home : Screen

    @Serializable
    data class Chat(val id: String) : Screen

    @Serializable
    data object Onboarding : Screen

    @Serializable
    data object Import : Screen

    @Serializable
    data object New : Screen
}

@Composable
fun HomeScreen(onOpenChat: (String) -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Home Screen")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { onOpenChat("123") }) {
                Text("Open Chat 123")
            }
        }
    }
}

@Composable
fun ChatScreen(id: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Chat Screen (ID: $id)")
    }
}

@Composable
fun OnboardingScreen(onOpenImport: () -> Unit, onOpenNew: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Onboarding Screen")
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onOpenImport) {
                Text("Import")
            }
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = onOpenNew) {
                Text("New")
            }
        }
    }
}

@Composable
fun ImportScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("Import Screen")
    }
}

@Composable
fun NewScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("New Screen")
    }
}
