package su.reya.coop.blossom

import kotlinx.serialization.Serializable

@Serializable
data class BlobDescriptor(
    /**
     * The URL at which the blob/file can be accessed
     */
    val url: String,
    /**
     * The SHA256 hash of the contents in the blob
     */
    val sha256: String,
    /**
     * The size of the blob/file, in bytes
     */
    val size: Long,
    /**
     * Mime type of the blob/file
     */
    val mimeType: String? = null,
    /**
     * The date at which the blob was uploaded, as a UNIX timestamp (in seconds)
     */
    val uploaded: ULong
)