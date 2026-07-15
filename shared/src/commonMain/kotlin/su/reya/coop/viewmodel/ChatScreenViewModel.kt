package su.reya.coop.viewmodel

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import rust.nostr.sdk.EventId
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.Room
import su.reya.coop.repository.AccountRepository
import su.reya.coop.repository.ChatRepository
import su.reya.coop.roomId

class ChatScreenViewModel(
    initialRoom: Room,
    screening: Boolean,
    accountRepository: AccountRepository,
    private val chatRepository: ChatRepository,
) : ViewModel(), ErrorHost by chatRepository {
    var loading by mutableStateOf(true)
    var newOtherMessages by mutableIntStateOf(0)
    var requireScreening by mutableStateOf(screening)
    val messages = mutableStateListOf<UnsignedEvent>()

    val currentUser = accountRepository.currentUserProfile
    val id = initialRoom.id
    
    val room: StateFlow<Room> = chatRepository.chatRooms
        .map { rooms -> rooms.find { it.id == id } ?: initialRoom }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = initialRoom
        )

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
