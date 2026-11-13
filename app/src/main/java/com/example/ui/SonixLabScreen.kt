package com.example.ui

import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.LiveTv
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.gst.ActiveAudioMode
import com.example.gst.PipelineState
import com.example.iptv.IptvChannel
import com.example.iptv.StreamFormatPreference
import com.example.iptv.SubscriptionMode
import com.example.ui.theme.SonixAccentGold
import com.example.ui.theme.SonixBgGradientEnd
import com.example.ui.theme.SonixBgGradientStart
import com.example.ui.theme.SonixBgPanel
import com.example.ui.theme.SonixBgScreen
import com.example.ui.theme.SonixBorderPanel
import com.example.ui.theme.SonixDangerRed
import com.example.ui.theme.SonixFocusBlue
import com.example.ui.theme.SonixSelectedItemBg
import com.example.ui.theme.SonixSuccessGreen
import com.example.ui.theme.SonixTextMuted
import com.example.ui.theme.SonixTextSilver
import com.example.ui.theme.SonixTextWhite
import kotlin.math.roundToLong

@Composable
fun SonixLabScreen(
    state: SonixLabUiState,
    onSubscriptionModeChange: (SubscriptionMode) -> Unit,
    onServerUrlChange: (String) -> Unit,
    onUsernameChange: (String) -> Unit,
    onPasswordChange: (String) -> Unit,
    onM3uUrlChange: (String) -> Unit,
    onFormatPrefChange: (StreamFormatPreference) -> Unit,
    onToggleSubscriptionPanel: () -> Unit,
    onLoadChannels: () -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelectVideoChannel: (IptvChannel) -> Unit,
    onSelectAudioCandidate: (IptvChannel) -> Unit,
    onActivateSelectedAudio: () -> Unit,
    onRestoreOriginalAudio: () -> Unit,
    onDetachAudioOnly: () -> Unit,
    onTestAudioReconnect: () -> Unit,
    onTogglePause: () -> Unit,
    onDelayChange: (Long) -> Unit,
    onDelayStep: (Long) -> Unit,
    onClearLogs: () -> Unit,
    onSurfaceReady: (android.view.Surface?) -> Unit
) {
    val filteredChannels = remember(state.allChannels, state.searchQuery) {
        val q = state.searchQuery.trim().lowercase()
        val list = if (q.isEmpty()) {
            state.allChannels
        } else {
            state.allChannels.filter {
                it.name.lowercase().contains(q) || it.categoryName.lowercase().contains(q)
            }
        }
        list.take(300)
    }

    val audioSourceList = remember(filteredChannels) {
        filteredChannels.sortedByDescending { it.isAudioCandidate }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SonixBgGradientStart, SonixBgGradientEnd)))
            .windowInsetsPadding(WindowInsets.safeDrawing)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            val isWide = maxWidth >= 820.dp

            Column(modifier = Modifier.fillMaxSize()) {
                HeaderTelemetryBar(state = state)

                if (isWide) {
                    Row(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1.15f)
                                .fillMaxHeight()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            SubscriptionPanel(
                                state = state,
                                onModeChange = onSubscriptionModeChange,
                                onServerChange = onServerUrlChange,
                                onUserChange = onUsernameChange,
                                onPassChange = onPasswordChange,
                                onM3uChange = onM3uUrlChange,
                                onFormatChange = onFormatPrefChange,
                                onToggleExpand = onToggleSubscriptionPanel,
                                onLoadClick = onLoadChannels
                            )
                            VideoPlayerCard(
                                state = state,
                                onTogglePause = onTogglePause,
                                onSurfaceReady = onSurfaceReady
                            )
                            SyncAndTelemetryCard(state = state)
                            AudioDelayAndControlCard(
                                state = state,
                                onActivateSelectedAudio = onActivateSelectedAudio,
                                onRestoreOriginalAudio = onRestoreOriginalAudio,
                                onDetachAudioOnly = onDetachAudioOnly,
                                onTestAudioReconnect = onTestAudioReconnect,
                                onDelayChange = onDelayChange,
                                onDelayStep = onDelayStep
                            )
                            DiagnosticLogCard(
                                logs = state.logs,
                                onClear = onClearLogs
                            )
                        }

                        ChannelAndAudioWorkspace(
                            modifier = Modifier
                                .weight(0.85f)
                                .fillMaxHeight(),
                            state = state,
                            videoChannels = filteredChannels,
                            audioCandidates = audioSourceList,
                            onSearchQueryChange = onSearchQueryChange,
                            onSelectVideoChannel = onSelectVideoChannel,
                            onSelectAudioCandidate = onSelectAudioCandidate,
                            onActivateSelectedAudio = onActivateSelectedAudio
                        )
                    }
                } else {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        SubscriptionPanel(
                            state = state,
                            onModeChange = onSubscriptionModeChange,
                            onServerChange = onServerUrlChange,
                            onUserChange = onUsernameChange,
                            onPassChange = onPasswordChange,
                            onM3uChange = onM3uUrlChange,
                            onFormatChange = onFormatPrefChange,
                            onToggleExpand = onToggleSubscriptionPanel,
                            onLoadClick = onLoadChannels
                        )
                        VideoPlayerCard(
                            state = state,
                            onTogglePause = onTogglePause,
                            onSurfaceReady = onSurfaceReady
                        )
                        SyncAndTelemetryCard(state = state)
                        AudioDelayAndControlCard(
                            state = state,
                            onActivateSelectedAudio = onActivateSelectedAudio,
                            onRestoreOriginalAudio = onRestoreOriginalAudio,
                            onDetachAudioOnly = onDetachAudioOnly,
                            onTestAudioReconnect = onTestAudioReconnect,
                            onDelayChange = onDelayChange,
                            onDelayStep = onDelayStep
                        )
                        ChannelAndAudioWorkspace(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 340.dp, max = 460.dp),
                            state = state,
                            videoChannels = filteredChannels,
                            audioCandidates = audioSourceList,
                            onSearchQueryChange = onSearchQueryChange,
                            onSelectVideoChannel = onSelectVideoChannel,
                            onSelectAudioCandidate = onSelectAudioCandidate,
                            onActivateSelectedAudio = onActivateSelectedAudio
                        )
                        DiagnosticLogCard(
                            logs = state.logs,
                            onClear = onClearLogs
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun HeaderTelemetryBar(state: SonixLabUiState) {
    Surface(
        color = SonixBgPanel.copy(alpha = 0.96f),
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (state.gstState.nativeLoaded) SonixSuccessGreen else SonixDangerRed)
                )
                Column {
                    Text(
                        text = "SONIX GStreamer Lab",
                        style = MaterialTheme.typography.titleMedium,
                        color = SonixTextWhite,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (state.gstState.nativeLoaded) {
                            "${state.gstState.gstreamerVersion} • ABIs: ${state.gstState.deviceAbis}"
                        } else {
                            state.gstState.nativeError ?: "GStreamer Native Libraries Waiting"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = SonixTextSilver,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetricPill(
                    label = "CPU ${state.telemetry.cpuUsagePercent}%",
                    color = if (state.telemetry.cpuUsagePercent > 75) SonixDangerRed else SonixFocusBlue
                )
                MetricPill(
                    label = "RAM ${state.telemetry.totalPssMb}MB",
                    color = SonixAccentGold
                )
            }
        }
    }
}

