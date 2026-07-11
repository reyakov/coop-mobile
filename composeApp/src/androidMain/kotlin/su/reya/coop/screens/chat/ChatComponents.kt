package su.reya.coop.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coop.composeapp.generated.resources.Res
import coop.composeapp.generated.resources.ic_cancel
import coop.composeapp.generated.resources.ic_check_circle
import org.jetbrains.compose.resources.painterResource
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Timestamp
import su.reya.coop.LocalAccountViewModel
import su.reya.coop.LocalNostrViewModel
import su.reya.coop.Room
import su.reya.coop.humanReadable
import su.reya.coop.shared.Avatar
import su.reya.coop.shared.getExpressiveFontFamily
import su.reya.coop.short

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ScreenerCard(room: Room) {
    val pubkey = room.members.firstOrNull() ?: return

    val nostrViewModel = LocalNostrViewModel.current
    val accountViewModel = LocalAccountViewModel.current

    var isContact by remember { mutableStateOf(false) }
    var mutualContacts by remember { mutableStateOf<Set<PublicKey>>(emptySet()) }
    var lastActivity by remember { mutableStateOf<Timestamp?>(null) }

    val profileFlow = remember(pubkey) { nostrViewModel.getMetadata(pubkey) }
    val profile by profileFlow.collectAsStateWithLifecycle()

    LaunchedEffect(pubkey) {
        // Check contact
        accountViewModel.verifyContact(pubkey) { isContact = it }
        // Get mutual contacts
        accountViewModel.mutualContacts(pubkey) { mutualContacts = it }
        // Get the last activity
        accountViewModel.verifyActivity(pubkey) { lastActivity = it }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(top = 48.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Avatar(
                picture = profile?.picture,
                description = "Profile picture",
                modifier = Modifier.size(120.dp),
                shape = MaterialShapes.Cookie12Sided.toShape(),
            )
            Column(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = profile?.name ?: "No name",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.titleLargeEmphasized.copy(
                        fontFamily = getExpressiveFontFamily()
                    ),
                )
                Text(
                    text = pubkey.short(),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.outline
                )
            }
        }
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (isContact) Res.drawable.ic_check_circle else Res.drawable.ic_cancel
                    ),
                    contentDescription = "Warning",
                    tint = if (isContact) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (isContact) "Contact" else "Not a contact",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(
                        if (mutualContacts.isNotEmpty()) Res.drawable.ic_check_circle else Res.drawable.ic_cancel
                    ),
                    contentDescription = "Warning",
                    tint = if (mutualContacts.isNotEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                )
                Text(
                    text = if (mutualContacts.isEmpty()) "No contacts in common" else "${mutualContacts.size} contacts in common",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    painter = painterResource(Res.drawable.ic_check_circle),
                    contentDescription = "Warning",
                    tint = if (lastActivity != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
                )
                Text(
                    text = if (lastActivity == null) "Don't have any public activities" else "Last activity at ${lastActivity?.humanReadable()}",
                    style = MaterialTheme.typography.labelMediumEmphasized
                )
            }
        }
    }
}

@Composable
fun DateSeparator(date: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = date,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.outline
        )
    }
}
