package su.reya.coop.nostr

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import rust.nostr.sdk.AckPolicy
import rust.nostr.sdk.Alphabet
import rust.nostr.sdk.Client
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.EventId
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SingleLetterTag
import rust.nostr.sdk.Tag
import rust.nostr.sdk.UnsignedEvent
import rust.nostr.sdk.nip17ExtractRelayList
import rust.nostr.sdk.nip59MakeGiftWrapAsync
import su.reya.coop.Room
import su.reya.coop.RoomKind
import su.reya.coop.roomId
import kotlin.time.Duration

data class MessageSyncState(
    val processedCount: Int = 0,
    val isSyncing: Boolean = false
)

class MessageManager(private val nostr: Nostr) {
    private val client: Client? get() = nostr.client
    private val signer: UniversalSigner get() = nostr.signer

    val sentEvents: MutableMap<EventId, List<RelayUrl>> = mutableMapOf()
    val rumorMap: MutableMap<EventId, EventId> = mutableMapOf()

    private val _messageSyncState = MutableStateFlow(MessageSyncState())
    val messageSyncState = _messageSyncState.asStateFlow()

    fun updateSyncState(update: (MessageSyncState) -> MessageSyncState) {
        _messageSyncState.update(update)
    }

    suspend fun getUserMessages(msgRelayList: Event) {
        try {
            val author =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val relays = nip17ExtractRelayList(msgRelayList)

            // Ensure relay connections
            relays.forEach { relay ->
                client?.addRelay(relay)
                client?.connectRelay(relay)
            }

            // Construct a filter for gift wrap events
            val filter = Filter().kind(Kind.fromStd(KindStandard.GIFT_WRAP)).pubkey(author)
            val target = mutableMapOf<RelayUrl, List<Filter>>()
            relays.forEach { relay ->
                target[relay] = listOf(filter)
            }

            client?.subscribe(
                target = ReqTarget.manual(target),
                id = "gift-wraps"
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user messages: ${e.message}", e)
        }
    }

    suspend fun extractRumor(event: Event): UnsignedEvent? {
        try {
            // Gift wrap must have at least one 'p' tag
            if (event.tags().publicKeys().isEmpty()) {
                println("No recipient tags found.")
                return null
            }

            // Event must be a gift wrap
            if (event.kind().asStd().let { it != KindStandard.GIFT_WRAP }) {
                println("Event is not a gift wrap.")
                return null
            }

            // Check if the rumor is already cached
            val cachedRumor = getCachedRumor(event.id())
            if (cachedRumor != null) return cachedRumor

            // Decrypt the gift wrap event
            val seal = signer.nip44DecryptAsync(event.author(), event.content())
            val sealEvent = Event.fromJson(seal)

            // Verify seal event
            if (!sealEvent.verify()) {
                println("Failed to verify seal event.")
                return null
            }

            // Decrypt the rumor
            val rumor = signer.nip44DecryptAsync(sealEvent.author(), sealEvent.content())
            val unsignedEvent = UnsignedEvent.fromJson(rumor).ensureId()

            // Ensure the rumor author matches the seal
            if (unsignedEvent.author() != sealEvent.author()) {
                println("Author mismatch.")
                return null
            }

            // Cache the rumor for later use
            setCachedRumor(event.id(), unsignedEvent)

            return unsignedEvent
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to unwrap gift ${event.id().toHex()}: ${e.message}")
            return null
        }
    }

    private suspend fun getCachedRumor(giftId: EventId): UnsignedEvent? {
        try {
            val filter = Filter().identifier(giftId.toHex())
            val event = client?.database()?.query(filter)?.first()

            return event?.content()?.let { UnsignedEvent.fromJson(it).ensureId() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get cached rumor: ${e.message}", e)
        }
    }

    private suspend fun setCachedRumor(giftId: EventId, rumor: UnsignedEvent) {
        try {
            // Construct reference tags
            val tags = listOf(
                Tag.identifier(giftId.toHex()),
                Tag.publicKey(rumor.author()),
                Tag.custom("r", listOf(rumor.roomId().toString())),
                Tag.custom("k", listOf("14"))
            )

            // Set event kind
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)

            // Construct event
            val event = EventBuilder(kind, rumor.asJson())
                .tags(tags)
                .finalizeAsync(Keys.generate())

            client?.database()?.saveEvent(event)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to set cached rumor: ${e.message}")
        }
    }

    suspend fun getChatRooms(): Set<Room>? {
        try {
            val userPubkey =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)
            val kTag = SingleLetterTag.lowercase(Alphabet.K)

            // Get all DM events
            val filter = Filter().kind(kind).customTags(kTag, listOf("14", "dm"))
            val events = client?.database()?.query(filter)?.toVec() ?: return null

            // Collect rooms
            val roomsMap: MutableMap<Long, Room> = mutableMapOf()

            events
                .map { UnsignedEvent.fromJson(it.content()) }
                .filter { it.tags().publicKeys().isNotEmpty() }
                .forEach { rumor ->
                    val id = rumor.roomId()
                    val isFromMe = rumor.author() == userPubkey
                    val existing = roomsMap[id]
                    val createdAt = rumor.createdAt()

                    // If the room is new or the current rumor is newer than the existing one
                    if (existing == null || createdAt.asSecs() > existing.createdAt.asSecs()) {
                        // A room is "Ongoing" if it was already marked as such or if the current rumor is from the user
                        val isOngoing = (existing?.kind == RoomKind.Ongoing) || isFromMe
                        val room = Room.new(rumor = rumor, userPubkey = userPubkey, id = id)
                        roomsMap[id] = if (isOngoing) room.copy(kind = RoomKind.Ongoing) else room
                    } else if (isFromMe && existing.kind != RoomKind.Ongoing) {
                        // If it's an older rumor but sent by the user, mark the room as Ongoing
                        roomsMap[id] = existing.copy(kind = RoomKind.Ongoing)
                    }
                }

            return roomsMap.values.sortedByDescending { it.createdAt.asSecs() }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to get chat rooms: ${e.message}")
            return null
        }
    }

