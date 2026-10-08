package com.swarm.ai.ui.screens

import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.swarm.ai.remote.ui.*
import com.swarm.ai.ui.companion.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.roundToInt

enum class RemoteMode { DESKTOP, TRACKPAD, KEYBOARD }

@Composable
fun RemotePCPage(
    state: CompanionState,
    link: RemoteUiState,
    online: Boolean,
    viewModel: RemotePCViewModel = hiltViewModel()
) {
    val streamState by viewModel.streamState.collectAsState()
    DisposableEffect(viewModel) { onDispose { viewModel.stopStream() } }
    var mode by remember { mutableStateOf(RemoteMode.DESKTOP) }
    var showTutorial by remember { mutableStateOf(true) }
    var viewportSize by remember { mutableStateOf(IntSize.Zero) }
    var scale by remember { mutableStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    
    // Start stream when online
    LaunchedEffect(online) {
        if (online && !streamState.isStreaming) {
            viewModel.startStream(StreamQuality.AUTO)
        } else if (!online && streamState.isStreaming) {
            viewModel.stopStream()
        }
    }
    
    // Coordinate mapping helper
    fun mapToDesktopCoords(viewportOffset: Offset): Pair<Int, Int>? {
        val frame = streamState.frame ?: return null
        if (viewportSize.width == 0 || viewportSize.height == 0) return null
        
        // Calculate displayed image dimensions (Fit scale)
        val frameAspect = frame.width.toFloat() / frame.height
        val viewportAspect = viewportSize.width.toFloat() / viewportSize.height
        
        val (displayWidth, displayHeight) = if (frameAspect > viewportAspect) {
            // Letterbox top/bottom
            viewportSize.width.toFloat() to viewportSize.width / frameAspect
        } else {
            // Letterbox left/right
            viewportSize.height * frameAspect to viewportSize.height.toFloat()
        }
        
        // Calculate letterbox offset
        val letterboxX = (viewportSize.width - displayWidth) / 2f
        val letterboxY = (viewportSize.height - displayHeight) / 2f
        
        // Map viewport coords to display coords
        val displayX = viewportOffset.x - letterboxX
        val displayY = viewportOffset.y - letterboxY
        
        // Check if within displayed image
        if (displayX < 0 || displayX > displayWidth || displayY < 0 || displayY > displayHeight) {
            return null
        }
        
        // Map to desktop coords
        val desktopX = (displayX / displayWidth * streamState.frameWidth).roundToInt()
        val desktopY = (displayY / displayHeight * streamState.frameHeight).roundToInt()
        
        return desktopX to desktopY
    }
    
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (!online) {
            // Offline state
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Rounded.CloudOff,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.White.copy(alpha = 0.5f)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "PC Not Connected",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color.White
                    )
                    Text(
                        "Connect to your PC to access remote desktop",
                        fontSize = 13.sp,
                        color = Color.White.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            // Remote desktop view
            Column(Modifier.fillMaxSize()) {
                // Top bar
                Surface(
                    color = Color.Black.copy(alpha = 0.7f),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        Modifier
                            .statusBarsPadding()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                link.pcName,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color.White
                            )
                            Text(
                                "● ${link.routeLabel ?: "Connected"}",
                                fontSize = 11.sp,
                                color = Color(0xFF10B981)
                            )
                        }
                        
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Connection quality
                            Surface(
                                color = Color.White.copy(alpha = 0.1f),
                                shape = RoundedCornerShape(12.dp)
                            ) {
                                Row(
                                    Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(
                                        Icons.Rounded.SignalCellularAlt,
                                        contentDescription = null,
                                        modifier = Modifier.size(14.dp),
                                        tint = Color.White
                                    )
                                    Text("${streamState.latencyMs}ms", fontSize = 11.sp, color = Color.White)
                                    Text("·", fontSize = 11.sp, color = Color.White.copy(alpha = 0.5f))
                                    Text("${streamState.fps}fps", fontSize = 11.sp, color = Color.White)
                                }
                            }
                        }
                    }
                }
                
                // Desktop stream
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .background(Color(0xFF1E1E1E))
                        .onSizeChanged { viewportSize = it }
                        .pointerInput(mode) {
                            when (mode) {
                                RemoteMode.DESKTOP -> {
                                    detectTapGestures(
                                        onTap = { tapOffset ->
                                            mapToDesktopCoords(tapOffset)?.let { (x, y) ->
                                                viewModel.sendClick(x, y, "left", false)
                                            }
                                        },
                                        onDoubleTap = { tapOffset ->
                                            mapToDesktopCoords(tapOffset)?.let { (x, y) ->
                                                viewModel.sendClick(x, y, "left", true)
                                            }
                                        },
                                        onLongPress = { tapOffset ->
                                            mapToDesktopCoords(tapOffset)?.let { (x, y) ->
                                                viewModel.sendClick(x, y, "right", false)
                                            }
                                        }
                                    )
                                }
                                RemoteMode.TRACKPAD -> {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        // Relative movement for trackpad
                                        viewModel.sendMove(
                                            dragAmount.x.roundToInt(),
                                            dragAmount.y.roundToInt(),
                                            relative = true
                                        )
                                    }
                                }
                                RemoteMode.KEYBOARD -> {
                                    // Keyboard mode - no gesture handling on viewport
                                }
                            }
                        }
                        .pointerInput(Unit) {
                            // Scroll detection (works in all modes)
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.type == PointerEventType.Scroll) {
                                        val scrollDelta = event.changes.first().scrollDelta
                                        viewModel.sendScroll(scrollDelta.y.roundToInt())
                                    }
                                }
                            }
                        },
                    contentAlignment = Alignment.Center
                ) {
                    if (streamState.frame != null) {
                        Image(
                            bitmap = streamState.frame!!,
                            contentDescription = "Desktop screen",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            CircularProgressIndicator(color = Color.White.copy(alpha = 0.5f))
                            Text(
                                if (streamState.error != null) streamState.error!! 
                                else "Waiting for desktop stream...",
                                fontSize = 14.sp,
                                color = if (streamState.error != null) Color(0xFFEF4444) else Color.White.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
                
                // Control toolbar
                RemoteControlToolbar(
                    mode = mode,
                    onModeChange = { mode = it },
                    onSpecialKey = { key ->
                        viewModel.sendKey(key.lowercase(), modifiers = null)
                    },
                    onQualityChange = { quality ->
                        viewModel.setQuality(quality)
                    }
                )
            }
            
            // Tutorial overlay
            if (showTutorial) {
                RemoteTutorialOverlay(onDismiss = { showTutorial = false })
            }
        }
    }
}

