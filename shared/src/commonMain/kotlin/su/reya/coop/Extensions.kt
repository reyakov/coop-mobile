package su.reya.coop

import rust.nostr.sdk.PublicKey

fun PublicKey.short(): String {
    val bech32 = toBech32()
    return bech32.substring(0, 6) + "..." + bech32.substring(bech32.length - 4)
}

private val urlRegex = Regex("(https?://\\S+)", RegexOption.IGNORE_CASE)
private val imageExtensions = setOf("jpg", "jpeg", "png", "gif", "webp", "bmp")

fun String.extractUrls(): List<String> {
    return urlRegex.findAll(this).map { it.value }.toList()
}

fun String.removeImageUrls(): String {
    return urlRegex.replace(this) { result ->
        if (result.value.isImageUrl()) "" else result.value
    }.replace(Regex("\\s+"), " ").trim()
}

fun String.isImageUrl(): Boolean {
    val extension = this.substringAfterLast('.', "").lowercase()
    return extension in imageExtensions
}