    suspend fun getChatRoomMessages(roomId: Long): List<UnsignedEvent> {
        try {
            val kind = Kind.fromStd(KindStandard.APPLICATION_SPECIFIC_DATA)
            val filter = Filter().kind(kind).reference(roomId.toString())
            val events = client?.database()?.query(filter)

            // Merge the events
            return events
                ?.toVec()
                ?.map { UnsignedEvent.fromJson(it.content()).ensureId() }
                // Filter out events without public keys (receivers)
                ?.filter { it.tags().publicKeys().isNotEmpty() }
                ?.sortedByDescending { it.createdAt().asSecs() } ?: emptyList()
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get chat room messages: ${e.message}", e)
        }
    }

    suspend fun chatRoomConnect(members: List<PublicKey>) {
        try {
            members.forEach { member ->
                val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
                val filter = Filter().kind(kind).author(member).limit(1u)

                val stream = client?.streamEvents(
                    target = ReqTarget.auto(listOf(filter)),
                    id = null,
                    timeout = Duration.parse("3s"),
                    policy = ReqExitPolicy.ExitOnEose
                )

                stream?.next()?.let { res ->
                    val event = res.event ?: return@let
                    connectMsgRelays(event)
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch relays: ${e.message}", e)
        }
    }

    suspend fun connectMsgRelays(event: Event) {
        try {
            val urls = nip17ExtractRelayList(event)
            for (url in urls) {
                client?.addRelay(url, RelayCapabilities.gossip())
                client?.connectRelay(url)
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to connect to relays: ${e.message}", e)
        }
    }

    suspend fun sendMessage(
        to: Set<PublicKey>,
        content: String,
        subject: String? = null,
        replies: List<EventId> = emptyList(),
        onRumorCreated: ((UnsignedEvent) -> Unit)? = null,
    ) {
        try {
            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val tags = mutableListOf<Tag>()

            // Add a subject tag if provided
            if (subject != null) {
                tags.add(Tag.custom("subject", listOf(subject)))
            }

            // Add event tags for replies
            if (replies.isNotEmpty()) {
                replies.forEach { replyId ->
                    tags.add(Tag.event(replyId))
                }
            }

            // Add public key tags for each recipient
            to.forEach { pubkey ->
                tags.add(Tag.publicKey(pubkey))
            }

            for (receiver in setOf(currentUser) + to) {
                // Construct the rumor event
                // NEVER SIGN this event with the current user signer
                val rumor = EventBuilder(Kind.fromStd(KindStandard.PRIVATE_DIRECT_MESSAGE), content)
                    .tags(tags)
                    .finalizeUnsigned(currentUser)
                    .ensureId()

                // Emit the rumor to the chat screen
                if (receiver == currentUser) {
                    onRumorCreated?.invoke(rumor)
                }

                // Construct the gift wrap event
                val gift = nip59MakeGiftWrapAsync(
                    signer = signer,
                    receiverPubkey = receiver,
                    rumor = rumor,
                    extraTags = listOf(
                        Tag.custom("k", listOf("14"))
                    )
                )

                // Send the event to receiver's NIP-17 relays
                val output = client?.sendEvent(
                    event = gift,
                    target = SendEventTarget.toNip17(),
                    ackPolicy = AckPolicy.none(),
                    authenticationTimeout = Duration.parse("2s")
                )

                if (output != null) {
                    // Keep track of sent events
                    sentEvents[output.id] = emptyList()

                    // Keep track of rumor IDs
                    val id = rumor.id() ?: throw IllegalStateException("Rumor ID is null")
                    rumorMap[id] = output.id

                    // Collect failed outputs
                    output.failed.forEach { (relayUrl, reason) ->
                        println("Failed to send event to relay $relayUrl: $reason")
                    }
                }
            }
        } catch (e: Exception) {
            throw IllegalStateException("Failed to send message: ${e.message}", e)
        }
    }
}
