package su.reya.coop.viewmodels

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.Tag
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.nostr.Nostr
import su.reya.coop.nostr.Room
import su.reya.coop.nostr.roomId

class ChatViewModel(
    private val nostr: Nostr,
    private val appViewModel: AppViewModel
) : ViewModel() {
    private val _chatRooms = MutableStateFlow<Set<Room>>(emptySet())
    val chatRooms = _chatRooms.asStateFlow()

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _isPartialProcessedGiftWrap = MutableStateFlow(false)
    val isPartialProcessedGiftWrap = _isPartialProcessedGiftWrap.asStateFlow()

    val isSyncing = nostr.messages.messageSyncState.map { it.isSyncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    suspend fun bindObservers() = coroutineScope {
        launch {
            nostr.messages.messageSyncState.collect { state ->
                if (state.processedCount > 0) {
                    _isPartialProcessedGiftWrap.value = true
                }
                if (state.processedCount % 10 == 0 || !state.isSyncing) {
                    refreshChatRooms()
                }
            }
        }

        launch {
            nostr.newEvents.collect { event ->
                val roomId = event.roomId()
                val existingRoom = _chatRooms.value.firstOrNull { it.id == roomId }

                if (existingRoom == null) {
                    val currentUser = nostr.signer.currentUser
                    if (currentUser != null) {
                        val newRoom = Room.new(event, currentUser)
                        _chatRooms.update { (it + newRoom).sortedDescending().toSet() }
                    }
                } else {
                    updateRoomList(roomId, event)
                }

                _newEvents.emit(event)
            }
        }
    }

    fun createChatRoom(to: List<PublicKey>): Long {
        try {
            val currentUser =
                nostr.signer.currentUser ?: throw IllegalStateException("User not signed in")
            if (to.isEmpty()) throw IllegalArgumentException("At least one recipient is required")

            val rumor = EventBuilder(Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE), "")
                .tags(to.map { Tag.publicKey(it) })
                .finalizeUnsigned(currentUser)

            val id = rumor.roomId()
            val existingRoom = _chatRooms.value.firstOrNull { it.id == id }

            if (existingRoom != null) {
                return existingRoom.id
            }

            val room = Room.new(rumor, currentUser)
            _chatRooms.update { currentRooms ->
                (currentRooms + room).sortedDescending().toSet()
            }

            return room.id
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to create room: ${e.message}")
        }
    }

    fun getChatRoom(id: Long): Room? {
        return chatRooms.value.firstOrNull { it.id == id }
    }

    fun getChatRooms() {
        viewModelScope.launch {
            val rooms = nostr.messages.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
        }
    }

    suspend fun refreshChatRooms() {
        try {
            val rooms = nostr.messages.getChatRooms() ?: emptySet()
            mergeChatRooms(rooms)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
    }

    fun mergeChatRooms(rooms: Set<Room>) {
        _chatRooms.update { currentRooms ->
            val merged = currentRooms.associateBy { it.id }.toMutableMap()
            rooms.forEach { room ->
                merged[room.id] = room
            }
            merged.values.sortedDescending().toSet()
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            return nostr.messages.getChatRoomMessages(roomId)
        } catch (e: Exception) {
            appViewModel.showError("Error: ${e.message}")
        }
        return emptyList()
    }

    fun chatRoomConnect(roomId: Long) {
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                nostr.messages.chatRoomConnect(room.members.toList())
            } catch (e: Exception) {
                appViewModel.showError("Error: ${e.message}")
            }
        }
    }

    fun sendMessage(
        roomId: Long,
        message: String,
        replies: List<EventId> = emptyList()
    ) {
        if (message.isEmpty()) {
            appViewModel.showError("Message cannot be empty")
            return
        }
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                nostr.messages.sendMessage(
                    to = room.members,
                    content = message,
                    subject = room.subject,
                    replies = replies,
                    onRumorCreated = { event ->
                        updateRoomList(roomId, event)
                        viewModelScope.launch { _newEvents.emit(event) }
                    },
                )
            } catch (e: Exception) {
                appViewModel.showError("Error: ${e.message}")
            }
        }
    }

    suspend fun sendFileMessage(
        roomId: Long,
        file: ByteArray?,
        contentType: String? = "image/jpeg",
        replies: List<EventId> = emptyList()
    ) {
        if (file == null) return
        
        try {
            val uri = appViewModel.blossomUpload(file, contentType)
                ?: throw IllegalArgumentException("Failed to upload file")

            sendMessage(roomId, uri, replies)
        } catch (e: Exception) {
            throw IllegalArgumentException("Error: ${e.message}")
        }
    }

    fun isMessageSent(id: EventId): Boolean {
        val giftWrapId = nostr.messages.rumorMap[id]
        return if (giftWrapId != null) {
            nostr.messages.sentEvents[giftWrapId]?.isNotEmpty() ?: false
        } else {
            false
        }
    }

    private fun updateRoomList(roomId: Long, newMessage: UnsignedEvent) {
        _chatRooms.update { currentRooms ->
            currentRooms.map { room ->
                if (room.id == roomId) {
                    room.copy(
                        lastMessage = newMessage.content(),
                        createdAt = newMessage.createdAt()
                    )
                } else {
                    room
                }
            }.sortedDescending().toSet()
        }
    }
}