@Composable
fun RemoteControlToolbar(
    mode: RemoteMode,
    onModeChange: (RemoteMode) -> Unit,
    onSpecialKey: (String) -> Unit,
    onQualityChange: (StreamQuality) -> Unit
) {
    Surface(
        color = Color.Black.copy(alpha = 0.9f),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column {
            // Mode selector
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ToolbarButton(
                    label = "Desktop",
                    icon = Icons.Rounded.DesktopWindows,
                    selected = mode == RemoteMode.DESKTOP,
                    onClick = { onModeChange(RemoteMode.DESKTOP) }
                )
                ToolbarButton(
                    label = "Trackpad",
                    icon = Icons.Rounded.TouchApp,
                    selected = mode == RemoteMode.TRACKPAD,
                    onClick = { onModeChange(RemoteMode.TRACKPAD) }
                )
                ToolbarButton(
                    label = "Keyboard",
                    icon = Icons.Rounded.Keyboard,
                    selected = mode == RemoteMode.KEYBOARD,
                    onClick = { onModeChange(RemoteMode.KEYBOARD) }
                )
            }
            
            // Special keys (when keyboard mode)
            AnimatedVisibility(mode == RemoteMode.KEYBOARD) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .padding(bottom = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf("Ctrl", "Alt", "Esc", "Tab", "Del").forEach { key ->
                        SpecialKeyButton(key) { onSpecialKey(key) }
                    }
                }
            }
        }
    }
}

@Composable
fun RowScope.ToolbarButton(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = if (selected) Blue else Color.White.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.weight(1f)
    ) {
        Column(
            Modifier.padding(vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (selected) Color.White else Color.White.copy(alpha = 0.7f),
                modifier = Modifier.size(20.dp)
            )
            Text(
                label,
                fontSize = 11.sp,
                color = if (selected) Color.White else Color.White.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
fun SpecialKeyButton(key: String, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        color = Color.White.copy(alpha = 0.15f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Text(
            key,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
        )
    }
}

@Composable
fun RemoteTutorialOverlay(onDismiss: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Color(0xFF2D2D2D),
            modifier = Modifier
                .padding(32.dp)
                .widthIn(max = 400.dp)
        ) {
            Column(
                Modifier.padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                Text(
                    "Remote Desktop Gestures",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                
                GestureTip(Icons.Rounded.TouchApp, "Tap", "Left click")
                GestureTip(Icons.Rounded.TouchApp, "Double tap", "Double click")
                GestureTip(Icons.Rounded.TouchApp, "Two-finger tap", "Right click")
                GestureTip(Icons.Rounded.PanTool, "Drag", "Move cursor")
                GestureTip(Icons.Rounded.SwipeVertical, "Two-finger scroll", "Scroll page")
                GestureTip(Icons.Rounded.ZoomIn, "Pinch", "Zoom view")
                
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Blue)
                ) {
                    Text("Got it")
                }
            }
        }
    }
}

@Composable
fun GestureTip(icon: androidx.compose.ui.graphics.vector.ImageVector, gesture: String, action: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = Blue,
            modifier = Modifier.size(24.dp)
        )
        Column {
            Text(gesture, fontSize = 14.sp, fontWeight = FontWeight.Medium, color = Color.White)
            Text(action, fontSize = 12.sp, color = Color.White.copy(alpha = 0.7f))
        }
    }
}
