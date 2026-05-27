package com.example.arzombie

import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.github.sceneview.math.Position
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

enum class GameState {
    START,
    PLAYING,
    GAME_OVER
}

data class GhostState(
    val id: String,
    val position: Position,
    val color: Color,
    val scale: Float = 1.0f,
    val floatPhase: Float = 0f,
    val spawnTime: Long = System.currentTimeMillis()
)

data class GameUiState(
    val gameState: GameState = GameState.START,
    val score: Int = 0,
    val highScore: Int = 0,
    val lives: Int = 3,
    val timeRemaining: Int = 60,
    val ghosts: List<GhostState> = emptyList(),
    val planeDetected: Boolean = false
)

class GameViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(GameUiState())
    val uiState = _uiState.asStateFlow()

    private var timerJob: Job? = null
    private var spawnJob: Job? = null
    private var ghostTimeoutJob: Job? = null
    private var scanTimerJob: Job? = null

    fun updatePlaneDetected(detected: Boolean) {
        if (detected) {
            scanTimerJob?.cancel()
        }
        _uiState.update { it.copy(planeDetected = detected) }
    }

    fun startGame() {
        timerJob?.cancel()
        spawnJob?.cancel()
        ghostTimeoutJob?.cancel()
        scanTimerJob?.cancel()

        _uiState.update {
            it.copy(
                gameState = GameState.PLAYING,
                score = 0,
                lives = 3,
                timeRemaining = 60,
                ghosts = emptyList(),
                planeDetected = false
            )
        }

        // Start fallback 10s scan timer
        scanTimerJob = viewModelScope.launch {
            delay(10000L)
            if (!_uiState.value.planeDetected && _uiState.value.gameState == GameState.PLAYING) {
                _uiState.update { it.copy(planeDetected = true) }
            }
        }

        // Start Countdown Timer
        timerJob = viewModelScope.launch {
            while (_uiState.value.timeRemaining > 0 && _uiState.value.gameState == GameState.PLAYING) {
                delay(1000L)
                if (_uiState.value.planeDetected) {
                    _uiState.update {
                        val nextTime = it.timeRemaining - 1
                        if (nextTime <= 0) {
                            it.copy(timeRemaining = 0, gameState = GameState.GAME_OVER)
                        } else {
                            it.copy(timeRemaining = nextTime)
                        }
                    }
                    if (_uiState.value.gameState == GameState.GAME_OVER) {
                        checkAndSaveHighScore()
                    }
                }
            }
        }

        // Check for Ghost Timeout (despawn if not shot in 12 seconds)
        ghostTimeoutJob = viewModelScope.launch {
            while (_uiState.value.gameState == GameState.PLAYING) {
                delay(500L)
                val currentTime = System.currentTimeMillis()
                val activeGhosts = _uiState.value.ghosts
                val timedOutGhosts = activeGhosts.filter { currentTime - it.spawnTime > 12000L }

                if (timedOutGhosts.isNotEmpty()) {
                    val nextLives = maxOf(0, _uiState.value.lives - timedOutGhosts.size)
                    val nextGhosts = activeGhosts.filterNot { timedOutGhosts.contains(it) }

                    _uiState.update {
                        it.copy(
                            lives = nextLives,
                            ghosts = nextGhosts,
                            gameState = if (nextLives <= 0) GameState.GAME_OVER else it.gameState
                        )
                    }

                    if (nextLives <= 0) {
                        checkAndSaveHighScore()
                        timerJob?.cancel()
                        spawnJob?.cancel()
                    }
                }
            }
        }
    }

    fun spawnGhost(cameraX: Float, cameraY: Float, cameraZ: Float) {
        if (_uiState.value.gameState != GameState.PLAYING) return

        val angle = (0..359).random() * (Math.PI / 180f).toFloat()
        val distance = (20..35).random() / 10f // 2.0 to 3.5 meters
        val x = cameraX + distance * kotlin.math.cos(angle)
        val z = cameraZ + distance * kotlin.math.sin(angle)
        // Spawn exactly at ground level
        val y = cameraY

        val neonColors = listOf(
            Color(0xFF39FF14), // Neon Lime Green
            Color(0xFFFF007F), // Neon Pink/Magenta
            Color(0xFF00E5FF), // Neon Cyan/Blue
            Color(0xFFFF5F1F), // Neon Orange
            Color(0xFFBD00FF)  // Neon Purple
        )
        val color = neonColors.random()

        val newGhost = GhostState(
            id = UUID.randomUUID().toString(),
            position = Position(x, y, z),
            color = color,
            floatPhase = (0..100).random() / 10f
        )

        _uiState.update {
            it.copy(ghosts = it.ghosts + newGhost)
        }
    }

    fun shootGhost(ghostId: String) {
        if (_uiState.value.gameState != GameState.PLAYING) return

        _uiState.update { state ->
            val updatedGhosts = state.ghosts.filterNot { it.id == ghostId }
            val hit = state.ghosts.size != updatedGhosts.size
            val nextScore = if (hit) state.score + 10 else state.score
            state.copy(
                score = nextScore,
                ghosts = updatedGhosts
            )
        }
    }

    private fun checkAndSaveHighScore() {
        val currentScore = _uiState.value.score
        if (currentScore > _uiState.value.highScore) {
            _uiState.update { it.copy(highScore = currentScore) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        spawnJob?.cancel()
        ghostTimeoutJob?.cancel()
        scanTimerJob?.cancel()
    }
}
