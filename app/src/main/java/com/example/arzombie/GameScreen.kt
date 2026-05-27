package com.example.arzombie

import android.view.MotionEvent
import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.filament.Engine
import com.google.android.filament.MaterialInstance
import com.google.ar.core.Frame
import com.google.ar.core.TrackingState
import io.github.sceneview.ar.ARSceneView
import io.github.sceneview.loaders.MaterialLoader
import io.github.sceneview.math.Position
import io.github.sceneview.node.SphereNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberMaterialLoader
import io.github.sceneview.rememberOnGestureListener
import kotlinx.coroutines.delay
import kotlin.math.sin

// Custom Node representing our Ghost in the 3D Filament space
class GhostNode(
    val ghostId: String,
    val floatPhase: Float,
    val basePosition: Position,
    val spawnTime: Long,
    engine: Engine,
    radius: Float,
    materialInstance: MaterialInstance?
) : SphereNode(
    engine = engine,
    radius = radius,
    materialInstance = materialInstance
) {
    init {
        position = basePosition
    }
}

@Composable
fun GameScreen(
    modifier: Modifier = Modifier,
    viewModel: GameViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val engine = rememberEngine()
    val materialLoader = rememberMaterialLoader(engine)
    val context = LocalContext.current

    // Keep track of the active 3D GhostNodes so we can animate their floating motion in the render loop
    val activeGhostNodes = remember { mutableMapOf<String, GhostNode>() }
    var lastFrame by remember { mutableStateOf<Frame?>(null) }
    var groundY by remember { mutableStateOf<Float?>(null) }

    // Spawn loop when playing and plane is detected
    LaunchedEffect(uiState.gameState, uiState.planeDetected) {
        if (uiState.gameState == GameState.PLAYING && uiState.planeDetected) {
            while (uiState.gameState == GameState.PLAYING) {
                delay(4400L) // Spawn every 4.4 seconds (50% slower spawn rate)
                val frame = lastFrame
                if (frame != null && frame.camera.trackingState == TrackingState.TRACKING) {
                    val cameraPose = frame.camera.pose
                    val cx = cameraPose.tx()
                    val cy = cameraPose.ty()
                    val cz = cameraPose.tz()

                    // Anchor Y to ground plane if detected, otherwise camera height - 1.2m (assume phone is held at 1.2m height)
                    val gy = groundY ?: (cy - 1.2f)
                    viewModel.spawnGhost(cx, gy, cz)
                }
            }
        }
    }

    // Intercept single taps on virtual 3D nodes to trigger hit detection
    val gestureListener = rememberOnGestureListener(
        onSingleTapUp = { motionEvent: MotionEvent, node ->
            if (node is GhostNode) {
                viewModel.shootGhost(node.ghostId)
            }
            true
        }
    )

    Box(modifier = modifier.fillMaxSize()) {
        // AR SceneView container
        ARSceneView(
            modifier = Modifier.fillMaxSize(),
            engine = engine,
            planeRenderer = !uiState.planeDetected, // Visualizes detected planes until one is detected
            onSessionUpdated = { session, frame ->
                lastFrame = frame

                // Update planes state and look for a horizontal ground plane
                val planes = session.getAllTrackables(com.google.ar.core.Plane::class.java)
                val horizontalPlane = planes.firstOrNull {
                    it.trackingState == TrackingState.TRACKING &&
                            it.type == com.google.ar.core.Plane.Type.HORIZONTAL_UPWARD_FACING
                }

                if (horizontalPlane != null) {
                    groundY = horizontalPlane.centerPose.ty()
                    viewModel.updatePlaneDetected(true)
                } else {
                    viewModel.updatePlaneDetected(planes.any { it.trackingState == TrackingState.TRACKING })
                }

                // Update floating and rising animation on each frame
                val currentTime = System.currentTimeMillis()
                val time = currentTime / 1000f
                activeGhostNodes.values.forEach { node ->
                    // Slowly move upward: 0.15 meters per second
                    val elapsedSeconds = (currentTime - node.spawnTime) / 1000f
                    val riseOffset = elapsedSeconds * 0.15f
                    // Floating height offset using sine wave
                    val newY = node.basePosition.y + riseOffset + sin(time * 3.5f + node.floatPhase) * 0.12f
                    node.position = Position(node.basePosition.x, newY, node.basePosition.z)
                }
            },
            onGestureListener = gestureListener,
            content = {
                // Render the ghosts declaratively
                if (uiState.gameState == GameState.PLAYING) {
                    uiState.ghosts.forEach { ghost ->
                        val material = remember(ghost.id, ghost.color) {
                            materialLoader.createColorInstance(
                                color = ghost.color,
                                metallic = 0.2f,
                                roughness = 0.6f,
                                reflectance = 0.3f
                            )
                        }

                        val node = remember(ghost.id) {
                            GhostNode(
                                ghostId = ghost.id,
                                floatPhase = ghost.floatPhase,
                                basePosition = ghost.position,
                                spawnTime = ghost.spawnTime,
                                engine = engine,
                                radius = 0.18f, // 18cm radius placeholder sphere
                                materialInstance = material
                            )
                        }

                        // Register and clean up node in active map for frame animation
                        DisposableEffect(ghost.id) {
                            activeGhostNodes[ghost.id] = node
                            onDispose {
                                activeGhostNodes.remove(ghost.id)
                            }
                        }

                        // Emit node into SceneView's custom Applier
                        NodeLifecycle(node = node, content = null)
                    }
                }
            }
        )

        // HUD Overlay UI
        HUDOverlay(
            uiState = uiState,
            onStartClick = { viewModel.startGame() }
        )
    }
}

