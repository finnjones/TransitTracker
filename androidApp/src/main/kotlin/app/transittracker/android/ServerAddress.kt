package app.transittracker.android

import java.net.URI

/** Checks and tidies the server address set at build time (tt.baseUrl, default https://tt.finnjones.me). */
internal object ServerAddress {
    /** `https://host[:port][/path]` without a trailing slash, or null when [input] is not one. */
    fun normalize(input: String): String? {
        val trimmed = input.trim().trimEnd('/')
        if (!trimmed.startsWith("https://", ignoreCase = true)) return null
        val uri = runCatching { URI(trimmed) }.getOrNull() ?: return null
        if (uri.host.isNullOrBlank() || uri.rawQuery != null || uri.rawFragment != null || uri.rawUserInfo != null) return null
        return "https://" + trimmed.substring("https://".length)
    }
}
