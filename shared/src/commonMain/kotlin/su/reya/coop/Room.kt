package su.reya.coop

import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.TagKind
import rust.nostr.sdk.Timestamp
import kotlin.time.Clock
import kotlin.time.Instant

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
    val kind: RoomKind = RoomKind.default(),
    val lastMessage: String? = null
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
            // Also remove the user's public key from the list, current user is always a member
            val pubkeys: MutableSet<PublicKey> = mutableSetOf()
            pubkeys.add(rumor.author())
            pubkeys.addAll(rumor.tags().publicKeys())
            pubkeys.remove(userPubkey)

            // Create a new Room instance
            return Room(
                id = id,
                createdAt = createdAt,
                subject = subject,
                members = pubkeys as Set<PublicKey>,
                lastMessage = rumor.content()
            )
        }
    }

    fun setKind(kind: RoomKind): Room {
        return this.copy(kind = kind)
    }

    fun setCreatedAt(createdAt: Timestamp): Room {
        return this.copy(createdAt = createdAt)
    }

    fun setSubject(subject: String?): Room {
        return this.copy(subject = subject)
    }

    fun setLastMessage(message: String?): Room {
        return this.copy(lastMessage = message)
    }

    fun isGroup(): Boolean {
        return members.size > 1
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

fun Timestamp.ago(): String {
    val SECONDS_IN_MINUTE = 60L
    val MINUTES_IN_HOUR = 60L
    val HOURS_IN_DAY = 24L
    val DAYS_IN_MONTH = 30L

    val inputInstant = Instant.fromEpochSeconds(this.asSecs().toLong())
    val now = Clock.System.now()
    val duration = now - inputInstant

    return when {
        duration.inWholeSeconds < SECONDS_IN_MINUTE -> "now"
        duration.inWholeMinutes < MINUTES_IN_HOUR -> "${duration.inWholeMinutes}m"
        duration.inWholeHours < HOURS_IN_DAY -> "${duration.inWholeHours}h"
        duration.inWholeDays < DAYS_IN_MONTH -> "${duration.inWholeDays}d"
        else -> {
            val localDateTime = inputInstant.toLocalDateTime(TimeZone.currentSystemDefault())
            val month =
                localDateTime.month.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
            val day = localDateTime.dayOfMonth.toString().padStart(2, '0')
            "$month $day"
        }
    }
}

fun Timestamp.humanReadable(): String {
    val timeZone = TimeZone.currentSystemDefault()
    val inputInstant = Instant.fromEpochSeconds(this.asSecs().toLong())
    val inputDateTime = inputInstant.toLocalDateTime(timeZone)
    val inputDate = inputDateTime.date

    val now = Clock.System.now()
    val today = now.toLocalDateTime(timeZone).date
    val yesterday = today.minus(1, DateTimeUnit.DAY)

    val hour = inputDateTime.hour
    val minute = inputDateTime.minute.toString().padStart(2, '0')
    val amPm = if (hour < 12) "AM" else "PM"
    val hour12 = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    val timeFormat = "$hour12:$minute $amPm"

    return when (inputDate) {
        today -> "Today at $timeFormat"
        yesterday -> "Yesterday at $timeFormat"
        else -> {
            val day = inputDateTime.day.toString().padStart(2, '0')
            val month = inputDateTime.month.number.toString().padStart(2, '0')
            val year = inputDateTime.year.toString().takeLast(2)
            "$day/$month/$year, $timeFormat"
        }
    }
}