@Composable
private fun MetricPill(label: String, color: Color) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = color.copy(alpha = 0.14f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.45f))
    ) {
        Text(
            text = label,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
private fun SubscriptionPanel(
    state: SonixLabUiState,
    onModeChange: (SubscriptionMode) -> Unit,
    onServerChange: (String) -> Unit,
    onUserChange: (String) -> Unit,
    onPassChange: (String) -> Unit,
    onM3uChange: (String) -> Unit,
    onFormatChange: (StreamFormatPreference) -> Unit,
    onToggleExpand: () -> Unit,
    onLoadClick: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpand),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.LiveTv, contentDescription = null, tint = SonixAccentGold)
                    Column {
                        Text(
                            text = "اشتراك IPTV واحد (مصدر القنوات + الصوت)",
                            style = MaterialTheme.typography.titleSmall,
                            color = SonixTextWhite,
                            fontWeight = FontWeight.Bold
                        )
                        val subtitle = when {
                            state.allChannels.isNotEmpty() -> {
                                val maxCons = state.accountInfo?.maxConnections?.let { " • حد الاتصالات: $it" } ?: ""
                                "تم تحميل ${state.allChannels.size} قناة$maxCons"
                            }
                            else -> "أدخل Xtream Codes أو رابط M3U مرة واحدة فقط"
                        }
                        Text(text = subtitle, style = MaterialTheme.typography.labelSmall, color = SonixTextSilver)
                    }
                }
                Icon(
                    imageVector = if (state.subscriptionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "تبديل لوحة الاشتراك",
                    tint = SonixTextSilver
                )
            }

            AnimatedVisibility(visible = state.subscriptionExpanded) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = state.subscriptionMode == SubscriptionMode.XTREAM,
                            onClick = { onModeChange(SubscriptionMode.XTREAM) },
                            label = { Text("Xtream Codes") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SonixAccentGold.copy(alpha = 0.2f),
                                selectedLabelColor = SonixAccentGold
                            )
                        )
                        FilterChip(
                            selected = state.subscriptionMode == SubscriptionMode.M3U,
                            onClick = { onModeChange(SubscriptionMode.M3U) },
                            label = { Text("رابط M3U واحد") },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = SonixFocusBlue.copy(alpha = 0.2f),
                                selectedLabelColor = SonixFocusBlue
                            )
                        )
                    }

                    if (state.subscriptionMode == SubscriptionMode.XTREAM) {
                        OutlinedTextField(
                            value = state.serverUrl,
                            onValueChange = onServerChange,
                            label = { Text("Server URL (http://host:port)") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("xtream_server_input")
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = state.username,
                                onValueChange = onUserChange,
                                label = { Text("Username") },
                                singleLine = true,
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xtream_user_input")
                            )
                            OutlinedTextField(
                                value = state.password,
                                onValueChange = onPassChange,
                                label = { Text("Password") },
                                singleLine = true,
                                visualTransformation = PasswordVisualTransformation(),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("xtream_pass_input")
                            )
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("صيغة البث:", style = MaterialTheme.typography.labelSmall, color = SonixTextSilver)
                            StreamFormatPreference.entries.forEach { pref ->
                                FilterChip(
                                    selected = state.formatPreference == pref,
                                    onClick = { onFormatChange(pref) },
                                    label = { Text(pref.label, fontSize = 11.sp) }
                                )
                            }
                        }
                    } else {
                        OutlinedTextField(
                            value = state.m3uUrl,
                            onValueChange = onM3uChange,
                            label = { Text("M3U Playlist URL") },
                            singleLine = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("m3u_url_input")
                        )
                    }

                    if (state.catalogError != null) {
                        Text(
                            text = state.catalogError,
                            style = MaterialTheme.typography.bodySmall,
                            color = SonixDangerRed
                        )
                    }

                    Button(
                        onClick = onLoadClick,
                        enabled = !state.isLoadingCatalog,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("load_channels_button"),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = SonixAccentGold,
                            contentColor = Color.Black
                        )
                    ) {
                        if (state.isLoadingCatalog) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Color.Black)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("جاري جلب القنوات...")
                        } else {
                            Icon(Icons.Default.Refresh, contentDescription = null)
                            Spacer(modifier = Modifier.width(6.dp))
                            Text("تحميل القنوات من الاشتراك", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VideoPlayerCard(
    state: SonixLabUiState,
    onTogglePause: () -> Unit,
    onSurfaceReady: (android.view.Surface?) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 9f)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color.Black)
            ) {
                AndroidView(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("gstreamer_video_surface"),
                    factory = { ctx ->
                        SurfaceView(ctx).apply {
                            holder.addCallback(object : SurfaceHolder.Callback {
                                override fun surfaceCreated(holder: SurfaceHolder) {
                                    onSurfaceReady(holder.surface)
                                }

                                override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
                                    onSurfaceReady(holder.surface)
                                }

                                override fun surfaceDestroyed(holder: SurfaceHolder) {
                                    onSurfaceReady(null)
                                }
                            })
                        }
                    }
                )

                Row(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "A الفيديو: ${state.activeVideoChannel?.name ?: "اختر قناة"}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SonixTextWhite,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.72f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "${state.gstState.videoState.labelAr} • ${state.gstState.videoResolution}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = SonixSuccessGreen
                        )
                    }
                }

                if (state.activeAudioChannel != null) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp),
                        color = SonixAccentGold.copy(alpha = 0.92f),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(
                            text = "B الصوت: ${state.activeAudioChannel.name}",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = Color.Black,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = onTogglePause,
                        enabled = state.activeVideoChannel != null,
                        modifier = Modifier.height(48.dp)
                    ) {
                        Icon(
                            imageVector = if (state.isPaused) Icons.Default.PlayArrow else Icons.Default.Pause,
                            contentDescription = "تشغيل أو إيقاف مؤقت"
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(if (state.isPaused) "استئناف" else "إيقاف مؤقت")
                    }
                    MetricPill(
                        label = "مفكك A: ${state.gstState.videoDecoder}",
                        color = SonixFocusBlue
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (state.gstState.originalAudioMuted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp,
                        contentDescription = null,
                        tint = if (state.gstState.originalAudioMuted) SonixDangerRed else SonixSuccessGreen,
                        modifier = Modifier.size(18.dp)
                    )
                    Text(
                        text = if (state.gstState.originalAudioMuted) "صوت A مكتوم" else "صوت A نشط",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (state.gstState.originalAudioMuted) SonixDangerRed else SonixSuccessGreen
                    )
                }
            }
        }
    }
}

