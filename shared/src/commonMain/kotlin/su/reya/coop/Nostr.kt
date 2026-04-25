package su.reya.coop

import rust.nostr.sdk.Client
import rust.nostr.sdk.ClientBuilder
import rust.nostr.sdk.ClientOptions
import rust.nostr.sdk.NostrDatabase
import rust.nostr.sdk.NostrGossip
import rust.nostr.sdk.RelayUrl

class Nostr {
    var client: Client? = null
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
}
