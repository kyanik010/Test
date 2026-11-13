package com.example

import android.net.Uri

/**
 * Pure utility to sanitize URLs, credentials, and error messages
 * before they are logged or passed to JavaScript/UI.
 */
internal object CredentialSanitizer {

    private val SENSITIVE_QUERY_PARAMS = setOf(
        "password", "pass", "pwd", "token", "secret", "key", "auth", "apikey"
    )

    private val XTREAM_PATH_REGEX = Regex(
        """(https?://[^/]+)/(live|movie|series)/([^/]+)/([^/]+)/([^/\s?#]+)""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Sanitizes any URL by:
     * 1. Redacting userInfo (user:pass@host) -> ***:***@host
     * 2. Redacting Xtream path credentials (/live/user/pass/id.ext) -> /live/[REDACTED]/[REDACTED]/id.ext
     * 3. Redacting sensitive query parameters (password=..., token=...)
     */
    fun sanitizeUrl(rawUrl: String?): String {
        if (rawUrl.isNullOrBlank()) return ""
        var text = rawUrl.trim()

        // 1. Mask Xtream path format: /(live|movie|series)/username/password/id.ext
        text = XTREAM_PATH_REGEX.replace(text) { match ->
            val base = match.groupValues[1]
            val type = match.groupValues[2]
            val streamIdAndExt = match.groupValues[5]
            "$base/$type/***/*****/$streamIdAndExt"
        }

        // 2. Parse and sanitize userInfo and sensitive query params
        return try {
            val uri = Uri.parse(text)
            val builder = uri.buildUpon()

            // Mask userInfo if present
            val userInfo = uri.userInfo
            val host = uri.host
            if (host != null && !userInfo.isNullOrEmpty()) {
                val portStr = if (uri.port != -1) ":${uri.port}" else ""
                builder.encodedAuthority("***:***@$host$portStr")
            }

            // Mask sensitive query parameters
            val paramNames = uri.queryParameterNames
            if (paramNames.isNotEmpty()) {
                builder.clearQuery()
                for (name in paramNames) {
                    val lower = name.lowercase()
                    if (SENSITIVE_QUERY_PARAMS.any { lower.contains(it) }) {
                        builder.appendQueryParameter(name, "[REDACTED]")
                    } else {
                        builder.appendQueryParameter(name, uri.getQueryParameter(name))
                    }
                }
            }
            builder.build().toString()
        } catch (_: Exception) {
            // Fallback string replacement if Uri parsing fails on unusual input
            text.replace(Regex("""(?i)(password|pass|token|secret)=[^&\s]+"""), "$1=[REDACTED]")
        }
    }

    /**
     * Sanitizes arbitrary text, error messages, or logs that may embed URLs or credentials.
     */
    fun sanitizeMessage(message: String?): String {
        if (message.isNullOrBlank()) return ""
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val withSanitizedUrls = urlRegex.replace(message) { match ->
            sanitizeUrl(match.value)
        }
        return withSanitizedUrls
            .replace(Regex("""(?i)(password|pass|token|secret)\s*[:=]\s*["']?[^"'\s,]+["']?"""), "$1=[REDACTED]")
    }
}
