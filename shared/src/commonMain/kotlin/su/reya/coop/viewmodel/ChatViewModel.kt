package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import rust.nostr.sdk.EventId
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.Room
import su.reya.coop.repository.ChatRepository
import su.reya.coop.repository.ChatState

class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel(), ErrorHost by repository {
    val state: StateFlow<ChatState> = repository.state
    val newEvents: SharedFlow<UnsignedEvent> = repository.newEvents
    val chatRooms: StateFlow<List<Room>> = repository.chatRooms
    val isSyncing: StateFlow<Boolean> = repository.isSyncing
    val isPartialProcessedGiftWrap: StateFlow<Boolean> = repository.isPartialProcessedGiftWrap

    fun createChatRoom(to: List<PublicKey>): Long = repository.createChatRoom(to)
    fun getChatRoom(id: Long): Room? = repository.getChatRoom(id)
    fun refreshChatRooms() = repository.refreshChatRooms()
    fun loadChatRoomMessages(roomId: Long, onResult: (List<UnsignedEvent>) -> Unit) =
        repository.loadChatRoomMessages(roomId, onResult)

    fun chatRoomConnect(roomId: Long) = repository.chatRoomConnect(roomId)
    fun sendMessage(roomId: Long, message: String, replies: List<EventId> = emptyList()) =
        repository.sendMessage(roomId, message, replies)

    fun sendFileMessage(
        roomId: Long,
        file: ByteArray?,
        contentType: String? = "image/jpeg",
        replies: List<EventId> = emptyList()
    ) = repository.sendFileMessage(roomId, file, contentType, replies)

    fun isMessageSent(id: EventId): Boolean = repository.isMessageSent(id)
    fun resetInternalState() = repository.resetInternalState()
}
