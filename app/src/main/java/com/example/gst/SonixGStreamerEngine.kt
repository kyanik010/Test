package com.example.gst

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Surface
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.freedesktop.gstreamer.GStreamer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

enum class PipelineState(val labelAr: String) {
    IDLE("خامل"),
    PROBING("جاري الفحص"),
    CONNECTING("جاري الاتصال"),
    BUFFERING("تخزين مؤقت"),
    PLAYING("يعمل"),
    PAUSED("متوقف مؤقتاً"),
    RECONNECTING("إعادة اتصال"),
    ERROR("خطأ")
}

enum class ActiveAudioMode(val labelAr: String) {
    ORIGINAL_A("صوت القناة الأصلي A"),
    EXTERNAL_B("الصوت الخارجي B"),
    SWITCHING_TO_B("تحضير B/C (الصوت الحالي مستمر)"),
    FALLBACK_TO_A("تعافي مؤقت بصوت A")
}

data class GstEngineState(
    val nativeLoaded: Boolean = false,
    val nativeError: String? = null,
    val gstreamerVersion: String = "غير محمّل",
    val deviceAbis: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val videoState: PipelineState = PipelineState.IDLE,
    val videoFirstFrameVerified: Boolean = false,
    val videoDecoder: String = "—",
    val videoIsHardwareDecoder: Boolean = false,
    val videoContainerKind: String = "—",
    val videoAudioStreamCount: Int = 0,
    val audioADecoderActive: Boolean = false,
    val audioADecodedFrames: Long = 0L,
    val videoResolution: String = "—",
    val videoPositionMs: Long = 0L,
    val videoRestartCount: Int = 0,
    val videoPlayingContinuouslyMs: Long = 0L,
    val audioMode: ActiveAudioMode = ActiveAudioMode.ORIGINAL_A,
    val audioState: PipelineState = PipelineState.IDLE,
    val audioPaused: Boolean = false,
    val audioExtractionMode: String = "صوت القناة الأصلي",
    val audioDecoder: String = "—",
    val audioBVideoDecodersCreated: Int = 0,
    val audioPositionMs: Long = 0L,
    val videoPtsMs: Long = -1L,
    val videoDtsMs: Long = -1L,
    val audioPtsMs: Long = -1L,
    val hasValidSyncTiming: Boolean = false,
    val baselinePtsOffsetMs: Long = 0L,
    val measuredDriftMs: Long = 0L,
    val clockSkewMs: Long = 0L,
    val audioDelayMs: Long = 0L,
    val audioReconnectAttempt: Int = 0,
    val originalAudioMuted: Boolean = false
)

interface GstDiagnosticSink {
    fun onGstLog(level: String, message: String)
}

/**
 * Dual-pipeline GStreamer JNI controller (ARM64-only build):
 * - Pipeline A: Custom uridecodebin + hardware MediaCodec preference + dynamically unloadable Audio A decoder.
 * - Pipeline B: Single Audio Pipeline with input-selector (active slot + candidate slot for zero-gap switching).
 */
