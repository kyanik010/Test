package com.example.audio

import java.net.URI

/**
 * Represents an audio rendition declared in an HLS Master Playlist via #EXT-X-MEDIA:TYPE=AUDIO.
 */
data class AudioRendition(
    val groupId: String,
    val name: String,
    val language: String? = null,
    val isDefault: Boolean = false,
    val autoSelect: Boolean = false,
    val channels: Int? = null,
    val uri: String
)

/**
 * Parsed HLS Master Playlist data for audio selection.
 */
data class HlsMasterPlaylist(
    val audioRenditions: List<AudioRendition>,
    val streamAudioGroupIds: List<String>
)

/**
 * Deterministic parser for HLS Master Playlists according to RFC 8216.
 * Extracts independent #EXT-X-MEDIA:TYPE=AUDIO tags and resolves URIs.
 */
object HlsMasterPlaylistParser {

    private val EXT_X_MEDIA_REGEX = Regex("""#EXT-X-MEDIA:(.+)""")
    private val EXT_X_STREAM_INF_REGEX = Regex("""#EXT-X-STREAM-INF:(.+)""")
    private val ATTRIBUTE_REGEX = Regex("""([A-Z0-9\-]+)=("[^"]*"|[^",\s]+)""")

    fun isMasterPlaylist(content: String): Boolean {
        return content.contains("#EXT-X-MEDIA:TYPE=AUDIO") || content.contains("#EXT-X-STREAM-INF")
    }

    fun parse(baseUri: String, content: String): HlsMasterPlaylist {
        val audioRenditions = mutableListOf<AudioRendition>()
        val streamAudioGroupIds = mutableListOf<String>()

        val lines = content.lines()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#EXT-X-MEDIA:")) {
                val mediaMatch = EXT_X_MEDIA_REGEX.matchEntire(trimmed)
                if (mediaMatch != null) {
                    val attributes = parseAttributes(mediaMatch.groupValues[1])
                    if (attributes["TYPE"] == "AUDIO") {
                        val uri = attributes["URI"]
                        if (!uri.isNullOrBlank()) {
                            val resolvedUri = resolveUri(baseUri, uri)
                            val channelsStr = attributes["CHANNELS"]
                            val channels = channelsStr?.split("/")?.firstOrNull()?.toIntOrNull()
                            audioRenditions.add(
                                AudioRendition(
                                    groupId = attributes["GROUP-ID"] ?: "",
                                    name = attributes["NAME"] ?: "Audio",
                                    language = attributes["LANGUAGE"],
                                    isDefault = attributes["DEFAULT"]?.equals("YES", ignoreCase = true) == true,
                                    autoSelect = attributes["AUTOSELECT"]?.equals("YES", ignoreCase = true) == true,
                                    channels = channels,
                                    uri = resolvedUri
                                )
                            )
                        }
                    }
                }
            } else if (trimmed.startsWith("#EXT-X-STREAM-INF:")) {
                val streamMatch = EXT_X_STREAM_INF_REGEX.matchEntire(trimmed)
                if (streamMatch != null) {
                    val attributes = parseAttributes(streamMatch.groupValues[1])
                    val audioGroup = attributes["AUDIO"]
                    if (!audioGroup.isNullOrBlank() && !streamAudioGroupIds.contains(audioGroup)) {
                        streamAudioGroupIds.add(audioGroup)
                    }
                }
            }
        }

        return HlsMasterPlaylist(audioRenditions, streamAudioGroupIds)
    }

    fun selectBestAudioRendition(
        playlist: HlsMasterPlaylist,
        preferredLanguage: String? = null
    ): AudioRendition? {
        val candidates = playlist.audioRenditions
        if (candidates.isEmpty()) return null

        // 1. Filter by stream-inf audio group if present
        val filtered = if (playlist.streamAudioGroupIds.isNotEmpty()) {
            val matched = candidates.filter { it.groupId in playlist.streamAudioGroupIds }
            if (matched.isNotEmpty()) matched else candidates
        } else {
            candidates
        }

        // 2. Match preferred language if specified
        if (!preferredLanguage.isNullOrBlank()) {
            val langMatch = filtered.firstOrNull {
                it.language?.equals(preferredLanguage, ignoreCase = true) == true
            }
            if (langMatch != null) return langMatch
        }

        // 3. Match DEFAULT=YES
        val defaultRendition = filtered.firstOrNull { it.isDefault }
        if (defaultRendition != null) return defaultRendition

        // 4. Match AUTOSELECT=YES
        val autoSelectRendition = filtered.firstOrNull { it.autoSelect }
        if (autoSelectRendition != null) return autoSelectRendition

        // 5. Fallback to first available rendition
        return filtered.first()
    }

    /**
     * Resolves relative or absolute URI based on standard RFC 8216 rules.
     */
    fun resolveUri(baseUri: String, uriString: String): String {
        val trimmed = uriString.trim().removeSurrounding("\"")
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            return trimmed
        }
        return try {
            val base = URI(baseUri)
            base.resolve(trimmed).toString()
        } catch (_: Exception) {
            if (trimmed.startsWith("/")) {
                val proto = baseUri.substringBefore("://")
                val host = baseUri.substringAfter("://").substringBefore("/")
                "$proto://$host$trimmed"
            } else {
                val basePath = baseUri.substringBeforeLast('/')
                "$basePath/$trimmed"
            }
        }
    }

    private fun parseAttributes(attributeString: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (match in ATTRIBUTE_REGEX.findAll(attributeString)) {
            val key = match.groupValues[1]
            val value = match.groupValues[2].removeSurrounding("\"")
            result[key] = value
        }
        return result
    }
}