@Composable
private fun SyncAndTelemetryCard(state: SonixLabUiState) {
    val gst = state.gstState
    Card(
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Icon(Icons.Default.Sync, contentDescription = null, tint = SonixFocusBlue, modifier = Modifier.size(18.dp))
                Text(
                    text = "مؤشرات الفيديو والصوت والمزامنة (GStreamer Dual Pipeline)",
                    style = MaterialTheme.typography.titleSmall,
                    color = SonixTextWhite,
                    fontWeight = FontWeight.Bold
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBox(
                    modifier = Modifier.weight(1f),
                    title = "الفيديو A",
                    value = gst.videoState.labelAr,
                    sub = "موضع: ${gst.videoPositionMs}ms • إعادات: ${gst.videoRestartCount}",
                    accent = SonixFocusBlue
                )
                StatusBox(
                    modifier = Modifier.weight(1f),
                    title = "الصوت النشط",
                    value = gst.audioMode.labelAr,
                    sub = "${gst.audioState.labelAr} • ${gst.audioDecoder}",
                    accent = if (gst.audioMode == ActiveAudioMode.EXTERNAL_B) SonixSuccessGreen else SonixAccentGold
                )
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatusBox(
                    modifier = Modifier.weight(1f),
                    title = "وضع الاستخراج B",
                    value = gst.audioExtractionMode,
                    sub = "موضع B: ${gst.audioPositionMs}ms",
                    accent = SonixTextSilver
                )
                StatusBox(
                    modifier = Modifier.weight(1f),
                    title = "المزامنة والانحراف",
                    value = "إزاحة: ${gst.audioDelayMs}ms | فرق: ${gst.measuredDriftMs}ms",
                    sub = "محاولات إعادة الاتصال: ${gst.audioReconnectAttempt} • Heap ${state.telemetry.nativeHeapMb}MB",
                    accent = if (kotlin.math.abs(gst.measuredDriftMs) > 400L) SonixDangerRed else SonixSuccessGreen
                )
            }

            state.videoStructureReport?.let { rep ->
                Text(
                    text = "فحص A: ${rep.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonixTextSilver
                )
            }
            state.audioStructureReport?.let { rep ->
                Text(
                    text = "فحص B: ${rep.summary}",
                    style = MaterialTheme.typography.labelSmall,
                    color = SonixAccentGold
                )
            }
        }
    }
}

