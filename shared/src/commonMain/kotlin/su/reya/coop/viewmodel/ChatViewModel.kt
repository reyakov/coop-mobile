package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.PublicKey
import su.reya.coop.Room
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.ChatState

class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel(), ErrorHost by repository {
    val state: StateFlow<ChatState> = repository.state
    val chatRooms: StateFlow<List<Room>> = repository.chatRooms
    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val isPartialProcessedGiftWrap: StateFlow<Boolean> = repository.isPartialProcessedGiftWrap

    fun createChatRoom(to: List<PublicKey>): Long = repository.createChatRoom(to)
    fun refreshChatRooms() = repository.refreshChatRooms()
    fun resetInternalState() = repository.resetInternalState()
}
