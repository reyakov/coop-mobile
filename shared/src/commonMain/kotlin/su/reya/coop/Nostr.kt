package su.reya.coop

import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Keys
import rust.nostr.sdk.Metadata
import rust.nostr.sdk.MetadataRecord
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.RelayUrl

class Nostr {
    var client: Client? = null
        private set
    var signer: NostrSigner? = null
        private set
    var deviceSigner: NostrSigner? = null
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

    suspend fun createIdentity(keys: Keys, name: String, bio: String, picture: String?) {
        signer = NostrSigner.keys(keys)

        // Construct metadata
        val metadata = Metadata.fromRecord(
            MetadataRecord(
                name = name,
                displayName = name,
                about = bio,
                picture = picture
            )
        )

        // Construct event and sign it
        val builder = EventBuilder.metadata(metadata).build(keys.publicKey())
        val event = this.signer?.signEvent(builder) ?: return

        // Send event to relays
        this.client?.sendEvent(event)
    }
}
