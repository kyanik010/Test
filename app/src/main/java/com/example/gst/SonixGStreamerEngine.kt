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
    SWITCHING_TO_B("تحضير الصوت B (صوت A مستمر)"),
    FALLBACK_TO_A("تعافي مؤقت بصوت A")
}

data class GstEngineState(
    val nativeLoaded: Boolean = false,
    val nativeError: String? = null,
    val gstreamerVersion: String = "غير محمّل",
    val deviceAbis: String = Build.SUPPORTED_ABIS.joinToString(", "),
    val videoState: PipelineState = PipelineState.IDLE,
    val videoDecoder: String = "—",
    val videoResolution: String = "—",
    val videoPositionMs: Long = 0L,
    val videoRestartCount: Int = 0,
    val videoPlayingContinuouslyMs: Long = 0L,
    val audioMode: ActiveAudioMode = ActiveAudioMode.ORIGINAL_A,
    val audioState: PipelineState = PipelineState.IDLE,
    val audioExtractionMode: String = "صوت القناة الأصلي",
    val audioDecoder: String = "—",
    val audioPositionMs: Long = 0L,
    val measuredDriftMs: Long = 0L,
    val audioDelayMs: Long = 0L,
    val audioReconnectAttempt: Int = 0,
    val originalAudioMuted: Boolean = false
)

interface GstDiagnosticSink {
    fun onGstLog(level: String, message: String)
}

/**
 * Dual-pipeline GStreamer JNI controller.
 * - Pipeline A: Video (with hardware MediaCodec amcviddec preference) + Original Audio A (muteable without interrupting video).
 * - Pipeline B: Independent Audio-only extraction pipeline (drops non-audio pads without video decode).
 */
