package su.reya.coop.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import rust.nostr.sdk.PublicKey
import su.reya.coop.nostr.Room
import su.reya.coop.short
import su.reya.coop.viewmodels.ProfileViewModel

fun Room.nameFlow(
    profileViewModel: ProfileViewModel,
    currentUser: PublicKey? = null
): Flow<String> {
    // Return early if there's a custom subject/room name
    subject?.takeIf { it.isNotBlank() }?.let { return flowOf(it) }

    val displayMembers = if (isGroup()) members.take(2) else members.take(1)
    if (displayMembers.isEmpty()) return flowOf("Unknown")

    return combine(displayMembers.map { profileViewModel.getMetadata(it) }) { metadataArray ->
        val names = metadataArray.mapIndexed { i, metadata ->
            val profile = metadata?.asRecord()
            profile?.displayName?.takeIf { it.isNotBlank() }
                ?: profile?.name?.takeIf { it.isNotBlank() }
                ?: displayMembers[i].short()
        }

        if (isGroup()) {
            val combined = names.joinToString(", ")
            val extraCount = members.size - names.size
            if (extraCount > 0) "$combined, +$extraCount" else combined
        } else {
            val name = names.first()
            if (displayMembers.first() == currentUser) "$name (you)" else name
        }
    }
}

fun Room.pictureFlow(profileViewModel: ProfileViewModel): Flow<String?> {
    val firstMember = members.firstOrNull() ?: return flowOf(null)
    return profileViewModel.getMetadata(firstMember).map { it?.asRecord()?.picture }
}
