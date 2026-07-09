package su.reya.coop

import rust.nostr.sdk.PublicKey

fun PublicKey.short(): String {
    val bech32 = toBech32()
    return bech32.substring(0, 6) + "..." + bech32.substring(bech32.length - 4)
}

val URL_REGEX = Regex("(https?://\\S+)", RegexOption.IGNORE_CASE)
private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

fun String.removeImageUrls(): String {
    return URL_REGEX.replace(this) { result ->
        if (result.value.isImageUrl()) "" else result.value
    }.trim()
}

fun String.isImageUrl(): Boolean {
    val extension = this.substringAfterLast('.', "").lowercase()
    return extension in imageExtensions
}
