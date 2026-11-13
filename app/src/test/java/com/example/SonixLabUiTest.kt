package com.example

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.gst.ActiveAudioMode
import com.example.gst.GstEngineState
import com.example.iptv.IptvChannel
import com.example.iptv.SubscriptionMode
import com.example.ui.SonixLabScreen
import com.example.ui.SonixLabUiState
import com.example.ui.theme.MyApplicationTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class SonixLabUiTest {

    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun singleScreenLab_rendersControls_andIndependentAudioPause_andHonestSyncAndLimitNotice() {
        var loadedClicked = false
        var delayStepped = 0L
        var restoreClicked = false
        var audioPauseClicked = false

        val sampleChannels = listOf(
            IptvChannel("1", "beIN SPORTS English", "1", "Sports", "http://example.com/1.m3u8"),
            IptvChannel("2", "beIN SPORTS Arabic", "2", "Sports", "http://example.com/2.m3u8", isAudioCandidate = true)
        )

        composeRule.setContent {
            MyApplicationTheme {
                SonixLabScreen(
                    state = SonixLabUiState(
                        subscriptionMode = SubscriptionMode.XTREAM,
                        allChannels = sampleChannels,
                        activeVideoChannel = sampleChannels[0],
                        selectedAudioCandidate = sampleChannels[1],
                        activeAudioChannel = sampleChannels[1],
                        providerLimitNotice = "قيد مزود الخدمة: max_connections=1",
                        gstState = GstEngineState(
                            audioMode = ActiveAudioMode.EXTERNAL_B,
                            hasValidSyncTiming = false
                        )
                    ),
                    onSubscriptionModeChange = {},
                    onServerUrlChange = {},
                    onUsernameChange = {},
                    onPasswordChange = {},
                    onM3uUrlChange = {},
                    onFormatPrefChange = {},
                    onToggleSubscriptionPanel = {},
                    onLoadChannels = { loadedClicked = true },
                    onSearchQueryChange = {},
                    onSelectVideoChannel = {},
                    onSelectAudioCandidate = {},
                    onActivateSelectedAudio = {},
                    onRestoreOriginalAudio = { restoreClicked = true },
                    onDetachAudioOnly = {},
                    onTestAudioReconnect = {},
                    onTogglePause = {},
                    onToggleAudioPause = { audioPauseClicked = true },
                    onDelayChange = {},
                    onDelayStep = { delayStepped += it },
                    onClearLogs = {},
                    onSurfaceReady = {}
                )
            }
        }

        composeRule.waitForIdle()
        composeRule.onNodeWithText("SONIX GStreamer Lab").assertIsDisplayed()
        composeRule.onNodeWithTag("load_channels_button").performScrollTo().performClick()
        assertTrue(loadedClicked)

        composeRule.onNodeWithTag("gstreamer_video_surface").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("قيد مزود الخدمة: max_connections=1").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("لا تتوفر بيانات PTS كافية لحساب انحراف تلقائي").performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("activate_external_audio_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("pause_audio_button").performScrollTo().performClick()
        assertTrue(audioPauseClicked)

        composeRule.onNodeWithTag("restore_original_audio_button").performScrollTo().performClick()
        assertTrue(restoreClicked)

        composeRule.onNodeWithText("+100").performScrollTo().performClick()
        assertEquals(100L, delayStepped)
    }
}
