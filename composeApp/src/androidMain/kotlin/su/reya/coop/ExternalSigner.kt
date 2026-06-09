package su.reya.coop

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import rust.nostr.sdk.AsyncNostrSigner
import rust.nostr.sdk.Event
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

data class SignerResult(
    val result: String,
    val event: Event? = null,
)

class ExternalSigner(
    private val context: Context,
    private val packageName: String,
    private val currentUser: PublicKey,
    private val launcher: ExternalSignerLauncher,
) : AsyncNostrSigner {
    private fun queryContentResolver(
        type: String,
        payload: String,
        pubkey: PublicKey? = null
    ): SignerResult? {
        val uri = "content://$packageName.${type.uppercase()}".toUri()
        val projection = mutableListOf<String>()
        projection.add(payload)
        if (pubkey != null) projection.add(pubkey.toHex())
        projection.add(currentUser.toHex())

        val cursor = context.contentResolver.query(
            uri,
            projection.toList() as Array<out String?>?,
            null, null, null,
        ) ?: return null

        return cursor.use {
            if (it.getColumnIndex("rejected") > -1) return null

            if (it.moveToFirst()) {
                val resultIndex = it.getColumnIndex(payload)
                val result = if (resultIndex > -1) it.getString(resultIndex) else null

                val eventIndex = it.getColumnIndex("event")
                val event = if (eventIndex > -1) it.getString(eventIndex) else null

                SignerResult(result = result!!, event = Event.fromJson(event!!))
            } else {
                null
            }
        }
    }

    private suspend fun requestViaIntent(
        type: String,
        payload: String,
        extras: Map<String, String> = emptyMap()
    ): String {
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:$payload".toUri()).apply {
            `package` = packageName
            putExtra("type", type)
            putExtra("current_user", currentUser.toHex())
            extras.forEach { (k, v) -> putExtra(k, v) }
        }

        val result = launcher.launch(intent)

        if (result.resultCode != Activity.RESULT_OK) {
            throw Exception("Signer returned error (resultCode=${result.resultCode})")
        }

        val data = result.data ?: throw Exception("Signer returned no data")

        if (data.getBooleanExtra("rejected", false)) {
            throw Exception("User rejected the request")
        }

        return data.getStringExtra("result") ?: throw Exception("Signer returned no result")
    }

    private suspend fun request(
        type: String,
        payload: String,
        pubkey: PublicKey? = null,
        currentUser: PublicKey? = null,
        extras: Map<String, String> = emptyMap()
    ): String {
        // Try silent Content Resolver first
        queryContentResolver(type, payload, pubkey)?.let { return it.result }

        // Fall back to Intent
        val allExtras = extras.toMutableMap().apply {
            if (pubkey != null) put("pubkey", pubkey.toHex())
            if (currentUser != null) put("current_user", currentUser.toHex())
        }

        return requestViaIntent(type, payload, allExtras)
    }

    override suspend fun getPublicKeyAsync(): PublicKey {
        return currentUser
    }

    override suspend fun signEventAsync(unsignedEvent: UnsignedEvent): Event? {
        val eventJson = unsignedEvent.asJson()

        // Try Content Resolver first
        val contentResult = queryContentResolver("sign_event", eventJson)
        contentResult?.event?.let { return it }

        // Fall back to Intent
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:$eventJson".toUri()).apply {
            `package` = packageName
            putExtra("type", "sign_event")
            putExtra("current_user", currentUser.toHex())
            putExtra("id", unsignedEvent.id()?.toHex() ?: "")
        }

        val result = launcher.launch(intent)
        if (result.resultCode != Activity.RESULT_OK) return null

        val data = result.data ?: return null
        if (data.getBooleanExtra("rejected", false)) return null

        val signedEventJson = data.getStringExtra("event")

        return signedEventJson?.let { Event.fromJson(it) }
    }

    override suspend fun nip04EncryptAsync(publicKey: PublicKey, content: String): String {
        return request(
            "nip04_encrypt",
            payload = content,
            pubkey = publicKey
        )
    }

    override suspend fun nip04DecryptAsync(publicKey: PublicKey, encryptedContent: String): String {
        return request(
            "nip04_decrypt",
            payload = encryptedContent,
            pubkey = publicKey
        )
    }

    override suspend fun nip44EncryptAsync(publicKey: PublicKey, content: String): String {
        return request(
            "nip44_encrypt",
            payload = content,
            pubkey = publicKey,
            currentUser = currentUser
        )
    }

    override suspend fun nip44DecryptAsync(publicKey: PublicKey, payload: String): String {
        return request(
            "nip44_decrypt",
            payload = payload,
            pubkey = publicKey,
            currentUser = currentUser
        )
    }
}