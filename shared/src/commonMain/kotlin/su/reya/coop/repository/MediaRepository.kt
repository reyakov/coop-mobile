package su.reya.coop.repository

import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import rust.nostr.sdk.AsyncNostrSigner
import su.reya.coop.blossom.BlossomClient

class MediaRepository(
    private val settingsRepository: SettingsRepository
) {
    private val httpClient = HttpClient {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                prettyPrint = true
                isLenient = true
            })
        }
    }

    suspend fun blossomUpload(
        signer: AsyncNostrSigner,
        file: ByteArray,
        contentType: String? = "image/jpeg"
    ): String? {
        return try {
            val url = settingsRepository.settings.value.blossomServer ?: "https://blossom.band"
            val blossom = BlossomClient(url, httpClient)
            val descriptor = blossom.upload(file = file, contentType = contentType, signer = signer)
            descriptor?.url
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            println("Upload failed: ${e.message}")
            null
        }
    }
}
