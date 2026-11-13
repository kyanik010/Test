package com.example.ui

import android.app.Application
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.CredentialSanitizer
import com.example.gst.GstDiagnosticSink
import com.example.gst.GstEngineState
import com.example.gst.SonixGStreamerEngine
import com.example.iptv.IptvAccountInfo
import com.example.iptv.IptvChannel
import com.example.iptv.IptvSubscriptionClient
import com.example.iptv.StreamFormatPreference
import com.example.iptv.StreamStructureInspector
import com.example.iptv.StreamStructureReport
import com.example.iptv.SubscriptionMode
import com.example.telemetry.SystemTelemetryMonitor
import com.example.telemetry.SystemTelemetrySnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class DiagnosticLogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

data class SonixLabUiState(
    val subscriptionMode: SubscriptionMode = SubscriptionMode.XTREAM,
    val serverUrl: String = "",
    val username: String = "",
    val password: String = "",
    val m3uUrl: String = "",
    val formatPreference: StreamFormatPreference = StreamFormatPreference.AUTO,
    val subscriptionExpanded: Boolean = true,
    val isLoadingCatalog: Boolean = false,
    val catalogError: String? = null,
    val providerLimitNotice: String? = null,
    val accountInfo: IptvAccountInfo? = null,
    val allChannels: List<IptvChannel> = emptyList(),
    val searchQuery: String = "",
    val activeVideoChannel: IptvChannel? = null,
    val selectedAudioCandidate: IptvChannel? = null,
    val activeAudioChannel: IptvChannel? = null,
    val videoStructureReport: StreamStructureReport? = null,
    val audioStructureReport: StreamStructureReport? = null,
    val isInspectingAudio: Boolean = false,
    val isPaused: Boolean = false,
    val gstState: GstEngineState = GstEngineState(),
    val telemetry: SystemTelemetrySnapshot = SystemTelemetrySnapshot(),
    val logs: List<DiagnosticLogEntry> = emptyList()
)

class SonixLabViewModel(application: Application) : AndroidViewModel(application), GstDiagnosticSink {

    private val _uiState = MutableStateFlow(SonixLabUiState())
    val uiState: StateFlow<SonixLabUiState> = _uiState.asStateFlow()