@Composable
fun HUDOverlay(
    uiState: GameUiState,
    onStartClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Status Bar (Top Cards)
        if (uiState.gameState == GameState.PLAYING) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Score Card
                GlassmorphicCard {
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "SCORE",
                            color = Color(0xFFA0AEC0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${uiState.score}",
                            color = Color(0xFF39FF14), // Neon green
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Timer Card
                GlassmorphicCard {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "TIME LEFT",
                            color = Color(0xFFA0AEC0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "${uiState.timeRemaining}s",
                            color = Color(0xFFFF9F00), // Neon Orange/Yellow
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Black
                        )
                    }
                }

                // Lives Card
                GlassmorphicCard {
                    Column(
                        horizontalAlignment = Alignment.End,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = "LIVES",
                            color = Color(0xFFA0AEC0),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            repeat(3) { index ->
                                val active = index < uiState.lives
                                Text(
                                    text = if (active) "❤️" else "🖤",
                                    fontSize = 16.sp
                                )
                            }
                        }
                    }
                }
            }

            // Crosshair in the center of the screen
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .align(Alignment.Center)
                    .border(2.dp, Color(0xFFFF0055).copy(alpha = 0.8f), CircleShape)
            ) {
                // Center dot
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .align(Alignment.Center)
                        .background(Color(0xFFFF0055), CircleShape)
                )
            }

            // Ground scan tutorial helper
            if (!uiState.planeDetected) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 80.dp)
                ) {
                    GlassmorphicCard {
                        Text(
                            text = "📱 Scan floor or flat surfaces to detect planes...",
                            color = Color.White,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }

        // Screens based on Game State
        AnimatedVisibility(
            visible = uiState.gameState == GameState.START,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            FullScreenOverlay {
                GlassmorphicCard(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        // Title
                        Text(
                            text = "GHOST HUNTER AR",
                            fontSize = 28.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center,
                            style = LocalTextStyle.current.copy(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(Color(0xFF00FFCC), Color(0xFFBD00FF))
                                )
                            )
                        )

                        Text(
                            text = "Scan your environment for ground surfaces. Tap the floating ghosts to shoot them down before they escape!",
                            color = Color(0xFFE2E8F0),
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center,
                            lineHeight = 20.sp
                        )

                        // Start Button
                        Button(
                            onClick = onStartClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF39FF14),
                                contentColor = Color(0xFF0F172A)
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "START HUNTING",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        if (uiState.highScore > 0) {
                            Text(
                                text = "HIGH SCORE: ${uiState.highScore}",
                                color = Color(0xFFFFD700),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = uiState.gameState == GameState.GAME_OVER,
            enter = fadeIn() + scaleIn(),
            exit = fadeOut() + scaleOut()
        ) {
            FullScreenOverlay {
                GlassmorphicCard(
                    modifier = Modifier
                        .fillMaxWidth(0.85f)
                        .wrapContentHeight()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        Text(
                            text = "GAME OVER",
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Black,
                            color = Color(0xFFFF0055),
                            textAlign = TextAlign.Center
                        )

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = "YOUR SCORE",
                                color = Color(0xFFA0AEC0),
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "${uiState.score}",
                                color = Color(0xFF39FF14),
                                fontSize = 48.sp,
                                fontWeight = FontWeight.Black
                            )
                        }

                        Text(
                            text = "HIGH SCORE: ${uiState.highScore}",
                            color = Color(0xFFFFD700),
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )

                        // Play Again Button
                        Button(
                            onClick = onStartClick,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFBD00FF),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp)
                        ) {
                            Text(
                                text = "PLAY AGAIN",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun FullScreenOverlay(
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0F19).copy(alpha = 0.82f))
            .clickable(enabled = false) {}, // Intercept clicks to background
        contentAlignment = Alignment.Center,
        content = content
    )
}

@Composable
fun GlassmorphicCard(
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF1A202C).copy(alpha = 0.72f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(16.dp))
            .padding(2.dp),
        content = content
    )
}
