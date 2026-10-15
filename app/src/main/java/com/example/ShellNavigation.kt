package com.example

import android.net.Uri
import java.util.Locale

/** Routing policy contains no Instagram API calls and never reads authentication data. */
object ShellNavigation {
    const val ORIGIN = "https://www.instagram.com"
    enum class Destination(val path: String?, val label: Int) {
        HOME("/", R.string.home), MESSAGES("/direct/inbox/", R.string.messages), NOTIFICATIONS("/accounts/activity/", R.string.notifications), PROFILE(null, R.string.profile)
    }

    fun isInstagramHost(host: String?): Boolean = host != null &&
        (host == "instagram.com" || host.endsWith(".instagram.com") || host == "cdninstagram.com" || host.endsWith(".cdninstagram.com"))

    fun isTrustedPage(url: String?): Boolean {
        val uri = url?.let(Uri::parse) ?: return false
        return uri.scheme == "https" && uri.host in setOf("instagram.com", "www.instagram.com") &&
            (uri.port == -1 || uri.port == 443)
    }

    fun isReelsUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        if (!isInstagramHost(uri.host?.lowercase(Locale.ROOT))) return false
        val path = uri.path?.lowercase(Locale.ROOT)?.trimEnd('/')
        return path == "/reels" || path?.startsWith("/reels/") == true
    }

    fun isDistractionUrl(url: String): Boolean {
        val uri = Uri.parse(url)
        if (!isInstagramHost(uri.host?.lowercase(Locale.ROOT))) return false
        val path = uri.path?.lowercase(Locale.ROOT)?.trimEnd('/')
        return isReelsUrl(url) || path == "/explore" || path?.startsWith("/explore/") == true
    }

    private val reserved = setOf("notifications", "accounts", "direct", "p", "reel", "reels", "explore", "stories", "about", "legal", "challenge", "oauth", "emails", "web")
    fun profilePath(username: String): String? {
        val value = username.trim().removePrefix("@")
        return if (Regex("[A-Za-z0-9._]{1,30}").matches(value) && value.any { it.isLetterOrDigit() || it == '_' } && value.lowercase(Locale.ROOT) !in reserved) "/$value/" else null
    }

    fun destination(url: String?): Destination? {
        if (!isTrustedPage(url)) return null
        val path = Uri.parse(url).path?.trimEnd('/') ?: return null
        return when {
            path.isEmpty() -> Destination.HOME
            path == "/accounts/activity" || path == "/notifications" -> Destination.NOTIFICATIONS
            path == "/direct" || path.startsWith("/direct/") -> Destination.MESSAGES
            path.count { it == '/' } == 1 && profilePath(path.removePrefix("/")) != null -> Destination.PROFILE
            else -> null
        }
    }
}
