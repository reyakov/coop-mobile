package su.reya.coop.viewmodel

import androidx.lifecycle.ViewModel
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import rust.nostr.sdk.AsyncNostrSigner
import su.reya.coop.ErrorManager
import su.reya.coop.blossom.BlossomClient

abstract class BaseViewModel : ViewModel() {
    protected val httpClient by lazy {
        HttpClient {
            install(ContentNegotiation) {
                json(Json {
                    ignoreUnknownKeys = true
                    prettyPrint = true
                    isLenient = true
                })
            }
        }
    }

    protected fun showError(message: String) {
        ErrorManager.showError(message)
    }

    protected suspend fun blossomUpload(
        signer: AsyncNostrSigner,
        file: ByteArray,
        contentType: String? = "image/jpeg"
    ): String? {
        return try {
            val blossom = BlossomClient(url = "https://blossom.band", client = httpClient)
            val descriptor = blossom.upload(
                file = file,
                contentType = contentType,
                signer = signer,
            )
            descriptor?.url
        } catch (e: Exception) {
            showError("Upload failed: ${e.message}")
            null
        }
    }
}