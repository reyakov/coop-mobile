package su.reya.coop.blossom

import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.Kind
import rust.nostr.sdk.Tag
import rust.nostr.sdk.Timestamp

/**
 * Represents the authorization data for accessing a Blossom server.
 */
data class BlossomAuthorization(
    /**
     * A human readable string explaining to the user what the events intended use is
     */
    val content: String,
    /**
     * A UNIX timestamp (in seconds) indicating when the authorization should be expired
     */
    val expiration: Timestamp,
    /**
     * The type of action authorized by the user
     */
    val action: BlossomAuthorizationVerb,
    /**
     * The scope of the authorization
     */
    val scope: BlossomAuthorizationScope,
)

/**
 * The scope of a Blossom authorization event
 */
sealed class BlossomAuthorizationScope {
    /**
     * Authorizes access to blobs with the given SHA256 hashes.
     */
    data class BlobSha256Hashes(val hashes: List<String>) : BlossomAuthorizationScope()

    /**
     * Authorizes access to the given server URL.
     */
    data class ServerUrl(val url: String) : BlossomAuthorizationScope()

    fun toTags(): List<Tag> {
        return when (this) {
            is BlobSha256Hashes -> hashes.map { hash ->
                // "x" tag for blob hash
                Tag.parse(listOf("x", hash))
            }

            is ServerUrl -> listOf(
                // "server" tag for server URL
                Tag.parse(listOf("server", url))
            )
        }
    }
}

/**
 * Represents the possible actions that can be authorized by a Blossom authorization event.
 */
enum class BlossomAuthorizationVerb(val value: String) {
    Get("get"),
    Upload("upload"),
    List("list"),
    Delete("delete");

    override fun toString(): String = value
}

/**
 * Extension functions for [BlossomAuthorization] and [EventBuilder].
 */
fun BlossomAuthorization.toTags(): List<Tag> {
    val tags = mutableListOf<Tag>()
    tags.addAll(scope.toTags())
    tags.add(Tag.expiration(expiration))
    // Add the 't' tag to say what this auth is for
    tags.add(Tag.hashtag(action.toString()))
    return tags
}

/**
 * Blossom authorization event (Kind 24242)
 *
 * https://github.com/hzrd149/blossom/blob/master/buds/01.md
 */
fun EventBuilder.Companion.blossomAuth(authorization: BlossomAuthorization): EventBuilder {
    // Kind 24242 is used for Blossom Auth
    val kind = Kind(24242u)
    return EventBuilder(kind, authorization.content).tags(authorization.toTags())
}
