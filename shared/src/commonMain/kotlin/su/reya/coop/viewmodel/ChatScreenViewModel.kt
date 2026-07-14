package su.reya.coop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import rust.nostr.sdk.EventId
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.Profile
import su.reya.coop.Room
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.ChatRepository
import su.reya.coop.roomId

class ChatScreenViewModel(
    val id: Long,
    screening: Boolean,
    accountRepository: AccountRepository,
    private val chatRepository: ChatRepository,
) : ViewModel(), ErrorHost by chatRepository {
    val currentUser: StateFlow<Profile?> = accountRepository.currentUserProfile
    val chatRooms: StateFlow<List<Room>> = chatRepository.chatRooms

    var loading by mutableStateOf(true)
    var newOtherMessages by mutableIntStateOf(0)
    var requireScreening by mutableStateOf(screening)
    val messages = mutableStateListOf<UnsignedEvent>()

    init {
        loadMessages()
        connect()
        observeNewEvents()
    }

    private fun loadMessages() {
        chatRepository.loadChatRoomMessages(id) { initialMessages ->
            messages.clear()
            messages.addAll(initialMessages.distinctBy { it.id() })
            loading = false
        }
    }

    private fun connect() {
        chatRepository.chatRoomConnect(id)
    }

    private fun observeNewEvents() {
        viewModelScope.launch {
            chatRepository.newEvents.collect { event ->
                if (event.roomId() == id) {
                    if (messages.none { it.id() == event.id() }) {
                        messages.add(0, event)
                    }
                } else {
                    newOtherMessages++
                }
            }
        }
    }

    fun sendMessage(text: String, replyTo: EventId? = null) {
        val replyToList = if (replyTo != null) listOf(replyTo) else emptyList()
        chatRepository.sendMessage(id, text, replyToList)
    }

    fun sendFileMessage(file: ByteArray?, type: String?) {
        chatRepository.sendFileMessage(id, file, type)
    }
}
