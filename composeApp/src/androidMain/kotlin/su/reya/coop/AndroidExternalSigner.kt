package su.reya.coop

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import rust.nostr.sdk.PublicKey
import rust.nostr.sdk.UnsignedEvent

class AndroidExternalSigner(
    private val context: Context,
    private val launcher: ExternalSignerLauncher,
) : ExternalSignerHandler {
    private var cachedPackageName: String? = null

    private data class ContentResolverResult(
        val result: String,
        val event: String? = null,
    )

    private fun queryContentResolver(
        type: String,
        payload: String,
        pubkey: PublicKey? = null,
        currentUser: PublicKey? = null,
    ): ContentResolverResult? {
        val uri = "content://$cachedPackageName.${type.uppercase()}".toUri()
        val projection = mutableListOf<String?>().apply {
            add(payload)
            add(pubkey?.toHex() ?: "")
            add(currentUser?.toHex() ?: "")
        }

        val cursor = context.contentResolver.query(
            uri,
            projection.toTypedArray(),
            null, null, null,
        ) ?: return null

        return cursor.use {
            if (it.getColumnIndex("rejected") > -1) return null
            if (it.moveToFirst()) {
                val resultIndex = it.getColumnIndex("result")
                val result = if (resultIndex > -1) it.getString(resultIndex) else null

                val eventIndex = it.getColumnIndex("event")
                val event = if (eventIndex > -1) it.getString(eventIndex) else null

                ContentResolverResult(result = result!!, event = event)
            } else null
        }
    }

    private suspend fun request(
        type: String,
        payload: String,
        pubkey: PublicKey? = null,
        currentUser: PublicKey? = null,
        resultKey: String = "result",
        extras: Map<String, String> = emptyMap(),
    ): String? {
        // Try Content Resolver first
        queryContentResolver(type, payload, pubkey, currentUser)?.let {
            return it.result
        }

        // Fall back to Intent
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:$payload".toUri()).apply {
            `package` = cachedPackageName
            putExtra("type", type)
            if (pubkey != null) putExtra("pubkey", pubkey.toHex())
            if (currentUser != null) putExtra("current_user", currentUser.toHex())
            extras.forEach { (k, v) -> putExtra(k, v) }
        }

        val result = launcher.launch(intent)
        if (result.resultCode != Activity.RESULT_OK) return null

        val data = result.data ?: return null
        if (data.getBooleanExtra("rejected", false)) return null

        return data.getStringExtra(resultKey)
    }

    override fun isAvailable(): Boolean {
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri())
        return context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
    }

    override fun setPackageName(packageName: String) {
        cachedPackageName = packageName
    }

    override suspend fun getPublicKey(permissions: String?): ExternalSignerResult? {
        val intent = Intent(Intent.ACTION_VIEW, "nostrsigner:".toUri()).apply {
            putExtra("type", "get_public_key")
            if (permissions != null) putExtra("permissions", permissions)
        }

        val result = launcher.launch(intent)
        if (result.resultCode != Activity.RESULT_OK) return null

        val data = result.data ?: return null
        if (data.getBooleanExtra("rejected", false)) return null

        val pubkey = data.getStringExtra("result") ?: return null
        val packageName = data.getStringExtra("package") ?: return null
        cachedPackageName = packageName

        return ExternalSignerResult(PublicKey.parse(pubkey), packageName)
    }

    override suspend fun signEvent(event: UnsignedEvent, currentUser: PublicKey): String? {
        val extras = event.id()?.let { mapOf("id" to it.toHex()) } ?: emptyMap()
        return request(
            type = "sign_event",
            payload = event.asJson(),
            currentUser = currentUser,
            resultKey = "event",
            extras = extras,
        )
    }

    override suspend fun nip04Encrypt(plaintext: String, pubkey: PublicKey): String? {
        return request("nip04_encrypt", plaintext, pubkey)
    }

    override suspend fun nip04Decrypt(ciphertext: String, pubkey: PublicKey): String? {
        return request("nip04_decrypt", ciphertext, pubkey)
    }

    override suspend fun nip44Encrypt(
        plaintext: String,
        pubkey: PublicKey,
        currentUser: PublicKey
    ): String? {
        return request("nip44_encrypt", plaintext, pubkey, currentUser)
    }

    override suspend fun nip44Decrypt(
        ciphertext: String,
        pubkey: PublicKey,
        currentUser: PublicKey
    ): String? {
        return request("nip44_decrypt", ciphertext, pubkey, currentUser)
    }
}
