package com.example

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.SonixLabScreen
import com.example.ui.SonixLabViewModel
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

    private val viewModel: SonixLabViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContent {
            val state by viewModel.uiState.collectAsStateWithLifecycle()
            MyApplicationTheme {
                SonixLabScreen(
                    state = state,
                    onSubscriptionModeChange = viewModel::setSubscriptionMode,
                    onServerUrlChange = viewModel::setServerUrl,
                    onUsernameChange = viewModel::setUsername,
                    onPasswordChange = viewModel::setPassword,
                    onM3uUrlChange = viewModel::setM3uUrl,
                    onFormatPrefChange = viewModel::setFormatPreference,
                    onToggleSubscriptionPanel = viewModel::toggleSubscriptionPanel,
                    onLoadChannels = viewModel::loadSubscriptionChannels,
                    onSearchQueryChange = viewModel::setSearchQuery,
                    onSelectVideoChannel = viewModel::selectVideoChannel,
                    onSelectAudioCandidate = viewModel::selectAudioCandidate,
                    onActivateSelectedAudio = viewModel::activateSelectedAudio,
                    onRestoreOriginalAudio = viewModel::restoreOriginalAudio,
                    onDetachAudioOnly = viewModel::detachExternalAudioOnly,
                    onTestAudioReconnect = viewModel::testAudioReconnect,
                    onTogglePause = viewModel::toggleVideoPause,
                    onDelayChange = viewModel::setAudioDelayMs,
                    onDelayStep = viewModel::adjustAudioDelayMs,
                    onClearLogs = viewModel::clearLogs,
                    onSurfaceReady = viewModel::attachVideoSurface
                )
            }
        }
    }
}
