package su.reya.coop.nostr

import rust.nostr.sdk.AckPolicy
import rust.nostr.sdk.Client
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayStatus
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SendEventTarget
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.extractRelayList
import rust.nostr.sdk.nip17ExtractRelayList
import kotlin.time.Duration

class RelayManager(private val nostr: Nostr) {
    private val client: Client? get() = nostr.client
    private val signer: UniversalSigner get() = nostr.signer

    suspend fun connectBootstrapRelays() {
        NostrManager.BOOTSTRAP_RELAYS.forEach { url ->
            client?.addRelay(RelayUrl.parse(url))
        }
        NostrManager.INDEXER_RELAY.forEach { url ->
            client?.addRelay(
                url = RelayUrl.parse(url),
                capabilities = RelayCapabilities.gossip()
            )
        }
        // Connect to all bootstrap relays
        client?.connect()
    }

    suspend fun reconnect() {
        NostrManager.ALL_RELAYS.forEach { url ->
            try {
                client?.relay(RelayUrl.parse(url)).let { relay ->
                    if (relay != null) {
                        if (relay.status() != RelayStatus.CONNECTED) {
                            relay.connect()
                        }
                    }
                }
            } catch (e: Exception) {
                println("Failed to reconnect relay: ${e.message}")
            }
        }
    }

    suspend fun disconnect() {
        NostrManager.ALL_RELAYS.forEach { url ->
            try {
                client?.disconnectRelay(RelayUrl.parse(url))
            } catch (e: Exception) {
                println("Failed to disconnect relay: ${e.message}")
            }
        }
    }

    internal suspend fun getDefaultRelayList(): Map<RelayUrl, RelayMetadata> {
        // Construct a list of relays
        val relayList = mapOf(
            RelayUrl.parse("wss://relay.damus.io") to RelayMetadata.READ,
            RelayUrl.parse("wss://relay.primal.net") to RelayMetadata.READ,
            RelayUrl.parse("wss://relay.nostr.net") to RelayMetadata.WRITE,
            RelayUrl.parse("wss://nostr.superfriends.online") to RelayMetadata.WRITE
        )

        // Ensure all relays are added and connected
        relayList.forEach { (relay, metadata) ->
            client?.addRelay(
                url = relay,
                capabilities =
                    when (metadata) {
                        RelayMetadata.READ -> RelayCapabilities.read()
                        RelayMetadata.WRITE -> RelayCapabilities.write()
                    }
            )
            client?.connectRelay(relay)
        }

        return relayList
    }

    internal suspend fun getDefaultMsgRelayList(): List<RelayUrl> {
        // Construct a list of messaging relays
        val msgRelayList = listOf(
            RelayUrl.parse("wss://auth.nostr1.com"),
            RelayUrl.parse("wss://nip17.com"),
        )

        // Ensure all relays are added and connected
        msgRelayList.forEach { relay ->
            client?.addRelay(relay, RelayCapabilities.none())
            client?.connectRelay(relay)
        }

        return msgRelayList
    }

    suspend fun setMsgRelays(urls: List<RelayUrl>) {
        try {
            val event = EventBuilder.nip17RelayList(urls).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.toNip65(),
                ackPolicy = AckPolicy.none(),
            )

            val currentUser =
                signer.getPublicKeyAsync() ?: throw IllegalStateException("User not signed in")

            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(currentUser).limit(1u)

            val target = ReqTarget.auto(listOf(filter))
            val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

            client?.subscribe(target = target, closeOn = opts)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set msg relays: ${e.message}", e)
        }
    }

    suspend fun getMsgRelays(publicKey: PublicKey): List<RelayUrl> {
        try {
            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val events = client?.database()?.query(filter)
            val event = events?.first() ?: return emptyList()

            return nip17ExtractRelayList(event)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get msg relays: ${e.message}", e)
        }
    }

    suspend fun fetchMsgRelays(publicKey: PublicKey): List<RelayUrl> {
        try {
            val kind = Kind.fromStd(KindStandard.INBOX_RELAYS)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val target = ReqTarget.auto(listOf(filter))
            val events = client?.fetchEvents(target, timeout = Duration.parse("3s"))

            return nip17ExtractRelayList(events?.toVec()?.firstOrNull() ?: return emptyList())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to fetch msg relays: ${e.message}", e)
        }
    }

    suspend fun getRelayList(publicKey: PublicKey): Map<RelayUrl, RelayMetadata?> {
        try {
            val kind = Kind.fromStd(KindStandard.RELAY_LIST)
            val filter = Filter().kind(kind).author(publicKey).limit(1u)
            val events = client?.database()?.query(filter)

            return extractRelayList(events?.toVec()?.firstOrNull() ?: return emptyMap())
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get relay list: ${e.message}", e)
        }
    }

    suspend fun setRelaylist(relays: Map<RelayUrl, RelayMetadata?>) {
        try {
            val event = EventBuilder.relayList(relays).finalizeAsync(signer)

            client?.sendEvent(
                event = event,
                target = SendEventTarget.broadcast(),
                ackPolicy = AckPolicy.none(),
            )
        } catch (e: Exception) {
            throw IllegalStateException("Failed to set msg relays: ${e.message}", e)
        }
    }
}