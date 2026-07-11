package su.reya.coop.viewmodel

import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
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
import su.reya.coop.Room
import su.reya.coop.nostr.Nostr
import su.reya.coop.repository.MediaRepository
import su.reya.coop.roomId

data class ChatState(
    val rooms: Map<Long, Room> = emptyMap(),
    val isPartialProcessedGiftWrap: Boolean = false,
)

class ChatViewModel(
    private val nostr: Nostr,
    private val mediaRepository: MediaRepository,
) : BaseViewModel() {
    private val _state = MutableStateFlow(ChatState())
    val state = _state.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5000),
        ChatState()
    )

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val newEvents = _newEvents.asSharedFlow()

    val chatRooms = state.map { it.rooms.values.sortedByDescending { it.createdAt.asSecs() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSyncing = nostr.messages.messageSyncState.map { it.isSyncing }
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

                    // Refresh UI every 100 messages OR when sync is fully done
                    if (syncState.processedCount % 100 == 0 || !syncState.isSyncing) {
                        refreshChatRooms()
                    }
                }
            }

            // Observe new messages
            launch {
                nostr.newEvents.collect { event ->
                    val roomId = event.roomId()
                    val existingRoom = _state.value.rooms[roomId]

                    if (existingRoom == null) {
                        val currentUser = nostr.signer.getPublicKeyAsync() ?: return@collect
                        val newRoom = Room.new(event, currentUser)
                        _state.update { it.copy(rooms = it.rooms + (newRoom.id to newRoom)) }
                    } else {
                        updateRoomList(roomId, event)
                    }

                    _newEvents.tryEmit(event)
                }
            }
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
            val existingRoom = _state.value.rooms[id]

            // If the room already exists, return its ID
            if (existingRoom != null) {
                return existingRoom.id
            }

            // Create a room from the rumor event
            val room = Room.new(rumor, currentUser)

            // Update the chat rooms state
            _state.update { it.copy(rooms = it.rooms + (room.id to room)) }

            return room.id
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to create room: ${e.message}")
        }
    }

    fun getChatRoom(id: Long): Room? {
        return _state.value.rooms[id]
    }

    fun refreshChatRooms() {
        viewModelScope.launch {
            try {
                val rooms = nostr.messages.getChatRooms() ?: emptySet()
                _state.update { currentState ->
                    val newMap = currentState.rooms.toMutableMap()
                    rooms.forEach { room -> newMap[room.id] = room }
                    currentState.copy(rooms = newMap)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun loadChatRoomMessages(roomId: Long, onResult: (List<UnsignedEvent>) -> Unit) {
        viewModelScope.launch {
            try {
                onResult(nostr.messages.getChatRoomMessages(roomId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
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
                        viewModelScope.launch { _newEvents.tryEmit(event) }
                    },
                )
            } catch (e: Exception) {
                showError("Error: ${e.message}")
            }
        }
    }

    fun sendFileMessage(
        roomId: Long,
        file: ByteArray?,
        contentType: String? = "image/jpeg",
        replies: List<EventId> = emptyList()
    ) {
        if (file == null) return

        viewModelScope.launch {
            try {
                val uri = mediaRepository.blossomUpload(nostr.signer.get(), file, contentType)
                if (uri != null) sendMessage(roomId, uri, replies)
            } catch (e: Exception) {
                showError("File upload failed: ${e.message}")
            }
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
            val room = currentState.rooms[roomId] ?: return@update currentState
            val updatedRoom = room.copy(
                lastMessage = newMessage.content(),
                createdAt = newMessage.createdAt()
            )
            currentState.copy(rooms = currentState.rooms + (roomId to updatedRoom))
        }
    }

    fun resetInternalState() {
        _state.update {
            it.copy(
                rooms = emptyMap(),
                isPartialProcessedGiftWrap = false,
            )
        }
    }
}
