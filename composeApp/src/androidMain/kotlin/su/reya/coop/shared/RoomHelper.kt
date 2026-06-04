package su.reya.coop.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import su.reya.coop.NostrViewModel
import su.reya.coop.Room
import su.reya.coop.short

fun Room.displayNameFlow(viewModel: NostrViewModel): Flow<String> {
    // Return early if there's a custom subject/room name
    subject?.takeIf { it.isNotBlank() }?.let { return flowOf(it) }

    val displayMembers = if (isGroup()) members.take(2) else members.take(1)
    if (displayMembers.isEmpty()) return flowOf("Unknown")

    return combine(displayMembers.map { viewModel.getMetadata(it) }) { metadataArray ->
        val names = metadataArray.mapIndexed { i, metadata ->
            val profile = metadata?.asRecord()
            profile?.name?.takeIf { it.isNotBlank() }
                ?: profile?.displayName?.takeIf { it.isNotBlank() }
                ?: displayMembers[i].short()
        }

        if (isGroup()) {
            val combined = names.joinToString(", ")
            val extraCount = members.size - names.size
            if (extraCount > 0) "$combined, +$extraCount" else combined
        } else {
            val name = names.first()
            if (displayMembers.first() == viewModel.currentUser()) "$name (you)" else name
        }
    }
}

fun Room.pictureFlow(viewModel: NostrViewModel): Flow<String?> {
    val firstMember = members.firstOrNull() ?: return flowOf(null)
    return viewModel.getMetadata(firstMember).map { it?.asRecord()?.picture }
}