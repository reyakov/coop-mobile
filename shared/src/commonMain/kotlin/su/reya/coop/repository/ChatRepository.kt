package su.reya.coop.repository

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
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
import su.reya.coop.Room
import su.reya.coop.RoomKind
import su.reya.coop.nostr.Nostr
import su.reya.coop.roomId
import su.reya.coop.viewmodel.ErrorHost
import su.reya.coop.viewmodel.createErrorHost

data class ChatState(
    val rooms: Map<Long, Room> = emptyMap(),
    val isPartialProcessedGiftWrap: Boolean = false,
)

class ChatRepository(
    private val nostr: Nostr,
    private val mediaRepository: MediaRepository,
    private val scope: CoroutineScope,
    private val defaultDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ErrorHost by createErrorHost() {
    private val _state = MutableStateFlow(ChatState())
    val state = _state.asStateFlow()

    private val _newEvents = MutableSharedFlow<UnsignedEvent>(
        replay = 0,
        extraBufferCapacity = 100,
        onBufferOverflow = BufferOverflow.SUSPEND
    )
    val newEvents = _newEvents.asSharedFlow()

    val chatRooms = state
        .map { it.rooms.values.sortedByDescending { it.createdAt.asSecs() } }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), emptyList())

    val isSyncing = nostr.messages.messageSyncState
        .map { it.isSyncing }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    val isPartialProcessedGiftWrap = state
        .map { it.isPartialProcessedGiftWrap }
        .stateIn(scope, SharingStarted.WhileSubscribed(5000), false)

    init {
        scope.launch(defaultDispatcher) {
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
                    updateRoomState(event)
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
        scope.launch(defaultDispatcher) {
            try {
                val dbRooms = nostr.messages.getChatRooms() ?: emptySet()
                _state.update { currentState ->
                    val newMap = currentState.rooms.toMutableMap()
                    dbRooms.forEach { dbRoom ->
                        val existing = newMap[dbRoom.id]
                        // Only update if the database version is newer or equal
                        if (existing == null || dbRoom.createdAt.asSecs() >= existing.createdAt.asSecs()) {
                            // Preserve Ongoing kind if already marked as such in memory
                            val mergedKind =
                                if (existing?.kind == RoomKind.Ongoing) RoomKind.Ongoing else dbRoom.kind
                            newMap[dbRoom.id] = dbRoom.copy(kind = mergedKind)
                        }
                    }
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
        scope.launch {
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
        scope.launch(defaultDispatcher) {
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
        scope.launch(defaultDispatcher) {
            try {
                val room = getChatRoom(roomId) ?: throw IllegalArgumentException("Room not found")
                nostr.messages.sendMessage(
                    to = room.members,
                    content = message,
                    subject = room.subject,
                    replies = replies,
                    onRumorCreated = {
                        scope.launch(defaultDispatcher) {
                            updateRoomState(it, roomId)
                        }
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

        scope.launch {
            try {
                val uri = mediaRepository.blossomUpload(nostr.signer.get(), file, contentType)
                if (uri != null) sendMessage(roomId, uri, replies)
            } catch (e: Exception) {
                showError("File upload failed: ${e.message}")
            }
        }
    }

    private suspend fun updateRoomState(event: UnsignedEvent, roomId: Long = event.roomId()) {
        val currentUser = nostr.signer.getPublicKeyAsync() ?: return

        _state.update { currentState ->
            val rooms = currentState.rooms.toMutableMap()
            val existingRoom = rooms[roomId]

            val isFromMe = event.author() == currentUser
            val newKind =
                if (isFromMe) RoomKind.Ongoing else (existingRoom?.kind ?: RoomKind.Request)

            if (existingRoom == null) {
                // New room discovery
                val newRoom = Room.new(event, currentUser, roomId).copy(kind = newKind)
                rooms[newRoom.id] = newRoom
            } else if (event.createdAt().asSecs() >= existingRoom.createdAt.asSecs()) {
                // Only update preview if message is newer (handles sync/late arrivals)
                rooms[roomId] = existingRoom.copy(
                    lastMessage = event.content(),
                    createdAt = event.createdAt(),
                    kind = newKind
                )
            } else if (isFromMe && existingRoom.kind != RoomKind.Ongoing) {
                // Even if it's an older message, if it's from me, the room is ongoing
                rooms[roomId] = existingRoom.copy(kind = RoomKind.Ongoing)
            } else {
                // Don't update the room list state for older messages
                return@update currentState
            }
            currentState.copy(rooms = rooms)
        }

        // Notify subscribers about the new event (for the active chat screen)
        _newEvents.tryEmit(event)
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
