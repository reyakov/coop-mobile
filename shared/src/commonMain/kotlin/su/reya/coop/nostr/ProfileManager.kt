package su.reya.coop.nostr

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.get
import io.ktor.client.statement.HttpResponse
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import rust.nostr.sdk.AckPolicy
import rust.nostr.sdk.Client
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.MetadataRecord
import rust.nostr.sdk.Nip05Address
import rust.nostr.sdk.Nip05Profile
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Timestamp
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration

class ProfileManager(private val nostr: Nostr) {
    private val client: Client? get() = nostr.client
    private val signer: UniversalSigner get() = nostr.signer
    private val httpClient by lazy { HttpClient() }

    private val _metadataUpdates =
        MutableSharedFlow<Pair<PublicKey, Metadata>>(
            replay = 0,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
    val metadataUpdates = _metadataUpdates.asSharedFlow()

    private val _contactListUpdates =
        MutableSharedFlow<List<PublicKey>>(
            replay = 0,
            extraBufferCapacity = 100,
            onBufferOverflow = BufferOverflow.SUSPEND
        )
    val contactListUpdates = _contactListUpdates.asSharedFlow()

    suspend fun emitMetadataUpdate(pubkey: PublicKey, metadata: Metadata) {
        _metadataUpdates.emit(pubkey to metadata)
    }

    suspend fun emitContactListUpdate(contacts: List<PublicKey>) {
        _contactListUpdates.emit(contacts)
    }

    suspend fun getUserMetadata() {
        try {
            val author =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            // Get the latest metadata event
            val metadataFilter =
                Filter().kind(Kind.fromStd(KindStandard.METADATA)).author(author).limit(1u)

            // Get the latest contact list event
            val contactFilter =
                Filter().kind(Kind.fromStd(KindStandard.CONTACT_LIST)).author(author).limit(1u)

            // Get the latest messaging relay list event
            val msgRelayFilter =
                Filter().kind(Kind.fromStd(KindStandard.INBOX_RELAYS)).author(author).limit(1u)

            // Construct a target that includes all filters
            val target = ReqTarget.auto(listOf(metadataFilter, contactFilter, msgRelayFilter))
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            client?.subscribe(target = target, id = "user-metadata", closeOn = opts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch user metadata: ${e.message}", e)
        }
    }

    suspend fun syncMutualContacts(pubkeys: List<PublicKey>) {
        try {
            val kind = Kind.fromStd(KindStandard.CONTACT_LIST)
            val filter = Filter().kind(kind).authors(pubkeys).limit(pubkeys.size.toULong())
            val relays = RelayManager.BOOTSTRAP_RELAYS.map { RelayUrl.parse(it) }

            client?.sync(filter, relays)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to sync mutual contacts: ${e.message}")
        }
    }

    suspend fun createIdentity(keys: Keys, name: String, bio: String?, picture: String?) {
        // Send relay list event
        val relayList = nostr.relays.getDefaultRelayList()
        val relayListEvent = EventBuilder.relayList(relayList).finalizeAsync(keys)

        client?.sendEvent(
            event = relayListEvent,
            target = SendEventTarget.broadcast(),
            ackPolicy = AckPolicy.all(),
            okTimeout = Duration.parse("3s")
        )

        // Send messaging relay list event
        val msgRelayList = nostr.relays.getDefaultMsgRelayList()
        val msgRelayListEvent = EventBuilder.nip17RelayList(msgRelayList).finalizeAsync(keys)

        client?.sendEvent(
            event = msgRelayListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        // Send metadata event
        val metadata =
            Metadata.fromRecord(MetadataRecord(displayName = name, about = bio, picture = picture))
        val metadataEvent = EventBuilder.metadata(metadata).finalizeAsync(keys)

        client?.sendEvent(
            event = metadataEvent,
            target = SendEventTarget.broadcast(),
            ackPolicy = AckPolicy.none()
        )

        // Send contact list event
        val defaultContact =
            Contact(PublicKey.parse("npub1j3rz3ndl902lya6ywxvy5c983lxs8mpukqnx4pa4lt5wrykwl5ys7wpw3x"))
        val contactListEvent = EventBuilder.contactList(listOf(defaultContact)).finalizeAsync(keys)

        client?.sendEvent(
            event = contactListEvent,
            target = SendEventTarget.toNip65(),
            ackPolicy = AckPolicy.none()
        )

        nostr.setSigner(keys)
    }

    suspend fun updateProfile(
        name: String? = null,
        bio: String? = null,
        picture: String? = null
    ): Metadata {
        try {
            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            // Get the latest metadata event
            val record = getLatestMetadata(currentUser)?.asRecord() ?: MetadataRecord()

            // Build a new metadata based on old records
            val newMetadata = Metadata.fromRecord(
                record.copy(
                    displayName = name ?: record.displayName,
                    about = bio ?: record.about,
                    picture = picture ?: record.picture
                )
            )

            // Send the new metadata event
            val event = EventBuilder.metadata(newMetadata).finalizeAsync(signer)
            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none()
            )

            return newMetadata
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to update identity: ${e.message}", e)
        }
    }

    private suspend fun getLatestMetadata(pubkey: PublicKey): Metadata? {
        return try {
            val kind = Kind.fromStd(KindStandard.METADATA)
            val filter = Filter().kind(kind).author(pubkey).limit(1u)
            val event = client?.database()?.query(filter)?.first() ?: return null

            Metadata.fromJson(event.content())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to get latest metadata: ${e.message}")
            null
        }
    }

    suspend fun getAllCacheMetadata(): Map<PublicKey, Metadata> {
        try {
            val filter = Filter().kind(Kind.fromStd(KindStandard.METADATA)).limit(100u)
            val events = client?.database()?.query(filter)
            val results = mutableMapOf<PublicKey, Metadata>()

            events?.toVec()?.forEach { event ->
                try {
                    val metadata = Metadata.fromJson(event.content())
                    results[event.author()] = metadata
                } catch (e: Exception) {
                    println("Failed to parse metadata: $e")
                }
            }

            return results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Failed to get all cache metadata: ${e.message}")
            return emptyMap()
        }
    }

    suspend fun fetchMetadataBatch(keys: List<PublicKey>) {
        try {
            val limit = keys.size.toULong() * 2u
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            // Construct a filter for metadata events
            val filter = Filter()
                .kind(Kind.fromStd(KindStandard.METADATA))
                .authors(keys)
                .limit(limit)

            // Construct request target
            val target = mutableMapOf<RelayUrl, List<Filter>>()
            RelayManager.BOOTSTRAP_RELAYS.forEach { relay ->
                target[RelayUrl.parse(relay)] = listOf(filter)
            }

            client?.subscribe(target = ReqTarget.manual(target), closeOn = opts)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch metadata batch: ${e.message}", e)
        }
    }

    suspend fun setContactList(contacts: List<PublicKey>) {
        try {
            val contactList = contacts.map { Contact(it) }
            val event = EventBuilder.contactList(contactList).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none(),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set contact list: ${e.message}", e)
        }
    }

    suspend fun profileFromAddress(client: HttpClient, address: Nip05Address): Nip05Profile {
        try {
            val response: HttpResponse = client.get(address.url())
            val bodyString: String = response.body()

            return Nip05Profile.fromJson(address, bodyString)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch profile from address: ${e.message}", e)
        }
    }

    suspend fun searchByAddress(query: String): PublicKey {
        try {
            val address = Nip05Address.parse(query)
            val profile = profileFromAddress(httpClient, address)

            return profile.publicKey()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to search address: ${e.message}", e)
        }
    }

    suspend fun searchByNostr(query: String): List<PublicKey> {
        try {
            // Add search relay
            val searchRelay = RelayUrl.parse("wss://antiprimal.net")
            if (client?.relay(searchRelay) == null) {
                client?.addRelay(url = searchRelay, capabilities = RelayCapabilities.read())
                client?.connectRelay(searchRelay)
            }

            val kinds = listOf(Kind.fromStd(KindStandard.METADATA))
            val filter = Filter().kinds(kinds).search(query).limit(10u)
            val target = ReqTarget.manual(mapOf(searchRelay to listOf(filter)))

            val stream = client?.streamEvents(
                target = target,
                id = "search",
                timeout = Duration.parse("3s"),
                policy = ReqExitPolicy.ExitOnEose
            )

            // Collect the results
            val results = mutableListOf<PublicKey>()

            // Keep searching until the stream is closed or timeout
            stream?.next()?.let { event ->
                val event = event.event ?: return@let
                results.add(event.author())
            }

            return results
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to search nostr: ${e.message}", e)
        }
    }

    suspend fun verifyActivity(pubkey: PublicKey): Timestamp? {
        try {
            val filter = Filter().author(pubkey).limit(3u)
            val target = mutableMapOf<RelayUrl, List<Filter>>()
            RelayManager.BOOTSTRAP_RELAYS.forEach { relay ->
                target[RelayUrl.parse(relay)] = listOf(filter)
            }

            val events = client?.fetchEvents(
                target = ReqTarget.manual(target),
                timeout = Duration.parse("3s")
            )

            return events?.first()?.createdAt()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get latest activity: ${e.message}", e)
        }
    }

    suspend fun verifyContact(pubkey: PublicKey): Boolean {
        try {
            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.CONTACT_LIST)
            val filter = Filter().kind(kind).author(currentUser).limit(1u)

            val events = client?.database()?.query(filter)
            val pubkeys = events?.first()?.tags()?.publicKeys() ?: listOf()

            return pubkeys.contains(pubkey)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get mutual contacts: ${e.message}", e)
        }
    }

    suspend fun mutualContacts(pubkey: PublicKey): Set<PublicKey> {
        try {
            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.CONTACT_LIST)
            val filter = Filter().kind(kind).pubkey(pubkey).limit(1u)

            val events = client?.database()?.query(filter)
            val contacts = mutableSetOf<PublicKey>()

            events?.toVec()?.filter { it.author() != currentUser }?.forEach { event ->
                contacts.add(event.author())
            }

            return contacts.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get mutual contacts: ${e.message}", e)
        }
    }
}