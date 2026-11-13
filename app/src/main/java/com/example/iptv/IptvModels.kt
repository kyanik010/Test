package com.example.iptv

import com.example.audio.AudioChannelGroup
import com.example.audio.AudioRendition

enum class SubscriptionMode {
    XTREAM,
    M3U
}

enum class StreamFormatPreference(val label: String) {
    AUTO("تلقائي (HLS أولاً)"),
    HLS("HLS (.m3u8)"),
    TS("MPEG-TS (.ts)")
}

data class IptvAccountInfo(
    val username: String,
    val status: String,
    val maxConnections: Int?,
    val activeConnections: Int?,
    val allowedFormats: List<String>,
    val expirationText: String
)

data class IptvChannel(
    val id: String,
    val name: String,
    val number: String = "",
    val categoryName: String = "",
    val streamUrl: String,
    val alternateTsUrl: String? = null,
    val audioGroup: AudioChannelGroup = AudioChannelGroup.OTHER,
    val isAudioCandidate: Boolean = false
)

data class TsPidInfo(
    val pid: Int,
    val streamType: Int,
    val kind: String,
    val codecName: String
)

data class StreamStructureReport(
    val sanitizedUrl: String,
    val resolvedPlaybackUrl: String,
    val httpStatus: Int,
    val contentType: String,
    val containerKind: String,
    val hasCombinedVideoAndAudio: Boolean,
    val hasIndependentAudioRendition: Boolean,
    val audioOnlyUrl: String? = null,
    val audioRenditions: List<AudioRendition> = emptyList(),
    val tsPids: List<TsPidInfo> = emptyList(),
    val summary: String,
    val errorMessage: String? = null
)
