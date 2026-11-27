package com.example

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.game.ScreenMode
import com.example.network.HeadlessPeerBridge
import com.example.ui.GameViewModel
import com.example.ui.screens.GameHudScreen
import com.example.ui.screens.LobbyScreen
import com.example.ui.screens.MatchEndScreen
import com.example.ui.screens.TitleScreen
import com.example.ui.theme.BouChorTheme
import com.example.ui.theme.MarigoldGold
import com.example.ui.theme.MaroonNight

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        hideSystemBars()
        setContent {
            BouChorTheme {
                BouChorApp()
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }
}

@Composable
fun BouChorApp(viewModel: GameViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val micPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.onMicPermissionResult(granted)
        if (!granted) {
            viewModel.showToast("Mic permission denied — voice chat muted")
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Headless 1dp WebRTC / PeerJS P2P Data + Proximity Voice Bridge
        HeadlessPeerBridge(
            controller = viewModel.peerController,
            micPermissionGranted = uiState.micPermissionGranted
        )

        when (val screen = uiState.screen) {
            is ScreenMode.Title -> {
                TitleScreen(
                    uiState = uiState,
                    onNameChange = viewModel::updatePlayerName,
                    onPreferredRoleChange = viewModel::selectPreferredRole,
                    onCreateRoom = {
                        if (!uiState.micPermissionGranted) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        viewModel.createMultiplayerRoom()
                    },
                    onJoinRoom = { code ->
                        if (!uiState.micPermissionGranted) {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                        viewModel.joinMultiplayerRoom(code)
                    },
                    onPlaySolo = { role ->
                        viewModel.startSoloMatch(role)
                    },
                    onClearLeaderboard = viewModel::clearLocalLeaderboard
                )
            }

            is ScreenMode.Lobby -> {
                LobbyScreen(
                    uiState = uiState,
                    onLeaveLobby = viewModel::leaveToTitle,
                    onStartMatch = viewModel::startMatchFromLobby,
                    onToggleMic = viewModel::toggleMic,
                    onToggleSpeaker = viewModel::toggleSpeakerMute,
                    onRequestMicPermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onSendChat = { text -> viewModel.sendLocalChat(text) },
                    onCopyToast = viewModel::showToast
                )
            }

            is ScreenMode.Playing -> {
                GameHudScreen(
                    uiState = uiState,
                    worldProvider = { viewModel.latestWorld },
                    frameTickProvider = { viewModel.renderFrameTick.longValue },
                    onJoystickMove = viewModel::setJoystickVector,
                    onKeyboardMove = viewModel::setKeyboardVector,
                    onCameraOrbit = viewModel::orbitCameraBy,
                    onCameraZoom = viewModel::zoomCameraBy,
                    onAbility1Press = viewModel::triggerAbility1Press,
                    onAbility1HoldChanged = viewModel::setAbility1Held,
                    onAbility2Press = viewModel::triggerAbility2Press,
                    onAbility3Press = viewModel::triggerAbility3Press,
                    onToggleMic = viewModel::toggleMic,
                    onToggleSpeaker = viewModel::toggleSpeakerMute,
                    onToggleTeamRadio = viewModel::toggleTeamRadio,
                    onToggleGraphicsQuality = viewModel::toggleGraphicsQuality,
                    onRequestMicPermission = {
                        micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    },
                    onToggleChat = viewModel::toggleChatExpanded,
                    onSelectChatChannel = viewModel::setChatChannel,
                    onSendChat = { text -> viewModel.sendLocalChat(text) },
                    onExitMatch = viewModel::returnToLobbyOrTitle
                )
            }

            is ScreenMode.MatchEnd -> {
                val localTeam = uiState.world.localPlayer?.role?.team
                MatchEndScreen(
                    result = screen.result,
                    localWon = localTeam == screen.result.winningTeam,
                    activePlayerName = uiState.world.localPlayer?.name ?: uiState.playerName,
                    leaderboardEntries = uiState.leaderboardEntries,
                    recordedBouEscape = uiState.lastMatchRecordedBouEscape,
                    recordedChorCatches = uiState.lastMatchRecordedChorCatches,
                    onClearLeaderboard = viewModel::clearLocalLeaderboard,
                    onPlayAgainShuffle = viewModel::playAgainShuffleRoles,
                    onBackToLobby = viewModel::returnToLobbyOrTitle
                )
            }
        }

        // Crisp Toast Banner
        AnimatedVisibility(
            visible = !uiState.toastMessage.isNullOrBlank(),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 28.dp)
        ) {
            Surface(
                color = MarigoldGold,
                shape = RoundedCornerShape(999.dp)
            ) {
                Text(
                    text = uiState.toastMessage.orEmpty(),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaroonNight,
                    modifier = Modifier.padding(horizontal = 18.dp, vertical = 10.dp)
                )
            }
        }
    }
}
