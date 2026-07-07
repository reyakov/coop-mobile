package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.Tag
import rust.nostr.sdk.UnsignedEvent
import su.reya.coop.Room
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository
import su.reya.coop.roomId

data class ChatState(
    val rooms: Set<Room> = emptySet(),
    val isSyncing: Boolean = false,
    val isPartialProcessedGiftWrap: Boolean = false,
)

class ChatViewModel(private val nostr: Nostr) : BaseViewModel() {
    private val mediaRepository = MediaRepository()

    private val _state = MutableStateFlow(ChatState())
    val state = combine(
        _state,
        nostr.messages.messageSyncState
    ) { local, state -> local.copy(isSyncing = state.isSyncing) }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatState()
    )

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(extraBufferCapacity = 100)
    val newEvents = _newEvents.asSharedFlow()

    private val _sentReports = MutableSharedFlow<Map<EventId, List<RelayUrl>>>()
    val sentReport = _sentReports.asSharedFlow()

    val chatRooms = state.map { it.rooms }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptySet())

    val isSyncing = state.map { it.isSyncing }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    val isPartialProcessedGiftWrap = state.map { it.isPartialProcessedGiftWrap }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    init {
        viewModelScope.launch {
            nostr.waitUntilInitialized()

            // Observe message sync progress
            launch {
                nostr.messages.messageSyncState.collect { syncState ->
                    // When at least some messages are processed, allow UI to show the list
                    if (syncState.processedCount > 0 || !syncState.isSyncing) {
                        _state.update { it.copy(isPartialProcessedGiftWrap = true) }
                    }

                    // Refresh UI every 10 messages OR when sync is fully done
                    if (syncState.processedCount % 10 == 0 || !syncState.isSyncing) {
                        refreshChatRooms()
                    }
                }
            }

            // Observe new messages
            launch {
                nostr.newEvents.collect { event ->
                    val roomId = event.roomId()
                    val existingRoom = _state.value.rooms.firstOrNull { it.id == roomId }

                    if (existingRoom == null) {
                        val currentUser = nostr.signer.getPublicKeyAsync() ?: return@collect
                        val newRoom = Room.new(event, currentUser)
                        _state.update {
                            it.copy(
                                rooms = (it.rooms + newRoom).sortedDescending().toSet()
                            )
                        }
                    } else {
                        updateRoomList(roomId, event)
                    }

                    _newEvents.emit(event)
                }
            }

            // Initial load of rooms
            refreshChatRooms()
        }
    }

    fun createChatRoom(to: List<PublicKey>): Long {
        try {
            if (to.isEmpty()) {
                throw IllegalArgumentException("At least one recipient is required")
            }

            // Get current user
            val currentUser = nostr.signer.publicKeyFlow.value
                ?: throw IllegalStateException("User not signed in")

            // Construct the rumor event
            val rumor = EventBuilder(Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE), "")
                .tags(to.map { Tag.publicKey(it) })
                .finalizeUnsigned(currentUser)

            // Check if the room already exists
            val id = rumor.roomId()
            val existingRoom = _state.value.rooms.firstOrNull { it.id == id }

            // If the room already exists, return its ID
            if (existingRoom != null) {
                return existingRoom.id
            }

            // Create a room from the rumor event
            val room = Room.new(rumor, currentUser)

            // Update the chat rooms state
            _state.update { it.copy(rooms = (it.rooms + room).sortedDescending().toSet()) }

            return room.id
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to create room: ${e.message}")
        }
    }

    fun getChatRoom(id: Long): Room? {
        return _state.value.rooms.firstOrNull { it.id == id }
    }

    suspend fun refreshChatRooms() {
        try {
            val rooms = nostr.messages.getChatRooms() ?: emptySet()
            _state.update { currentState ->
                val merged = currentState.rooms.associateBy { it.id }.toMutableMap()
                // Add or update rooms from the database
                rooms.forEach { room ->
                    merged[room.id] = room
                }
                // Return as a sorted set to maintain UI consistency
                currentState.copy(rooms = merged.values.sortedDescending().toSet())
            }
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            return nostr.messages.getChatRoomMessages(roomId)
        } catch (e: Exception) {
            showError("Error: ${e.message}")
        }

        return emptyList()
    }

    fun chatRoomConnect(roomId: Long) {
        viewModelScope.launch {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                val members = room.members

                nostr.messages.chatRoomConnect(members.toList())
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun sendMessage(roomId: Long, message: String, replies: List<EventId> = emptyList()) {
        if (message.isEmpty()) {
            showError("Message cannot be empty")
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
                showError("Error: ${e.message}")
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
            val uri = mediaRepository.blossomUpload(nostr.signer.get(), file, contentType)
            if (uri != null) sendMessage(roomId, uri, replies)
        } catch (e: Exception) {
            throw IllegalArgumentException("Error: ${e.message}")
        }
    }

    fun isMessageSent(id: EventId): Boolean {
        val giftWrapId = nostr.messages.rumorMap[id]

        if (giftWrapId != null) {
            val isSent = nostr.messages.sentEvents[giftWrapId]?.isNotEmpty() ?: false
            return isSent
        } else {
            return false
        }
    }

    private fun updateRoomList(roomId: Long, newMessage: UnsignedEvent) {
        _state.update { currentState ->
            val updatedRooms = currentState.rooms.map { room ->
                if (room.id == roomId) {
                    room.copy(
                        lastMessage = newMessage.content(),
                        createdAt = newMessage.createdAt()
                    )
                } else {
                    room
                }
            }.sortedDescending().toSet()
            currentState.copy(rooms = updatedRooms)
        }
    }

    fun resetInternalState() {
        _state.update {
            it.copy(
                rooms = emptySet(),
                isPartialProcessedGiftWrap = false,
            )
        }
    }
}