    private val gstEngine = SonixGStreamerEngine(application.applicationContext, this)
    private val telemetryMonitor = SystemTelemetryMonitor()
    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)
    private var audioInspectJob: Job? = null

    init {
        onGstLog("SYS", "بدء مختبر SONIX GStreamer Lab (arm64-v8a) | معماريات الجهاز: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
        gstEngine.initialize()

        viewModelScope.launch {
            gstEngine.state.collect { gst ->
                _uiState.update { it.copy(gstState = gst) }
            }
        }

        viewModelScope.launch(Dispatchers.Default) {
            while (isActive) {
                val snap = telemetryMonitor.sample()
                _uiState.update { it.copy(telemetry = snap) }
                delay(1000L)
            }
        }
    }

    fun setSubscriptionMode(mode: SubscriptionMode) {
        _uiState.update { it.copy(subscriptionMode = mode, catalogError = null) }
    }

    fun setServerUrl(value: String) = _uiState.update { it.copy(serverUrl = value) }
    fun setUsername(value: String) = _uiState.update { it.copy(username = value) }
    fun setPassword(value: String) = _uiState.update { it.copy(password = value) }
    fun setM3uUrl(value: String) = _uiState.update { it.copy(m3uUrl = value) }
    fun setFormatPreference(pref: StreamFormatPreference) = _uiState.update { it.copy(formatPreference = pref) }
    fun toggleSubscriptionPanel() = _uiState.update { it.copy(subscriptionExpanded = !it.subscriptionExpanded) }
    fun setSearchQuery(query: String) = _uiState.update { it.copy(searchQuery = query) }

    fun attachVideoSurface(surface: Surface?) {
        gstEngine.attachSurface(surface)
    }

    fun loadSubscriptionChannels() {
        val current = _uiState.value
        if (current.isLoadingCatalog) return

        StreamStructureInspector.clearCache()
        _uiState.update { it.copy(isLoadingCatalog = true, catalogError = null, providerLimitNotice = null) }
        onGstLog("IPTV", "جلب القنوات من اشتراك ${if (current.subscriptionMode == SubscriptionMode.XTREAM) "Xtream Codes" else "M3U"} واحد...")

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val loaded = when (current.subscriptionMode) {
                    SubscriptionMode.XTREAM -> IptvSubscriptionClient.loadFromXtream(
                        rawServer = current.serverUrl,
                        rawUsername = current.username,
                        rawPassword = current.password,
                        formatPreference = current.formatPreference
                    )
                    SubscriptionMode.M3U -> IptvSubscriptionClient.loadFromM3uUrl(current.m3uUrl)
                }

                loaded.warnings.forEach { warn -> onGstLog("WARN", warn) }

                val limitWarning = loaded.accountInfo?.let { acc ->
                    onGstLog(
                        "AUTH",
                        "اشتراك صالح (${acc.status}) | الاتصالات: active=${acc.activeConnections ?: 0} / max=${acc.maxConnections ?: "غير محدد"} | الصيغ: ${acc.allowedFormats.joinToString(",")}"
                    )
                    if (acc.maxConnections != null && acc.maxConnections <= 1) {
                        "قيد مزود الخدمة: max_connections=${acc.maxConnections} (active=${acc.activeConnections ?: 0}). لا يمكن فتح قناتين مستقلتين بالتوازي من هذا الاشتراك."
                    } else null
                }

                val channels = loaded.channels
                onGstLog("IPTV", "تم تحميل ${channels.size} قناة بنجاح من الاشتراك الواحد")

                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        accountInfo = loaded.accountInfo,
                        providerLimitNotice = limitWarning,
                        allChannels = channels,
                        subscriptionExpanded = channels.isEmpty()
                    )
                }
            } catch (e: Exception) {
                val safeMsg = CredentialSanitizer.sanitizeMessage(e.message ?: "تعذر تحميل القنوات")
                onGstLog("ERR", safeMsg)
                _uiState.update { it.copy(isLoadingCatalog = false, catalogError = safeMsg) }
            }
        }
    }

    fun selectVideoChannel(channel: IptvChannel) {
        _uiState.update {
            it.copy(
                activeVideoChannel = channel,
                videoStructureReport = null,
                isPaused = false
            )
        }
        /* No extra HTTP PROBE-A connection: GStreamer inspects container/codecs directly from the stream! */
        gstEngine.playVideoChannel(channel.streamUrl, channel.name)
    }

    fun selectAudioCandidate(channel: IptvChannel) {
        _uiState.update { it.copy(selectedAudioCandidate = channel) }
    }

    fun activateSelectedAudio() {
        val snapshot = _uiState.value
        val candidate = snapshot.selectedAudioCandidate ?: return
        val activeVideo = snapshot.activeVideoChannel

        if (activeVideo != null && activeVideo.id == candidate.id && activeVideo.streamUrl == candidate.streamUrl) {
            onGstLog("WARN", "القناة المختارة للصوت هي نفس قناة الفيديو الحالية — سيتم استخدام الصوت الأصلي A")
            return
        }

        val maxCons = snapshot.accountInfo?.maxConnections
        val activeCons = snapshot.accountInfo?.activeConnections ?: 0
        if (maxCons != null && maxCons <= 1 && activeVideo != null) {
            val msg = "غير مدعوم من مزود الخدمة: الاشتراك يسمح باتصال متزامن واحد فقط " +
                "(max_connections=$maxCons, active_cons=$activeCons) ولا يوفّر دمج قناتين مستقلتين في جلسة واحدة. " +
                "لن يتم التحايل أو استخدام اشتراك ثانٍ أو فتح اتصال ثانٍ يقطع الفيديو A."
            onGstLog("WARN", msg)
            _uiState.update { it.copy(providerLimitNotice = msg) }
            return
        }

        audioInspectJob?.cancel()
        _uiState.update {
            it.copy(
                activeAudioChannel = candidate,
                isInspectingAudio = true,
                audioStructureReport = null,
                providerLimitNotice = null
            )
        }

        audioInspectJob = viewModelScope.launch(Dispatchers.IO) {
            val report = StreamStructureInspector.inspectForAudioRenditionIfHls(candidate.streamUrl)
            _uiState.update { it.copy(audioStructureReport = report, isInspectingAudio = false) }
            onGstLog("AUD-SRC", "[${candidate.name}] ${report.summary}")

            val targetUrl = report.audioOnlyUrl ?: candidate.streamUrl
            val modeLabel = when {
                report.hasIndependentAudioRendition -> "HLS Audio-Only Rendition"
                report.containerKind == "MPEG-TS" -> "MPEG-TS Audio Demux (دون فك فيديو B)"
                else -> "${report.containerKind} (استخراج الصوت فقط)"
            }
            gstEngine.attachOrSwitchExternalAudio(targetUrl, candidate.name, modeLabel)
        }
    }

    fun restoreOriginalAudio() {
        audioInspectJob?.cancel()
        _uiState.update { it.copy(activeAudioChannel = null, isInspectingAudio = false) }
        gstEngine.detachExternalAudio(restoreOriginalA = true)
    }

    fun detachExternalAudioOnly() {
        audioInspectJob?.cancel()
        _uiState.update { it.copy(activeAudioChannel = null, isInspectingAudio = false) }
        gstEngine.detachExternalAudio(restoreOriginalA = false)
    }

    fun testAudioReconnect() {
        gstEngine.forceReconnectExternalAudio()
    }

    fun toggleVideoPause() {
        val nextPaused = !_uiState.value.isPaused
        _uiState.update { it.copy(isPaused = nextPaused) }
        gstEngine.setVideoPaused(nextPaused)
    }

    fun toggleAudioPause() {
        val nextPaused = !_uiState.value.gstState.audioPaused
        gstEngine.setAudioPaused(nextPaused)
    }

    fun adjustAudioDelayMs(deltaMs: Long) {
        val next = (_uiState.value.gstState.audioDelayMs + deltaMs).coerceIn(-5000L, 5000L)
        gstEngine.setAudioDelayMs(next)
        onGstLog("SYNC", "تعديل تأخير الصوت إلى ${next}ms")
    }

    fun setAudioDelayMs(delayMs: Long) {
        gstEngine.setAudioDelayMs(delayMs)
    }

    fun clearLogs() {
        _uiState.update { it.copy(logs = emptyList()) }
    }

    override fun onGstLog(level: String, message: String) {
        val entry = DiagnosticLogEntry(
            timestamp = timeFormat.format(Date()),
            level = level,
            message = CredentialSanitizer.sanitizeMessage(message)
        )
        _uiState.update { state ->
            state.copy(logs = (listOf(entry) + state.logs).take(150))
        }
    }

    override fun onCleared() {
        audioInspectJob?.cancel()
        gstEngine.release()
        super.onCleared()
    }
}
