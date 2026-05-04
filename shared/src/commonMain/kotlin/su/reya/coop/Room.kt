package su.reya.coop

import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.TagKind
import rust.nostr.sdk.Timestamp

enum class RoomKind {
    Ongoing,
    Request;

    companion object {
        fun default(): RoomKind = Request
    }
}

data class Room(
    val id: Long,
    val createdAt: Timestamp,
    val subject: String?,
    val members: Set<PublicKey>,
    val kind: RoomKind = RoomKind.default()
) : Comparable<Room> {
    override fun hashCode(): Int = id.hashCode()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is Room) return false
        return id == other.id
    }

    override fun compareTo(other: Room): Int {
        return this.createdAt.asSecs().compareTo(other.createdAt.asSecs())
    }

    companion object {
        fun new(rumor: Event, userPubkey: PublicKey): Room {
            val id = rumor.roomId()
            val createdAt = rumor.createdAt()
            val subject = rumor.tags().find(TagKind.Subject)?.content()

            // Collect the author's public key and all public keys from tags
            // Also remove the user's public key from the list
            val pubkeys: MutableSet<PublicKey> = mutableSetOf()
            pubkeys.add(rumor.author())
            pubkeys.addAll(rumor.tags().publicKeys())
            pubkeys.remove(userPubkey)

            // Create a new Room instance
            return Room(
                id = id,
                createdAt = createdAt,
                subject = subject,
                members = pubkeys as Set<PublicKey>
            )
        }
    }

    fun kind(kind: RoomKind): Room {
        return this.copy(kind = kind)
    }
}

fun Event.roomId(): Long {
    // Collect the author's public key and all public keys from tags
    val pubkeys: MutableList<PublicKey> = mutableListOf()
    pubkeys.add(this.author())
    pubkeys.addAll(this.tags().publicKeys())

    // Sort and hash the list of public keys
    val sortedUniqueKeys = pubkeys
        .distinctBy { it.toBech32() }
        .sortedBy { it.toBech32() }

    return sortedUniqueKeys.hashCode().toLong()
}
