package su.reya.coop

import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientNotification
import rust.nostr.sdk.Contact
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.GossipConfig
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.MetadataRecord
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.RelayCapabilities
import rust.nostr.sdk.RelayMetadata
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.ReqTarget
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Timestamp

class Nostr {
    var client: Client? = null
        private set
    var signer: NostrSigner? = null
        private set

    suspend fun init(dbPath: String) {
        val lmdb = NostrDatabase.lmdb(dbPath)
        val gossip = NostrGossip.inMemory()

        client =
            ClientBuilder()
                .database(lmdb)
                .gossip(gossip)
                .gossipConfig(GossipConfig().noBackgroundRefresh())
                .maxRelays(20u)
                .verifySubscriptions(false)
                .automaticAuthentication(false)
                .build()
    }

    suspend fun connect() {
        client?.addRelay(
            url = RelayUrl.parse("wss://relay.damus.io"),
            capabilities = RelayCapabilities.none()
        )
        client?.addRelay(
            url = RelayUrl.parse("wss://relay.primal.net"),
            capabilities = RelayCapabilities.none()
        )
        client?.addRelay(
            url = RelayUrl.parse("wss://user.kindpag.es"),
            capabilities = RelayCapabilities.none()
        )
        client?.addRelay(
            url = RelayUrl.parse("https://indexer.coracle.social"),
            capabilities = RelayCapabilities.gossip()
        )
        client?.connect()
    }

    suspend fun disconnect() {
        client?.shutdown()
    }

    suspend fun setKeySigner(keys: Keys) {
        signer = NostrSigner.keys(keys)
        getUserMetadata()
    }

    suspend fun setRemoteSigner(remote: NostrConnect) {
        signer = NostrSigner.nostrConnect(remote)
        getUserMetadata()
    }

    suspend fun getUserMetadata() {
        val userPubkey = signer?.getPublicKey() ?: return

        val filter = Filter().author(userPubkey).limit(10u).kinds(
            listOf(
                Kind.fromStd(KindStandard.METADATA),
                Kind.fromStd(KindStandard.CONTACT_LIST),
                Kind.fromStd(KindStandard.INBOX_RELAYS)
            )
        )

        val target = ReqTarget.auto(listOf(filter))
        val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)

        client?.subscribe(target = target, id = "user-metadata", closeOn = opts)
    }

    suspend fun handleNotifications() {
        val now = Timestamp.now()
        val notifications = client?.notifications()

        while (true) {
            val notification = notifications?.next() ?: break

            when (notification) {
                is ClientNotification.Message -> {
                    // TODO: Handle message
                }

                is ClientNotification.NewEvent -> {
                    // TODO: Handle new event
                }

                is ClientNotification.Shutdown -> {
                    break
                }
            }
        }
    }

    suspend fun getDefaultRelayList(): Map<RelayUrl, RelayMetadata> {
        // Construct a list of relays
        val relayList = mapOf<RelayUrl, RelayMetadata>(
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
                    if (metadata == RelayMetadata.READ) RelayCapabilities.read()
                    else if (metadata == RelayMetadata.WRITE) RelayCapabilities.write()
                    else RelayCapabilities.none()
            )
            client?.connectRelay(relay)
        }

        return relayList
    }

    suspend fun getMsgRelayList(): List<RelayUrl> {
        // Construct a list of messaging relays
        val msgRelayList = listOf(
            RelayUrl.parse("wss://relay.0xchat.com"),
            RelayUrl.parse("wss://nip17.com"),
        )

        // Ensure all relays are added and connected
        msgRelayList.forEach { relay ->
            client?.addRelay(relay, RelayCapabilities.none())
            client?.connectRelay(relay)
        }

        return msgRelayList
    }

    suspend fun createIdentity(keys: Keys, name: String, bio: String, picture: String?) {
        // Set signer
        signer = NostrSigner.keys(keys)

        // Send relay list event
        val relayList = getDefaultRelayList()
        val relayListEvent = EventBuilder.relayList(relayList).sign(signer!!);
        client?.sendEvent(relayListEvent)

        // Send messaging relay list event
        val msgRelayList = getMsgRelayList()
        val msgRelayListEvent = EventBuilder.nip17RelayList(msgRelayList).sign(signer!!)
        client?.sendEventNoWait(msgRelayListEvent)

        // Send metadata event
        val metadata =
            Metadata.fromRecord(MetadataRecord(name = name, about = bio, picture = picture))
        val metadataEvent = EventBuilder.metadata(metadata).sign(signer!!)
        client?.sendEventNoWait(metadataEvent)

        // Send contact list event
        val defaultContact =
            listOf(Contact(publicKey = PublicKey.parse("npub1j3rz3ndl902lya6ywxvy5c983lxs8mpukqnx4pa4lt5wrykwl5ys7wpw3x")))
        val contactListEvent = EventBuilder.contactList(defaultContact).sign(signer!!)
        client?.sendEventNoWait(contactListEvent)
    }
}
