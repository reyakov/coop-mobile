package su.reya.coop.blossom

import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.HeaderValue
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.core.toByteArray
import okio.ByteString.Companion.toByteString
import rust.nostr.sdk.EventBuilder
import rust.nostr.sdk.NostrSigner
import rust.nostr.sdk.Timestamp
import kotlin.io.encoding.Base64
import kotlin.time.Duration

class BlossomClient(
    val url: String,
    val client: HttpClient,
) {
    suspend fun upload(
        file: ByteArray,
        contentType: String? = null,
        signer: NostrSigner? = null
    ): BlobDescriptor? {
        val url = "$url/upload"
        val hash = file.toByteString().sha256().hex()
        val fileHashes = listOf(hash)

        val res = client.put(url) {
            // Set body
            setBody(file)

            // Set the content type if provided
            contentType?.let {
                header(HttpHeaders.ContentType, it)
            }

            signer?.let {
                val defaultAuth = defaultAuth(
                    action = BlossomAuthorizationVerb.Upload,
                    defaultContent = "Blossom upload authorization",
                    defaultScope = BlossomAuthorizationScope.BlobSha256Hashes(fileHashes)
                )
                val authHeader = buildAuthHeader(it, defaultAuth)
                header(HttpHeaders.Authorization, authHeader.value)
            }
        }

        return when (res.status) {
            HttpStatusCode.OK -> res.body<BlobDescriptor>()
            else -> {
                throw Exception("Failed to upload file: ${res.status}")
            }
        }
    }

    fun defaultAuth(
        action: BlossomAuthorizationVerb,
        defaultContent: String,
        defaultScope: BlossomAuthorizationScope
    ): BlossomAuthorization {
        val expiration = Timestamp.now().addDuration(Duration.parse("300s"))
        return BlossomAuthorization(
            content = defaultContent,
            expiration = expiration,
            action = action,
            scope = defaultScope
        )
    }

    suspend fun buildAuthHeader(signer: NostrSigner, authz: BlossomAuthorization): HeaderValue {
        val authEvent = EventBuilder.blossomAuth(authz).sign(signer)
        val encodedAuth = Base64.encode(authEvent.asJson().toByteArray())
        val value = "Nostr $encodedAuth"
        return HeaderValue(value)
    }
}