class SonixGStreamerEngine(
    private val context: Context,
    private val diagnosticSink: GstDiagnosticSink
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val lifecycleExecutor = Executors.newSingleThreadExecutor()
    private val _state = MutableStateFlow(GstEngineState())
    val state: StateFlow<GstEngineState> = _state.asStateFlow()

    private val slotCounter = AtomicLong(1L)
    private var currentSurface: Surface? = null
    private var currentVideoUrl: String = ""

    private var activeAudioUrl: String = ""
    private var activeAudioName: String = ""
    private var activeAudioModeLabel: String = ""

    private var pendingSlotId: Long = 0L
    private var pendingCandidateUrl: String = ""
    private var pendingCandidateName: String = ""
    private var pendingCandidateModeLabel: String = ""

    private var externalAudioIntended: Boolean = false
    private var retryRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private var videoSessionStartMs: Long = 0L

    fun initialize(): Boolean {
        if (_state.value.nativeLoaded) return true

        val requiredLibs = listOf("c++_shared", "gstreamer_android", "sonix_gst")
        for (lib in requiredLibs) {
            try {
                System.loadLibrary(lib)
            } catch (t: Throwable) {
                val abiList = Build.SUPPORTED_ABIS.joinToString(", ").ifBlank { "unknown" }
                val msg = "فشل تحميل المكتبة الأصلية lib${lib}.so (المعماريات المدعومة: $abiList): ${t.message ?: t.javaClass.simpleName}"
                _state.update { it.copy(nativeLoaded = false, nativeError = msg) }
                diagnosticSink.onGstLog("ERR", msg)
                return false
            }
        }

        return try {
            GStreamer.init(context.applicationContext)
            val version = nativeInit()
            _state.update {
                it.copy(
                    nativeLoaded = true,
                    nativeError = null,
                    gstreamerVersion = version
                )
            }
            diagnosticSink.onGstLog(
                "GST",
                "تم تحميل lib++_shared وlibgstreamer_android وlibsonix_gst وتهيئة $version (arm64-v8a)"
            )
            startTelemetryPoll()
            true
        } catch (t: Throwable) {
            val msg = "فشل تهيئة محرك GStreamer بعد تحميل المكتبات: ${t.message ?: t.javaClass.simpleName}"
            _state.update { it.copy(nativeLoaded = false, nativeError = msg) }
            diagnosticSink.onGstLog("ERR", msg)
            false
        }
    }

    fun attachSurface(surface: Surface?) {
        currentSurface = surface
        if (_state.value.nativeLoaded) {
            nativeSetSurface(surface)
        }
    }

    fun playVideoChannel(url: String, channelName: String) {
        currentVideoUrl = url
        videoSessionStartMs = 0L
        _state.update {
            it.copy(
                videoState = PipelineState.CONNECTING,
                videoFirstFrameVerified = false,
                videoDecoder = "جاري الاكتشاف...",
                videoIsHardwareDecoder = false,
                videoContainerKind = "جاري الاكتشاف...",
                videoAudioStreamCount = 0,
                videoResolution = "—",
                videoPlayingContinuouslyMs = 0L,
                videoRestartCount = it.videoRestartCount + 1
            )
        }
        diagnosticSink.onGstLog("VID", "تشغيل Video Pipeline A للقناة [$channelName] (بدون طلب PROBE إضافي)")
        if (!_state.value.nativeLoaded && !initialize()) {
            _state.update { it.copy(videoState = PipelineState.ERROR) }
            return
        }
        nativeSetSurface(currentSurface)
        nativePlayVideo(url)
    }

    fun setVideoPaused(paused: Boolean) {
        if (!_state.value.nativeLoaded) return
        nativeSetVideoPaused(paused)
        _state.update {
            it.copy(
                videoState = if (paused) PipelineState.PAUSED else if (it.videoFirstFrameVerified) PipelineState.PLAYING else PipelineState.BUFFERING
            )
        }
        diagnosticSink.onGstLog("VID", if (paused) "تم إيقاف الفيديو A مؤقتاً (الصوت B مستقل لم يتأثر)" else "استئناف الفيديو A")
    }

    fun setAudioPaused(paused: Boolean) {
        if (!_state.value.nativeLoaded) return
        nativeSetAudioPaused(paused)
        _state.update {
            it.copy(
                audioPaused = paused,
                audioState = if (paused) PipelineState.PAUSED else if (it.audioMode == ActiveAudioMode.EXTERNAL_B) PipelineState.PLAYING else it.audioState
            )
        }
        diagnosticSink.onGstLog("AUD", if (paused) "تم إيقاف الصوت B مؤقتاً (الفيديو A مستمر)" else "استئناف الصوت B")
    }

    fun stopVideoOnly() {
        if (!_state.value.nativeLoaded) return
        nativeStopVideo()
        videoSessionStartMs = 0L
        _state.update {
            it.copy(
                videoState = PipelineState.IDLE,
                videoFirstFrameVerified = false,
                videoPlayingContinuouslyMs = 0L
            )
        }
        diagnosticSink.onGstLog("VID", "تم إيقاف Video Pipeline A فقط (الصوت B مستقل)")
    }

    fun setVideoVolume(volume: Float) {
        if (!_state.value.nativeLoaded) return
        nativeSetVideoVolume(volume.coerceIn(0f, 1f))
    }

    fun attachOrSwitchExternalAudio(
        url: String,
        channelName: String,
        extractionModeLabel: String
    ) {
        cancelRetry()
        externalAudioIntended = true
        val slotId = slotCounter.incrementAndGet()
        pendingSlotId = slotId
        pendingCandidateUrl = url
        pendingCandidateName = channelName
        pendingCandidateModeLabel = extractionModeLabel

        val hasActiveB = activeAudioUrl.isNotBlank() && _state.value.audioMode == ActiveAudioMode.EXTERNAL_B
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.SWITCHING_TO_B,
                audioState = PipelineState.CONNECTING,
                audioPaused = false,
                audioExtractionMode = if (hasActiveB) "تحضير $channelName (الحالي مستمر)" else extractionModeLabel,
                audioReconnectAttempt = 0
            )
        }
        diagnosticSink.onGstLog(
            "AUD",
            if (hasActiveB) {
                "تحضير المرشح C [$channelName] مع استمرار تشغيل الصوت B الحالي دون انقطاع..."
            } else {
                "تحضير Audio Pipeline B لـ [$channelName] ($extractionModeLabel) — الفيديو A مستمر"
            }
        )

        if (!_state.value.nativeLoaded && !initialize()) {
            _state.update {
                it.copy(
                    audioMode = if (hasActiveB) ActiveAudioMode.EXTERNAL_B else ActiveAudioMode.FALLBACK_TO_A,
                    audioState = PipelineState.ERROR
                )
            }
            return
        }
        nativeAttachOrSwitchExternalAudio(url, _state.value.audioDelayMs, slotId)
    }

    fun detachExternalAudio(restoreOriginalA: Boolean = true) {
        cancelRetry()
        externalAudioIntended = false
        pendingSlotId = 0L
        pendingCandidateUrl = ""
        activeAudioUrl = ""
        activeAudioName = ""
        activeAudioModeLabel = ""

        if (_state.value.nativeLoaded) {
            nativeStopExternalAudio()
            nativeSetOriginalAudioEnabled(restoreOriginalA)
        }
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.ORIGINAL_A,
                audioState = PipelineState.IDLE,
                audioPaused = false,
                audioExtractionMode = if (restoreOriginalA) "صوت القناة الأصلي A" else "صوت B مفصول (صامت)",
                audioDecoder = "—",
                audioBVideoDecodersCreated = 0,
                audioPositionMs = 0L,
                audioPtsMs = -1L,
                hasValidSyncTiming = false,
                baselinePtsOffsetMs = 0L,
                measuredDriftMs = 0L,
                clockSkewMs = 0L,
                audioReconnectAttempt = 0,
                originalAudioMuted = !restoreOriginalA
            )
        }
        diagnosticSink.onGstLog(
            "AUD",
            if (restoreOriginalA) "تم إغلاق B وتفعيل مفكك الصوت الأصلي A فوراً دون إعادة تشغيل الفيديو" else "تم إغلاق الصوت الخارجي B مع بقاء صوت A متوقفاً"
        )
    }

    fun setAudioDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-5000L, 5000L)
        _state.update { it.copy(audioDelayMs = clamped) }
        if (_state.value.nativeLoaded) {
            nativeSetAudioDelayMs(clamped)
        }
    }

    fun forceReconnectExternalAudio() {
        val url = activeAudioUrl.ifBlank { pendingCandidateUrl }
        val name = activeAudioName.ifBlank { pendingCandidateName }
        val mode = activeAudioModeLabel.ifBlank { pendingCandidateModeLabel }
        if (url.isBlank() || !externalAudioIntended) {
            diagnosticSink.onGstLog("AUD", "لا يوجد صوت خارجي مفعل لإعادة الاتصال")
            return
        }
        diagnosticSink.onGstLog("AUD", "اختبار إعادة اتصال الصوت B [$name] مع بقاء الفيديو A مستمراً...")
        if (_state.value.nativeLoaded) {
            nativeStopExternalAudio()
            nativeSetOriginalAudioEnabled(true)
        }
        activeAudioUrl = ""
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.FALLBACK_TO_A,
                audioState = PipelineState.RECONNECTING,
                audioPaused = false,
                originalAudioMuted = false
            )
        }
        pendingCandidateUrl = url
        pendingCandidateName = name
        pendingCandidateModeLabel = mode
        scheduleReconnect(immediate = true)
    }

    fun release() {
        cancelRetry()
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        val wasLoaded = _state.value.nativeLoaded
        _state.update { it.copy(nativeLoaded = false) }
        if (wasLoaded) {
            lifecycleExecutor.execute {
                nativeShutdown()
            }
        }
    }

    private fun startTelemetryPoll() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                if (_state.value.nativeLoaded) {
                    val snap = nativeQuerySyncSnapshot()
                    if (snap.size >= 12) {
                        val videoPosMs = snap[0].coerceAtLeast(0L)
                        val audioPosMs = snap[1].coerceAtLeast(0L)
                        val videoPtsMs = snap[3]
                        val videoDtsMs = snap[4]
                        val audioPtsMs = snap[5]
                        val validPts = snap[6] == 1L
                        val baseOffsetMs = snap[7]
                        val realDriftMs = if (validPts && _state.value.audioMode == ActiveAudioMode.EXTERNAL_B) snap[8] else 0L
                        val clockSkewMs = if (validPts && _state.value.audioMode == ActiveAudioMode.EXTERNAL_B) snap[9] else 0L
                        val bVideoDecoders = snap[10].toInt()
                        val audioAFrames = snap[11]

                        val continuousMs = if (_state.value.videoState == PipelineState.PLAYING && videoSessionStartMs > 0L) {
                            System.currentTimeMillis() - videoSessionStartMs
                        } else {
                            0L
                        }

                        _state.update {
                            it.copy(
                                videoPositionMs = videoPosMs,
                                audioPositionMs = audioPosMs,
                                videoPtsMs = videoPtsMs,
                                videoDtsMs = videoDtsMs,
                                audioPtsMs = audioPtsMs,
                                hasValidSyncTiming = validPts,
                                baselinePtsOffsetMs = baseOffsetMs,
                                measuredDriftMs = realDriftMs,
                                clockSkewMs = clockSkewMs,
                                audioBVideoDecodersCreated = bVideoDecoders,
                                audioADecodedFrames = audioAFrames,
                                videoPlayingContinuouslyMs = continuousMs
                            )
                        }
                    }
                }
                mainHandler.postDelayed(this, 500L)
            }
        }
        pollRunnable = runnable
        mainHandler.postDelayed(runnable, 500L)
    }

    private fun scheduleReconnect(immediate: Boolean = false) {
        val targetUrl = pendingCandidateUrl.ifBlank { activeAudioUrl }
        val targetName = pendingCandidateName.ifBlank { activeAudioName }
        val targetMode = pendingCandidateModeLabel.ifBlank { activeAudioModeLabel }
        if (!externalAudioIntended || targetUrl.isBlank()) return

        val nextAttempt = (_state.value.audioReconnectAttempt + 1).coerceAtMost(5)
        val delayMs = if (immediate) 350L else when (nextAttempt) {
            1 -> 1000L
            2 -> 2500L
            3 -> 5000L
            4 -> 9000L
            else -> 15000L
        }
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.FALLBACK_TO_A,
                audioState = PipelineState.RECONNECTING,
                audioReconnectAttempt = nextAttempt,
                originalAudioMuted = false
            )
        }
        diagnosticSink.onGstLog(
            "REC",
            "جدولة إعادة اتصال [$targetName] محاولة #$nextAttempt خلال ${delayMs}ms (صوت A مستمر كاحتياط)"
        )
        cancelRetry()
        val capturedAttemptSlot = slotCounter.incrementAndGet()
        pendingSlotId = capturedAttemptSlot
        val r = Runnable {
            if (externalAudioIntended && targetUrl.isNotBlank() && _state.value.nativeLoaded && pendingSlotId == capturedAttemptSlot) {
                pendingCandidateUrl = targetUrl
                pendingCandidateName = targetName
                pendingCandidateModeLabel = targetMode
                nativeAttachOrSwitchExternalAudio(targetUrl, _state.value.audioDelayMs, capturedAttemptSlot)
            }
        }
        retryRunnable = r
        mainHandler.postDelayed(r, delayMs)
    }

    private fun cancelRetry() {
        retryRunnable?.let(mainHandler::removeCallbacks)
        retryRunnable = null
    }

    @Suppress("unused")
    private fun onNativeVideoState(stateCode: Int, info: String) {
        mainHandler.post {
            val mapped = when (stateCode) {
                1 -> PipelineState.BUFFERING
                2 -> PipelineState.PLAYING
                3 -> PipelineState.PAUSED
                4 -> PipelineState.ERROR
                else -> PipelineState.IDLE
            }
            val verifiedFirstFrame = if (stateCode == 2) true else if (stateCode == 4 || stateCode == 0) false else _state.value.videoFirstFrameVerified
            if (stateCode == 2 && videoSessionStartMs == 0L) {
                videoSessionStartMs = System.currentTimeMillis()
            }
            _state.update {
                it.copy(
                    videoState = mapped,
                    videoFirstFrameVerified = verifiedFirstFrame,
                    videoDecoder = if (info.isNotBlank() && stateCode == 2) info else it.videoDecoder
                )
            }
            if (info.isNotBlank()) {
                diagnosticSink.onGstLog("VID", "حالة الفيديو A: ${mapped.labelAr} ($info)")
            }
        }
    }

    @Suppress("unused")
    private fun onNativeVideoFormat(width: Int, height: Int, decoderName: String, isHardware: Boolean) {
        mainHandler.post {
            val res = if (width > 0 && height > 0) "${width}x${height}" else "—"
            val displayDec = if (decoderName.isBlank()) "—" else if (isHardware) "$decoderName (HW)" else "$decoderName (SW)"
            _state.update {
                it.copy(
                    videoResolution = res,
                    videoDecoder = displayDec,
                    videoIsHardwareDecoder = isHardware
                )
            }
            diagnosticSink.onGstLog(
                "DEC",
                "فك ترميز A: $displayDec | الدقة: $res"
            )
        }
    }

    @Suppress("unused")
    private fun onNativeVideoTopology(containerKind: String, audioTrackCount: Int, audioADecoderActive: Boolean) {
        mainHandler.post {
            _state.update {
                it.copy(
                    videoContainerKind = containerKind,
                    videoAudioStreamCount = audioTrackCount,
                    audioADecoderActive = audioADecoderActive
                )
            }
        }
    }

    @Suppress("unused")
    private fun onNativeAudioState(stateCode: Int, info: String) {
        mainHandler.post {
            val mapped = when (stateCode) {
                1 -> PipelineState.BUFFERING
                2 -> PipelineState.PLAYING
                3 -> PipelineState.ERROR
                else -> PipelineState.IDLE
            }
            _state.update {
                it.copy(
                    audioState = mapped,
                    audioDecoder = if (info.isNotBlank() && stateCode == 2) info else it.audioDecoder
                )
            }
            if (stateCode == 3 && externalAudioIntended) {
                val isProviderLimitReject = info.contains("HTTP_REJECT") || info.contains("403") || info.contains("429") || info.contains("509")
                nativeSetOriginalAudioEnabled(true)
                activeAudioUrl = ""
                if (isProviderLimitReject) {
                    externalAudioIntended = false
                    _state.update {
                        it.copy(
                            audioMode = ActiveAudioMode.ORIGINAL_A,
                            audioState = PipelineState.ERROR,
                            originalAudioMuted = false
                        )
                    }
                    diagnosticSink.onGstLog(
                        "ERR",
                        "رفض مزود الخدمة فتح اتصال الصوت B ($info) — تم التوقف والعودة لصوت A دون تكرار الطلبات"
                    )
                } else {
                    diagnosticSink.onGstLog("ERR", "انقطاع Active Audio B ($info) — تفعيل صوت A مؤقتاً وجدولة إعادة الاتصال")
                    scheduleReconnect(immediate = false)
                }
            }
        }
    }

    @Suppress("unused")
    private fun onNativeCandidateAudioPromoted(slotId: Long, decoderName: String, containerKind: String, bVideoDecoders: Int) {
        mainHandler.post {
            if (!externalAudioIntended || slotId != pendingSlotId) return@post

            activeAudioUrl = pendingCandidateUrl
            activeAudioName = pendingCandidateName
            activeAudioModeLabel = pendingCandidateModeLabel.ifBlank { containerKind }

            /* Tear down Audio A decoder AFTER Audio B/C is verified and active on input-selector */
            nativeSetOriginalAudioEnabled(false)

            _state.update {
                it.copy(
                    audioMode = ActiveAudioMode.EXTERNAL_B,
                    audioState = PipelineState.PLAYING,
                    audioPaused = false,
                    audioDecoder = decoderName.ifBlank { "GStreamer Audio" },
                    audioExtractionMode = "$activeAudioModeLabel ($containerKind)",
                    audioBVideoDecodersCreated = bVideoDecoders,
                    audioADecoderActive = false,
                    audioReconnectAttempt = 0,
                    originalAudioMuted = true
                )
            }
            diagnosticSink.onGstLog(
                "SYNC",
                "تم التحقق من أول PCM لـ [$activeAudioName] ($decoderName | $containerKind | مفككات فيديو B=$bVideoDecoders) -> تم تفريغ مفكك صوت A واستمرار الفيديو دون انقطاع"
            )
        }
    }

    @Suppress("unused")
    private fun onNativeCandidateAudioFailed(slotId: Long, errKind: String, errorMsg: String) {
        mainHandler.post {
            if (slotId != pendingSlotId) return@post
            val activeStillPlaying = activeAudioUrl.isNotBlank()
            if (activeStillPlaying) {
                /* Candidate C failed -> B stays alive undisturbed! */
                _state.update {
                    it.copy(
                        audioMode = ActiveAudioMode.EXTERNAL_B,
                        audioState = PipelineState.PLAYING,
                        audioExtractionMode = activeAudioModeLabel
                    )
                }
                diagnosticSink.onGstLog(
                    "ERR",
                    "فشل المصدر المرشح [$pendingCandidateName] ($errKind): $errorMsg — بقي الصوت الحالي [$activeAudioName] مستمراً"
                )
            } else if (externalAudioIntended) {
                val isProviderLimitReject = errKind == "HTTP_REJECT" || errorMsg.contains("403") || errorMsg.contains("429") || errorMsg.contains("509")
                nativeSetOriginalAudioEnabled(true)
                if (isProviderLimitReject) {
                    externalAudioIntended = false
                    _state.update {
                        it.copy(
                            audioMode = ActiveAudioMode.ORIGINAL_A,
                            audioState = PipelineState.ERROR,
                            originalAudioMuted = false
                        )
                    }
                    diagnosticSink.onGstLog(
                        "ERR",
                        "رفض الخادم فتح الصوت B بسبب قيود المزود ($errorMsg) — بقي الفيديو A والصوت A يعملان"
                    )
                } else {
                    diagnosticSink.onGstLog("ERR", "فشل تحضير الصوت B ($errorMsg) — الإبقاء على صوت A وجدولة إعادة المحاولة")
                    scheduleReconnect(immediate = false)
                }
            }
        }
    }

    @Suppress("unused")
    private fun onNativeLog(level: String, msg: String) {
        mainHandler.post {
            diagnosticSink.onGstLog(level, msg)
        }
    }

    private external fun nativeInit(): String
    private external fun nativeSetSurface(surface: Surface?)
    private external fun nativePlayVideo(url: String)
    private external fun nativeSetVideoPaused(paused: Boolean)
    private external fun nativeSetAudioPaused(paused: Boolean)
    private external fun nativeStopVideo()
    private external fun nativeSetOriginalAudioEnabled(enabled: Boolean)
    private external fun nativeSetVideoVolume(volume: Float)
    private external fun nativeAttachOrSwitchExternalAudio(url: String, delayMs: Long, slotId: Long)
    private external fun nativeStopExternalAudio()
    private external fun nativeSetAudioDelayMs(delayMs: Long)
    private external fun nativeQuerySyncSnapshot(): LongArray
    private external fun nativeShutdown()
}
