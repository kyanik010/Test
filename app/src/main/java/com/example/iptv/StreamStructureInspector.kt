package com.example.iptv

import com.example.CredentialSanitizer
import com.example.NetworkSecurityValidator
import com.example.audio.HlsMasterPlaylistParser
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * Inspects actual IPTV stream structure (HLS Master/Media vs MPEG-TS PAT/PMT PIDs)
 * before handing the URL to GStreamer.
 */
object StreamStructureInspector {

    private const val USER_AGENT = "SONIX-TV/1.0"
    private const val PROBE_BYTES = 64 * 1024

    fun inspectRemote(streamUrl: String): StreamStructureReport {
        val sanitized = CredentialSanitizer.sanitizeUrl(streamUrl)
        return try {
            var currentUrl = streamUrl
            var redirects = 0
            while (redirects < 5) {
                NetworkSecurityValidator.assertSafeUrl(currentUrl)
                val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    val code = conn.responseCode
                    if (code in listOf(301, 302, 303, 307, 308)) {
                        val loc = conn.getHeaderField("Location")
                        if (!loc.isNullOrBlank()) {
                            currentUrl = URL(URL(currentUrl), loc).toString()
                            redirects++
                            continue
                        }
                    }

                    val contentType = conn.contentType.orEmpty()
                    if (code !in 200..299) {
                        return StreamStructureReport(
                            sanitizedUrl = sanitized,
                            resolvedPlaybackUrl = currentUrl,
                            httpStatus = code,
                            contentType = contentType,
                            containerKind = "HTTP $code",
                            hasCombinedVideoAndAudio = false,
                            hasIndependentAudioRendition = false,
                            summary = "فشل فحص المصدر (HTTP $code)",
                            errorMessage = "HTTP $code"
                        )
                    }

                    val sample = ByteArrayOutputStream()
                    val buf = ByteArray(8192)
                    conn.inputStream.use { input ->
                        var total = 0
                        while (total < PROBE_BYTES) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            sample.write(buf, 0, n)
                            total += n
                        }
                    }
                    return analyzeBytes(
                        originalUrl = streamUrl,
                        resolvedUrl = currentUrl,
                        httpStatus = code,
                        contentType = contentType,
                        bytes = sample.toByteArray()
                    )
                } finally {
                    conn.disconnect()
                }
            }
            StreamStructureReport(
                sanitizedUrl = sanitized,
                resolvedPlaybackUrl = streamUrl,
                httpStatus = 0,
                contentType = "",
                containerKind = "Redirect Loop",
                hasCombinedVideoAndAudio = false,
                hasIndependentAudioRendition = false,
                summary = "فشل الفحص: تجاوز حد التوجيهات",
                errorMessage = "Too many redirects"
            )
        } catch (e: Exception) {
            StreamStructureReport(
                sanitizedUrl = sanitized,
                resolvedPlaybackUrl = streamUrl,
                httpStatus = 0,
                contentType = "",
                containerKind = "Error",
                hasCombinedVideoAndAudio = false,
                hasIndependentAudioRendition = false,
                summary = "تعذر فحص البث: ${CredentialSanitizer.sanitizeMessage(e.message)}",
                errorMessage = CredentialSanitizer.sanitizeMessage(e.message)
            )
        }
    }

    fun analyzeBytes(
        originalUrl: String,
        resolvedUrl: String,
        httpStatus: Int = 200,
        contentType: String = "",
        bytes: ByteArray
    ): StreamStructureReport {
        val sanitized = CredentialSanitizer.sanitizeUrl(originalUrl)
        if (bytes.isEmpty()) {
            return StreamStructureReport(
                sanitizedUrl = sanitized,
                resolvedPlaybackUrl = resolvedUrl,
                httpStatus = httpStatus,
                contentType = contentType,
                containerKind = "Empty",
                hasCombinedVideoAndAudio = false,
                hasIndependentAudioRendition = false,
                summary = "استجابة فارغة من المصدر"
            )
        }

        val tsOffset = findTsSyncOffset(bytes)
        if (tsOffset >= 0) {
            val pids = parseTsPatAndPmt(bytes, tsOffset)
            val hasVideo = pids.any { it.kind == "VIDEO" }
            val hasAudio = pids.any { it.kind == "AUDIO" }
            val pidSummary = if (pids.isNotEmpty()) {
                pids.joinToString(", ") { "${it.kind} PID 0x${it.pid.toString(16)} (${it.codecName})" }
            } else {
                "MPEG-TS stream (PMT لم تظهر في أول ${bytes.size / 1024}KB)"
            }
            return StreamStructureReport(
                sanitizedUrl = sanitized,
                resolvedPlaybackUrl = resolvedUrl,
                httpStatus = httpStatus,
                contentType = contentType,
                containerKind = "MPEG-TS",
                hasCombinedVideoAndAudio = hasVideo && hasAudio,
                hasIndependentAudioRendition = false,
                tsPids = pids,
                summary = "MPEG-TS | $pidSummary"
            )
        }

        val textPreview = bytes.toString(Charsets.UTF_8)
        if (textPreview.trimStart().startsWith("#EXTM3U")) {
            if (HlsMasterPlaylistParser.isMasterPlaylist(textPreview)) {
                val master = HlsMasterPlaylistParser.parse(resolvedUrl, textPreview)
                val bestAudio = HlsMasterPlaylistParser.selectBestAudioRendition(master)
                return StreamStructureReport(
                    sanitizedUrl = sanitized,
                    resolvedPlaybackUrl = bestAudio?.uri ?: resolvedUrl,
                    httpStatus = httpStatus,
                    contentType = contentType,
                    containerKind = "HLS Master",
                    hasCombinedVideoAndAudio = bestAudio == null,
                    hasIndependentAudioRendition = bestAudio != null,
                    audioOnlyUrl = bestAudio?.uri,
                    audioRenditions = master.audioRenditions,
                    summary = if (bestAudio != null) {
                        "HLS Master | مسار صوت مستقل (${bestAudio.name} / ${bestAudio.language ?: "und"})"
                    } else {
                        "HLS Master | صوت وفيديو مدمجان داخل مسار #EXT-X-STREAM-INF"
                    }
                )
            } else {
                val hasAacOnlySegments = textPreview.contains(".aac", ignoreCase = true) && !textPreview.contains(".ts", ignoreCase = true)
                return StreamStructureReport(
                    sanitizedUrl = sanitized,
                    resolvedPlaybackUrl = resolvedUrl,
                    httpStatus = httpStatus,
                    contentType = contentType,
                    containerKind = "HLS Media",
                    hasCombinedVideoAndAudio = !hasAacOnlySegments,
                    hasIndependentAudioRendition = hasAacOnlySegments,
                    audioOnlyUrl = if (hasAacOnlySegments) resolvedUrl else null,
                    summary = if (hasAacOnlySegments) {
                        "HLS Media | مقاطع صوت AAC مستقلة"
                    } else {
                        "HLS Media | مقاطع TS/fMP4 مدمجة (سيتم استخراج الصوت فقط في GStreamer)"
                    }
                )
            }
        }

        if (bytes.size >= 4 && (bytes[0].toInt() and 0xFF) == 0xFF && ((bytes[1].toInt() and 0xF0) == 0xF0)) {
            return StreamStructureReport(
                sanitizedUrl = sanitized,
                resolvedPlaybackUrl = resolvedUrl,
                httpStatus = httpStatus,
                contentType = contentType,
                containerKind = "AAC ADTS",
                hasCombinedVideoAndAudio = false,
                hasIndependentAudioRendition = true,
                audioOnlyUrl = resolvedUrl,
                summary = "AAC ADTS خام (صوت فقط)"
            )
        }

        return StreamStructureReport(
            sanitizedUrl = sanitized,
            resolvedPlaybackUrl = resolvedUrl,
            httpStatus = httpStatus,
            contentType = contentType,
            containerKind = contentType.ifBlank { "Unknown" },
            hasCombinedVideoAndAudio = true,
            hasIndependentAudioRendition = false,
            summary = "بنية غير مصنفة (${contentType.ifBlank { "binary" }})"
        )
    }

    fun findTsSyncOffset(bytes: ByteArray): Int {
        val limit = (bytes.size - 377).coerceAtMost(188)
        for (offset in 0..limit) {
            if (bytes[offset] == 0x47.toByte() &&
                bytes[offset + 188] == 0x47.toByte() &&
                bytes[offset + 376] == 0x47.toByte()
            ) {
                return offset
            }
        }
        return -1
    }

    fun parseTsPatAndPmt(bytes: ByteArray, startOffset: Int): List<TsPidInfo> {
        val pmtPids = mutableSetOf<Int>()
        val results = mutableListOf<TsPidInfo>()
        var offset = startOffset

        while (offset + 188 <= bytes.size) {
            if (bytes[offset] == 0x47.toByte()) {
                val b1 = bytes[offset + 1].toInt() and 0xFF
                val b2 = bytes[offset + 2].toInt() and 0xFF
                val b3 = bytes[offset + 3].toInt() and 0xFF
                val pusi = (b1 and 0x40) != 0
                val pid = ((b1 and 0x1F) shl 8) or b2
                val adapt = (b3 and 0x30) shr 4

                if (pid == 0 && pusi && (adapt == 1 || adapt == 3)) {
                    var pos = offset + 4
                    if (adapt == 3) {
                        val len = bytes[pos].toInt() and 0xFF
                        pos += 1 + len
                    }
                    if (pos < offset + 188) {
                        val pointerField = bytes[pos].toInt() and 0xFF
                        pos += 1 + pointerField
                        if (pos + 8 <= offset + 188 && bytes[pos] == 0x00.toByte()) {
                            val sectionLen = ((bytes[pos + 1].toInt() and 0x0F) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
                            val end = (pos + 3 + sectionLen - 4).coerceAtMost(offset + 188)
                            var p = pos + 8
                            while (p + 4 <= end) {
                                val progNum = ((bytes[p].toInt() and 0xFF) shl 8) or (bytes[p + 1].toInt() and 0xFF)
                                val pmtPid = ((bytes[p + 2].toInt() and 0x1F) shl 8) or (bytes[p + 3].toInt() and 0xFF)
                                if (progNum != 0) pmtPids.add(pmtPid)
                                p += 4
                            }
                        }
                    }
                }
            }
            offset += 188
        }

        if (pmtPids.isEmpty()) return emptyList()

        val seenPids = mutableSetOf<Int>()
        offset = startOffset
        while (offset + 188 <= bytes.size) {
            if (bytes[offset] == 0x47.toByte()) {
                val b1 = bytes[offset + 1].toInt() and 0xFF
                val b2 = bytes[offset + 2].toInt() and 0xFF
                val b3 = bytes[offset + 3].toInt() and 0xFF
                val pusi = (b1 and 0x40) != 0
                val pid = ((b1 and 0x1F) shl 8) or b2
                val adapt = (b3 and 0x30) shr 4

                if (pid in pmtPids && pusi && (adapt == 1 || adapt == 3)) {
                    var pos = offset + 4
                    if (adapt == 3) {
                        val len = bytes[pos].toInt() and 0xFF
                        pos += 1 + len
                    }
                    if (pos < offset + 188) {
                        val pointerField = bytes[pos].toInt() and 0xFF
                        pos += 1 + pointerField
                        if (pos + 12 <= offset + 188 && bytes[pos] == 0x02.toByte()) {
                            val sectionLen = ((bytes[pos + 1].toInt() and 0x0F) shl 8) or (bytes[pos + 2].toInt() and 0xFF)
                            val progInfoLen = ((bytes[pos + 10].toInt() and 0x0F) shl 8) or (bytes[pos + 11].toInt() and 0xFF)
                            val end = (pos + 3 + sectionLen - 4).coerceAtMost(offset + 188)
                            var p = pos + 12 + progInfoLen
                            while (p + 5 <= end) {
                                val streamType = bytes[p].toInt() and 0xFF
                                val elemPid = ((bytes[p + 1].toInt() and 0x1F) shl 8) or (bytes[p + 2].toInt() and 0xFF)
                                val esInfoLen = ((bytes[p + 3].toInt() and 0x0F) shl 8) or (bytes[p + 4].toInt() and 0xFF)
                                if (seenPids.add(elemPid)) {
                                    results.add(describeTsStream(elemPid, streamType))
                                }
                                p += 5 + esInfoLen
                            }
                            if (results.isNotEmpty()) break
                        }
                    }
                }
            }
            offset += 188
        }
        return results
    }

    private fun describeTsStream(pid: Int, streamType: Int): TsPidInfo {
        return when (streamType) {
            0x01, 0x02 -> TsPidInfo(pid, streamType, "VIDEO", "MPEG-2")
            0x10 -> TsPidInfo(pid, streamType, "VIDEO", "MPEG-4")
            0x1B -> TsPidInfo(pid, streamType, "VIDEO", "H.264/AVC")
            0x24 -> TsPidInfo(pid, streamType, "VIDEO", "H.265/HEVC")
            0x03, 0x04 -> TsPidInfo(pid, streamType, "AUDIO", "MPEG Audio (MP2/MP3)")
            0x0F -> TsPidInfo(pid, streamType, "AUDIO", "AAC ADTS")
            0x11 -> TsPidInfo(pid, streamType, "AUDIO", "AAC LATM")
            0x81 -> TsPidInfo(pid, streamType, "AUDIO", "AC-3")
            0x87 -> TsPidInfo(pid, streamType, "AUDIO", "E-AC-3")
            else -> TsPidInfo(pid, streamType, "OTHER", "0x${streamType.toString(16)}")
        }
    }
}
