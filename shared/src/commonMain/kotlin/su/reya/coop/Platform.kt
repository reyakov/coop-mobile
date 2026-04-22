package su.reya.coop

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform