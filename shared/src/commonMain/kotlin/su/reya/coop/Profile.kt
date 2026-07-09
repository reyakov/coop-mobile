package su.reya.coop

import rust.nostr.sdk.Metadata
import rust.nostr.sdk.PublicKey

data class Profile(
    val publicKey: PublicKey,
    val metadata: Metadata
) {
    private val record by lazy { metadata.asRecord() }

    val name: String
        get() = record.displayName?.sanitizeName() ?: record.name ?: publicKey.short()

    val picture: String?
        get() = record.picture

    val shortPublicKey: String
        get() = publicKey.short()
}