@Composable
private fun StatusBox(
    modifier: Modifier,
    title: String,
    value: String,
    sub: String,
    accent: Color
) {
    Surface(
        modifier = modifier,
        color = SonixBgScreen,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, SonixBorderPanel)
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(text = title, style = MaterialTheme.typography.labelSmall, color = SonixTextMuted)
            Text(
                text = value,
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = sub,
                style = MaterialTheme.typography.labelSmall,
                color = SonixTextSilver,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun AudioDelayAndControlCard(
    state: SonixLabUiState,
    onActivateSelectedAudio: () -> Unit,
    onRestoreOriginalAudio: () -> Unit,
    onDetachAudioOnly: () -> Unit,
    onTestAudioReconnect: () -> Unit,
    onDelayChange: (Long) -> Unit,
    onDelayStep: (Long) -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.GraphicEq, contentDescription = null, tint = SonixAccentGold)
                    Text(
                        text = "تحكم الصوت الخارجي والتأخير (ms)",
                        style = MaterialTheme.typography.titleSmall,
                        color = SonixTextWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
                if (state.isInspectingAudio) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = SonixAccentGold)
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onActivateSelectedAudio,
                    enabled = state.selectedAudioCandidate != null && !state.isInspectingAudio,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("activate_external_audio_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SonixAccentGold,
                        contentColor = Color.Black
                    )
                ) {
                    Text(
                        text = "تفعيل الصوت المختار",
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onRestoreOriginalAudio,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("restore_original_audio_button"),
                    border = BorderStroke(1.dp, SonixSuccessGreen)
                ) {
                    Text(
                        text = "العودة للصوت الأصلي",
                        color = SonixSuccessGreen,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onDetachAudioOnly,
                    enabled = state.activeAudioChannel != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("detach_audio_button")
                ) {
                    Text("فصل الصوت B فقط")
                }
                OutlinedButton(
                    onClick = onTestAudioReconnect,
                    enabled = state.activeAudioChannel != null,
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp)
                        .testTag("reconnect_audio_button")
                ) {
                    Text("اختبار إعادة اتصال B")
                }
            }

            HorizontalDivider(color = SonixBorderPanel)

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "تأخير الصوت (Audio Delay)",
                    style = MaterialTheme.typography.bodySmall,
                    color = SonixTextSilver
                )
                Text(
                    text = "${state.gstState.audioDelayMs} ms",
                    style = MaterialTheme.typography.bodyMedium,
                    color = SonixAccentGold,
                    fontWeight = FontWeight.Bold
                )
            }

            Slider(
                value = state.gstState.audioDelayMs.toFloat(),
                onValueChange = { onDelayChange(it.roundToLong()) },
                valueRange = -5000f..5000f,
                colors = SliderDefaults.colors(
                    thumbColor = SonixAccentGold,
                    activeTrackColor = SonixAccentGold
                )
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                listOf(-500L, -100L, -20L, 20L, 100L, 500L).forEach { step ->
                    OutlinedButton(
                        onClick = { onDelayStep(step) },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        contentPadding = PaddingValues(horizontal = 2.dp)
                    ) {
                        Text(
                            text = if (step > 0) "+$step" else "$step",
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                }
            }

            TextButton(
                onClick = { onDelayChange(0L) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("تصفير التأخير (0ms)", color = SonixFocusBlue)
            }
        }
    }
}