class SonixGStreamerEngine(
    private val context: Context,
    private val diagnosticSink: GstDiagnosticSink
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow(GstEngineState())
    val state: StateFlow<GstEngineState> = _state.asStateFlow()

    private var currentSurface: Surface? = null
    private var currentVideoUrl: String = ""
    private var currentExternalAudioUrl: String = ""
    private var externalAudioIntended: Boolean = false
    private var retryRunnable: Runnable? = null
    private var pollRunnable: Runnable? = null
    private var videoSessionStartMs: Long = 0L

    fun initialize(): Boolean {
        if (_state.value.nativeLoaded) return true
        return try {
            System.loadLibrary("gstreamer_android")
            System.loadLibrary("sonix_gst")
            GStreamer.init(context.applicationContext)
            val version = nativeInit()
            _state.update {
                it.copy(
                    nativeLoaded = true,
                    nativeError = null,
                    gstreamerVersion = version
                )
            }
            diagnosticSink.onGstLog("GST", "تمت تهيئة محرك GStreamer بنجاح: $version (${Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown"})")
            startTelemetryPoll()
            true
        } catch (t: Throwable) {
            val msg = "تعذر تحميل مكتبات GStreamer الأصلية (ARMv7/ARM64): ${t.message ?: t.javaClass.simpleName}"
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
        videoSessionStartMs = System.currentTimeMillis()
        _state.update {
            it.copy(
                videoState = PipelineState.CONNECTING,
                videoDecoder = "جاري الاكتشاف...",
                videoResolution = "—",
                videoPlayingContinuouslyMs = 0L,
                videoRestartCount = it.videoRestartCount + 1
            )
        }
        diagnosticSink.onGstLog("VID", "تشغيل Video Pipeline A للقناة: $channelName")
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
            it.copy(videoState = if (paused) PipelineState.PAUSED else PipelineState.PLAYING)
        }
    }

    fun attachOrSwitchExternalAudio(
        url: String,
        channelName: String,
        extractionModeLabel: String
    ) {
        cancelRetry()
        currentExternalAudioUrl = url
        externalAudioIntended = true
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.SWITCHING_TO_B,
                audioState = PipelineState.CONNECTING,
                audioExtractionMode = extractionModeLabel,
                audioDecoder = "جاري الاكتشاف...",
                audioReconnectAttempt = 0
            )
        }
        diagnosticSink.onGstLog("AUD", "بدء/تبديل Audio Pipeline B إلى [$channelName] ($extractionModeLabel) — الفيديو A مستمر")
        if (!_state.value.nativeLoaded && !initialize()) {
            _state.update {
                it.copy(
                    audioMode = ActiveAudioMode.FALLBACK_TO_A,
                    audioState = PipelineState.ERROR
                )
            }
            return
        }
        nativeStartExternalAudio(url, _state.value.audioDelayMs)
    }

    fun detachExternalAudio(restoreOriginalA: Boolean = true) {
        cancelRetry()
        externalAudioIntended = false
        currentExternalAudioUrl = ""
        if (_state.value.nativeLoaded) {
            nativeStopExternalAudio()
            if (restoreOriginalA) {
                nativeMuteOriginalAudio(false)
            }
        }
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.ORIGINAL_A,
                audioState = PipelineState.IDLE,
                audioExtractionMode = "صوت القناة الأصلي A",
                audioDecoder = "—",
                audioPositionMs = 0L,
                measuredDriftMs = 0L,
                audioReconnectAttempt = 0,
                originalAudioMuted = !restoreOriginalA
            )
        }
        diagnosticSink.onGstLog("AUD", if (restoreOriginalA) "تم فصل الصوت الخارجي والعودة الفورية لصوت القناة A الأصلي" else "تم فصل الصوت الخارجي")
    }

    fun setAudioDelayMs(delayMs: Long) {
        val clamped = delayMs.coerceIn(-5000L, 5000L)
        _state.update { it.copy(audioDelayMs = clamped) }
        if (_state.value.nativeLoaded) {
            nativeSetAudioDelayMs(clamped)
        }
    }

    fun forceReconnectExternalAudio() {
        val url = currentExternalAudioUrl
        if (url.isBlank() || !externalAudioIntended) {
            diagnosticSink.onGstLog("AUD", "لا يوجد صوت خارجي مفعل لإعادة الاتصال")
            return
        }
        diagnosticSink.onGstLog("AUD", "اختبار إعادة اتصال قسري لمسار الصوت B (مع بقاء الفيديو A مستمراً)...")
        if (_state.value.nativeLoaded) {
            nativeMuteOriginalAudio(false)
            nativeStopExternalAudio()
        }
        _state.update {
            it.copy(
                audioMode = ActiveAudioMode.FALLBACK_TO_A,
                audioState = PipelineState.RECONNECTING,
                originalAudioMuted = false
            )
        }
        scheduleReconnect(immediate = true)
    }

    fun release() {
        cancelRetry()
        pollRunnable?.let(mainHandler::removeCallbacks)
        pollRunnable = null
        if (_state.value.nativeLoaded) {
            nativeShutdown()
        }
    }

    private fun startTelemetryPoll() {
        pollRunnable?.let(mainHandler::removeCallbacks)
        val runnable = object : Runnable {
            override fun run() {
                if (_state.value.nativeLoaded) {
                    val snapshot = nativeQuerySyncSnapshot()
                    if (snapshot.size >= 3) {
                        val videoPosMs = snapshot[0]
                        val audioPosMs = snapshot[1]
                        val driftMs = if (_state.value.audioMode == ActiveAudioMode.EXTERNAL_B && videoPosMs > 0 && audioPosMs > 0) {
                            (videoPosMs - audioPosMs)
                        } else {
                            0L
                        }
                        val continuousMs = if (_state.value.videoState == PipelineState.PLAYING && videoSessionStartMs > 0L) {
                            System.currentTimeMillis() - videoSessionStartMs
                        } else {
                            0L
                        }
                        _state.update {
                            it.copy(
                                videoPositionMs = videoPosMs.coerceAtLeast(0L),
                                audioPositionMs = audioPosMs.coerceAtLeast(0L),
                                measuredDriftMs = driftMs,
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
        if (!externalAudioIntended || currentExternalAudioUrl.isBlank()) return
        val nextAttempt = (_state.value.audioReconnectAttempt + 1).coerceAtMost(5)
        val delayMs = if (immediate) 400L else when (nextAttempt) {
            1 -> 1000L
            2 -> 2000L
            3 -> 4000L
            4 -> 8000L
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
        diagnosticSink.onGstLog("REC", "جدولة إعادة اتصال الصوت B محاولة #$nextAttempt خلال ${delayMs}ms (صوت A مفعل مؤقتاً)")
        cancelRetry()
        val r = Runnable {
            if (externalAudioIntended && currentExternalAudioUrl.isNotBlank() && _state.value.nativeLoaded) {
                nativeStartExternalAudio(currentExternalAudioUrl, _state.value.audioDelayMs)
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
            _state.update {
                it.copy(
                    videoState = mapped,
                    videoDecoder = if (info.isNotBlank() && stateCode == 2) info else it.videoDecoder
                )
            }
            if (info.isNotBlank()) {
                diagnosticSink.onGstLog("VID", "حالة الفيديو A: ${mapped.labelAr} ($info)")
            }
        }
    }

    @Suppress("unused")
    private fun onNativeVideoFormat(width: Int, height: Int, decoderName: String) {
        mainHandler.post {
            val res = if (width > 0 && height > 0) "${width}x${height}" else "—"
            _state.update {
                it.copy(
                    videoResolution = res,
                    videoDecoder = decoderName.ifBlank { it.videoDecoder }
                )
            }
            diagnosticSink.onGstLog("DEC", "مفكك الفيديو A: $decoderName | الدقة: $res")
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
                nativeMuteOriginalAudio(false)
                diagnosticSink.onGstLog("ERR", "انقطاع أو فشل Audio Pipeline B: $info — استعادة صوت A فوراً")
                scheduleReconnect(immediate = false)
            }
        }
    }

    @Suppress("unused")
    private fun onNativeFirstExternalAudioBuffer(decoderName: String) {
        mainHandler.post {
            if (!externalAudioIntended) return@post
            nativeMuteOriginalAudio(true)
            _state.update {
                it.copy(
                    audioMode = ActiveAudioMode.EXTERNAL_B,
                    audioState = PipelineState.PLAYING,
                    audioDecoder = decoderName.ifBlank { it.audioDecoder },
                    audioReconnectAttempt = 0,
                    originalAudioMuted = true
                )
            }
            diagnosticSink.onGstLog("SYNC", "تم التحقق من أول إطارات PCM في الصوت B ($decoderName) -> كتم صوت A الأصلي مع استمرار الفيديو دون انقطاع")
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
    private external fun nativeMuteOriginalAudio(muted: Boolean)
    private external fun nativeStartExternalAudio(url: String, delayMs: Long)
    private external fun nativeStopExternalAudio()
    private external fun nativeSetAudioDelayMs(delayMs: Long)
    private external fun nativeQuerySyncSnapshot(): LongArray
    private external fun nativeShutdown()
}
