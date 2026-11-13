package com.example

import com.example.audio.AudioChannelGroup
import com.example.iptv.IptvSubscriptionClient
import com.example.iptv.StreamStructureInspector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SonixGStreamerLabTests {

    @Test
    fun parseM3uText_extractsSingleSubscriptionAndClassifiesAudioCandidates() {
        val m3u = """
            #EXTM3U
            #EXTINF:-1 group-title="Sports HD",beIN SPORTS English 1
            http://iptv.example.com:8080/live/user123/pass456/101.m3u8
            #EXTINF:-1 group-title="beIN Arabic",beIN SPORTS 1 Arabic
            http://iptv.example.com:8080/live/user123/pass456/102.m3u8
            #EXTINF:-1 group-title="Cinema",Movie Channel
            http://iptv.example.com:8080/live/user123/pass456/103.ts
        """.trimIndent()

        val channels = IptvSubscriptionClient.parseM3uText(m3u)
        assertEquals(3, channels.size)
        assertEquals(AudioChannelGroup.BEIN_SPORTS, channels[0].audioGroup)
        assertTrue(channels[1].isAudioCandidate)
        assertFalse(channels[2].isAudioCandidate)
    }

    @Test
    fun streamInspector_identifiesHlsMasterWithIndependentAudioRendition() {
        val master = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Arabic",LANGUAGE="ar",DEFAULT=YES,AUTOSELECT=YES,URI="audio_ar/playlist.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=4500000,AUDIO="aud"
            video_1080p/playlist.m3u8
        """.trimIndent()

        val report = StreamStructureInspector.analyzeBytes(
            originalUrl = "http://cdn.example.com/ch1/master.m3u8",
            resolvedUrl = "http://cdn.example.com/ch1/master.m3u8",
            bytes = master.toByteArray()
        )
        assertEquals("HLS Master", report.containerKind)
        assertTrue(report.hasIndependentAudioRendition)
        assertFalse(report.hasCombinedVideoAndAudio)
        assertEquals("http://cdn.example.com/ch1/audio_ar/playlist.m3u8", report.audioOnlyUrl)
    }

    @Test
    fun streamInspector_identifiesHlsMediaCombinedTs() {
        val media = """
            #EXTM3U
            #EXT-X-VERSION:3
            #EXT-X-TARGETDURATION:4
            #EXTINF:4.0,
            segment_1001.ts
            #EXTINF:4.0,
            segment_1002.ts
        """.trimIndent()

        val report = StreamStructureInspector.analyzeBytes(
            originalUrl = "http://cdn.example.com/ch2/index.m3u8",
            resolvedUrl = "http://cdn.example.com/ch2/index.m3u8",
            bytes = media.toByteArray()
        )
        assertEquals("HLS Media", report.containerKind)
        assertTrue(report.hasCombinedVideoAndAudio)
        assertFalse(report.hasIndependentAudioRendition)
    }

    @Test
    fun streamInspector_parsesMpegTsPatAndPmtPids() {
        val packets = ByteArray(188 * 4)

        // Packet 0: sync=0x47, PUSI=1, PID=0x0000 (PAT), payload only
        packets[0] = 0x47
        packets[1] = 0x40
        packets[2] = 0x00
        packets[3] = 0x10
        packets[4] = 0x00
        packets[5] = 0x00
        packets[6] = 0xB0.toByte()
        packets[7] = 0x0D
        packets[8] = 0x00
        packets[9] = 0x01
        packets[10] = 0xC1.toByte()
        packets[11] = 0x00
        packets[12] = 0x00
        packets[13] = 0x00
        packets[14] = 0x01
        packets[15] = 0xE1.toByte()
        packets[16] = 0x00

        // Packet 1: sync=0x47, PUSI=1, PID=0x0100 (PMT)
        val p1 = 188
        packets[p1] = 0x47
        packets[p1 + 1] = 0x41
        packets[p1 + 2] = 0x00
        packets[p1 + 3] = 0x10
        packets[p1 + 4] = 0x00
        packets[p1 + 5] = 0x02
        packets[p1 + 6] = 0xB0.toByte()
        packets[p1 + 7] = 0x17
        packets[p1 + 15] = 0xF0.toByte()
        packets[p1 + 16] = 0x00
        // ES 1: Video H.264 (0x1B), PID 0x0101
        packets[p1 + 17] = 0x1B
        packets[p1 + 18] = 0xE1.toByte()
        packets[p1 + 19] = 0x01
        packets[p1 + 20] = 0xF0.toByte()
        packets[p1 + 21] = 0x00
        // ES 2: Audio AAC ADTS (0x0F), PID 0x0102
        packets[p1 + 22] = 0x0F
        packets[p1 + 23] = 0xE1.toByte()
        packets[p1 + 24] = 0x02
        packets[p1 + 25] = 0xF0.toByte()
        packets[p1 + 26] = 0x00

        // Packet 2 & 3 sync bytes
        packets[188 * 2] = 0x47
        packets[188 * 3] = 0x47

        val report = StreamStructureInspector.analyzeBytes(
            originalUrl = "http://cdn.example.com/live/u/p/10.ts",
            resolvedUrl = "http://cdn.example.com/live/u/p/10.ts",
            bytes = packets
        )
        assertEquals("MPEG-TS", report.containerKind)
        assertTrue(report.hasCombinedVideoAndAudio)
        assertEquals(2, report.tsPids.size)
        assertEquals("VIDEO", report.tsPids[0].kind)
        assertEquals(0x0101, report.tsPids[0].pid)
        assertEquals("AUDIO", report.tsPids[1].kind)
        assertEquals(0x0102, report.tsPids[1].pid)
        assertFalse(report.sanitizedUrl.contains("p/10") && report.sanitizedUrl.contains("/u/"))
    }

    @Test
    fun streamInspector_skipsRemoteHttpProbeForDirectTsUrls() {
        StreamStructureInspector.clearCache()
        val report = StreamStructureInspector.inspectForAudioRenditionIfHls(
            "http://nonexistent.invalid:9999/live/user/pass/101.ts"
        )
        assertEquals("MPEG-TS", report.containerKind)
        assertEquals(0, report.httpStatus)
        assertTrue(report.hasCombinedVideoAndAudio)
        assertFalse(report.hasIndependentAudioRendition)
    }

    @Test
    fun syncPolicy_returnsNeutralRateInsideTolerance_andAdjustsOutside() {
        assertEquals(1.0f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 1080L, 0L))
        assertEquals(1.02f, ExternalAudioSyncPolicy.livePlaybackSpeed(1000L, 1400L, 0L))
        assertEquals(0.98f, ExternalAudioSyncPolicy.livePlaybackSpeed(1400L, 1000L, 0L))
    }
}