@Composable
private fun ChannelAndAudioWorkspace(
    modifier: Modifier,
    state: SonixLabUiState,
    videoChannels: List<IptvChannel>,
    audioCandidates: List<IptvChannel>,
    onSearchQueryChange: (String) -> Unit,
    onSelectVideoChannel: (IptvChannel) -> Unit,
    onSelectAudioCandidate: (IptvChannel) -> Unit,
    onActivateSelectedAudio: () -> Unit
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("ابحث عن قناة (مثل beIN SPORTS أو Alwan)...") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChange("") }) {
                            Icon(Icons.Default.Close, contentDescription = "مسح البحث")
                        }
                    }
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("channel_search_input")
            )

            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = SonixBgScreen,
                contentColor = SonixAccentGold
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text("قائمة القنوات (فيديو A) [${videoChannels.size}]", fontSize = 12.sp) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text("مصادر الصوت المتاحة (B) [${audioCandidates.size}]", fontSize = 12.sp) }
                )
            }

            val itemsToShow = if (selectedTab == 0) videoChannels else audioCandidates
            if (itemsToShow.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "لا توجد قنوات بعد — أدخل بيانات الاشتراك واضغط تحميل القنوات",
                        style = MaterialTheme.typography.bodySmall,
                        color = SonixTextMuted
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    items(itemsToShow, key = { "${selectedTab}_${it.id}_${it.number}" }) { channel ->
                        val isSelected = if (selectedTab == 0) {
                            state.activeVideoChannel?.id == channel.id
                        } else {
                            state.selectedAudioCandidate?.id == channel.id
                        }
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    if (selectedTab == 0) {
                                        onSelectVideoChannel(channel)
                                    } else {
                                        onSelectAudioCandidate(channel)
                                    }
                                },
                            color = if (isSelected) SonixSelectedItemBg else SonixBgScreen,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) {
                                    if (selectedTab == 0) SonixFocusBlue else SonixAccentGold
                                } else SonixBorderPanel
                            )
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "${channel.number}. ${channel.name}",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = SonixTextWhite,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    val cat = buildString {
                                        if (channel.categoryName.isNotBlank()) append(channel.categoryName)
                                        if (channel.isAudioCandidate) {
                                            if (isNotEmpty()) append(" • ")
                                            append("مرشح صوت (${channel.audioGroup.displayName})")
                                        }
                                    }
                                    if (cat.isNotBlank()) {
                                        Text(
                                            text = cat,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (channel.isAudioCandidate) SonixAccentGold else SonixTextMuted,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }

                                if (selectedTab == 1 && isSelected) {
                                    Button(
                                        onClick = onActivateSelectedAudio,
                                        modifier = Modifier.height(36.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = SonixAccentGold,
                                            contentColor = Color.Black
                                        )
                                    ) {
                                        Text("تفعيل", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                } else if (isSelected) {
                                    Icon(
                                        Icons.Default.CheckCircle,
                                        contentDescription = "محدد",
                                        tint = SonixFocusBlue,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiagnosticLogCard(
    logs: List<DiagnosticLogEntry>,
    onClear: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SonixBgPanel),
        border = BorderStroke(1.dp, SonixBorderPanel),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Warning, contentDescription = null, tint = SonixAccentGold, modifier = Modifier.size(16.dp))
                    Text(
                        text = "السجل التشخيصي المختصر (${logs.size})",
                        style = MaterialTheme.typography.titleSmall,
                        color = SonixTextWhite,
                        fontWeight = FontWeight.Bold
                    )
                }
                TextButton(onClick = onClear) {
                    Text("مسح", color = SonixTextSilver)
                }
            }

            Surface(
                color = SonixBgScreen,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 200.dp)
            ) {
                if (logs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("لا توجد أحداث مسجلة بعد", style = MaterialTheme.typography.labelSmall, color = SonixTextMuted)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(logs) { item ->
                            val color = when (item.level) {
                                "ERR", "WARN" -> SonixDangerRed
                                "SYNC", "DEC" -> SonixSuccessGreen
                                "PROBE-A", "PROBE-B" -> SonixAccentGold
                                else -> SonixTextSilver
                            }
                            Text(
                                text = "[${item.timestamp}][${item.level}] ${item.message}",
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = color
                            )
                        }
                    }
                }
            }
        }
    }
}
