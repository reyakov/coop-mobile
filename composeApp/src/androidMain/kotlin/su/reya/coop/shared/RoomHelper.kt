package su.reya.coop.shared

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import su.reya.coop.NostrViewModel
import su.reya.coop.Room
import su.reya.coop.short

fun Room.displayNameFlow(viewModel: NostrViewModel): Flow<String> {
    if (!subject.isNullOrBlank()) return flowOf<String>(subject!!)

    val memberFlows = members.map { viewModel.getMetadata(it) }

    return combine(memberFlows) { metadataArray ->
        if (isGroup()) {
            val profiles = metadataArray.map { it?.asRecord() }
            val names = profiles.take(2).mapNotNull { it?.name ?: it?.displayName }
            var combined = names.joinToString(", ")
            if (profiles.size > 2) combined += ", +${profiles.size - 2}"
            combined.ifBlank { "Unknown group" }
        } else {
            val profile = metadataArray.firstOrNull()?.asRecord()
            profile?.name ?: profile?.displayName ?: members.firstOrNull()?.short() ?: "Unknown"
        }
    }
}

fun Room.pictureFlow(viewModel: NostrViewModel): Flow<String?> {
    val firstMember = members.firstOrNull() ?: return kotlinx.coroutines.flow.flowOf(null)
    return viewModel.getMetadata(firstMember).map { it?.asRecord()?.picture }
}
