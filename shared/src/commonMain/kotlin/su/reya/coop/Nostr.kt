package su.reya.coop

import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.Event
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Filter
import rust.nostr.sdk.HandleNotification
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Kind
import rust.nostr.sdk.KindStandard
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.MetadataRecord
import rust.nostr.sdk.NostrConnect
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.RelayMessage
import rust.nostr.sdk.RelayUrl
import rust.nostr.sdk.ReqExitPolicy
import rust.nostr.sdk.SubscribeAutoCloseOptions
import rust.nostr.sdk.Timestamp

class Nostr {
    var client: Client? = null
        private set
    var signer: NostrSigner? = null
        private set

    fun init(dbPath: String) {
        val lmdb = NostrDatabase.lmdb(dbPath)
        val gossip = NostrGossip.inMemory()
        val opts = ClientOptions().automaticAuthentication(false)

        client = ClientBuilder().database(lmdb).gossip(gossip).opts(opts).build()
    }

    suspend fun connect() {
        this.client?.addRelay(RelayUrl.parse("wss://relay.damus.io"))
        this.client?.addRelay(RelayUrl.parse("wss://relay.primal.net"))
        this.client?.addRelay(RelayUrl.parse("wss://user.kindpag.es"))
        this.client?.connect()
    }

    suspend fun disconnect() {
        this.client?.shutdown()
    }

    suspend fun setKeySigner(keys: Keys) {
        signer = NostrSigner.keys(keys)
        this.getMetadata()
    }

    suspend fun setRemoteSigner(signer: NostrConnect) {
        this.signer = NostrSigner.nostrConnect(signer)
        this.getMetadata()
    }

    suspend fun getMetadata() {
        val currentUserPubKey = this.signer?.getPublicKey() ?: return
        val opts = SubscribeAutoCloseOptions().exitPolicy(ReqExitPolicy.ExitOnEose)
        val filter = Filter().author(currentUserPubKey).limit(10u).kinds(
            listOf(
                Kind.fromStd(KindStandard.METADATA),
                Kind.fromStd(KindStandard.CONTACT_LIST),
                Kind.fromStd(KindStandard.INBOX_RELAYS)
            )
        )

        this.client?.subscribe(filter, opts)
    }

    suspend fun handleNotifications() {
        val now = Timestamp.now()

        this.client?.handleNotifications(object : HandleNotification {
            override suspend fun handle(relayUrl: RelayUrl, subscriptionId: String, event: Event) {
                TODO("Not yet implemented")
            }

            override suspend fun handleMsg(
                relayUrl: RelayUrl,
                msg: RelayMessage
            ) {
                TODO("Not yet implemented")
            }
        })
    }

    suspend fun createIdentity(keys: Keys, name: String, bio: String, picture: String?) {
        // Set signer
        signer = NostrSigner.keys(keys)

        // Construct metadata records
        val records = MetadataRecord(
            name = name,
            displayName = name,
            about = bio,
            picture = picture
        )

        // Construct a nostr event and sign it
        val metadata = Metadata.fromRecord(records)
        val builder = EventBuilder.metadata(metadata).build(keys.publicKey())
        val event = this.signer?.signEvent(builder) ?: return

        // Send event to relays
        this.client?.sendEvent(event)
    }
}
