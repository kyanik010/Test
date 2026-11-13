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

    init {
        onGstLog("SYS", "بدء مختبر SONIX GStreamer Lab | معماريات الجهاز: ${android.os.Build.SUPPORTED_ABIS.joinToString(", ")}")
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

        _uiState.update { it.copy(isLoadingCatalog = true, catalogError = null) }
        onGstLog("IPTV", "جلب القنوات من اشتراك ${if (current.subscriptionMode == SubscriptionMode.XTREAM) "Xtream Codes" else "M3U"}...")

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

                loaded.accountInfo?.let { acc ->
                    onGstLog(
                        "AUTH",
                        "اشتراك صالح (${acc.status}) | الحد الأقصى للاتصالات: ${acc.maxConnections ?: "غير محدد"} | الصيغ: ${acc.allowedFormats.joinToString(",")}"
                    )
                }

                val channels = loaded.channels
                onGstLog("IPTV", "تم تحميل ${channels.size} قناة بنجاح من الاشتراك الواحد")

                _uiState.update {
                    it.copy(
                        isLoadingCatalog = false,
                        accountInfo = loaded.accountInfo,
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
        gstEngine.playVideoChannel(channel.streamUrl, channel.name)

        viewModelScope.launch(Dispatchers.IO) {
            val report = StreamStructureInspector.inspectRemote(channel.streamUrl)
            _uiState.update { it.copy(videoStructureReport = report) }
            onGstLog("PROBE-A", "[${channel.name}] ${report.summary}")
        }
    }

    fun selectAudioCandidate(channel: IptvChannel) {
        _uiState.update { it.copy(selectedAudioCandidate = channel) }
    }

    fun activateSelectedAudio() {
        val candidate = _uiState.value.selectedAudioCandidate ?: return
        val activeVideo = _uiState.value.activeVideoChannel
        if (activeVideo != null && activeVideo.id == candidate.id && activeVideo.streamUrl == candidate.streamUrl) {
            onGstLog("WARN", "القناة المختارة للصوت هي نفس قناة الفيديو الحالية — سيتم استخدام الصوت الأصلي A")
            return
        }

        _uiState.update {
            it.copy(
                activeAudioChannel = candidate,
                isInspectingAudio = true,
                audioStructureReport = null
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            onGstLog("PROBE-B", "فحص بنية البث لقناة الصوت [${candidate.name}]...")
            val report = StreamStructureInspector.inspectRemote(candidate.streamUrl)
            _uiState.update { it.copy(audioStructureReport = report, isInspectingAudio = false) }
            onGstLog("PROBE-B", "[${candidate.name}] ${report.summary}")

            val targetUrl = report.audioOnlyUrl ?: candidate.streamUrl
            val modeLabel = when {
                report.hasIndependentAudioRendition -> "HLS Audio-Only Rendition"
                report.containerKind == "MPEG-TS" -> "MPEG-TS Audio Demux (تجاهل الفيديو)"
                else -> "${report.containerKind} (استخراج الصوت فقط)"
            }
            gstEngine.attachOrSwitchExternalAudio(targetUrl, candidate.name, modeLabel)
        }
    }

    fun restoreOriginalAudio() {
        _uiState.update { it.copy(activeAudioChannel = null) }
        gstEngine.detachExternalAudio(restoreOriginalA = true)
    }

    fun detachExternalAudioOnly() {
        _uiState.update { it.copy(activeAudioChannel = null) }
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
            state.copy(logs = (listOf(entry) + state.logs).take(120))
        }
    }

    override fun onCleared() {
        gstEngine.release()
        super.onCleared()
    }
}
