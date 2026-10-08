package com.swarm.ai.ui.screens

import android.graphics.BitmapFactory
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.swarm.ai.remote.RemoteClient
import com.swarm.ai.remote.WsEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DesktopStreamState(
    val frame: ImageBitmap? = null,
    val fps: Int = 0,
    val latencyMs: Int = 0,
    val frameWidth: Int = 0,
    val frameHeight: Int = 0,
    val sequence: Int = 0,
    val isStreaming: Boolean = false,
    val error: String? = null
)

data class StreamQuality(
    val name: String,
    val maxFps: Int,
    val maxWidth: Int,
    val maxHeight: Int
) {
    companion object {
        val AUTO = StreamQuality("auto", 30, 1920, 1080)
        val LOW = StreamQuality("low", 20, 1280, 720)
        val MEDIUM = StreamQuality("medium", 25, 1600, 900)
        val HIGH = StreamQuality("high", 30, 1920, 1080)
    }
}

@HiltViewModel
class RemotePCViewModel @Inject constructor(
    private val client: RemoteClient
) : ViewModel() {

    private val _streamState = MutableStateFlow(DesktopStreamState())
    val streamState: StateFlow<DesktopStreamState> = _streamState.asStateFlow()

    private var streamJob: Job? = null
    private var fpsJob: Job? = null
    private var frameCount = 0
    private var lastFrameTime = 0L
    private var currentQuality = StreamQuality.AUTO

    fun startStream(quality: StreamQuality = StreamQuality.AUTO) {
        if (streamJob?.isActive == true) return
        currentQuality = quality

        streamJob?.cancel()
        streamJob = viewModelScope.launch {
            try {
                suspend fun requestStream() {
                    client.startDesktopStream(quality.name, quality.maxFps, quality.maxWidth, quality.maxHeight)
                }
                launch { delay(100); requestStream() }
                client.events().buffer(1, BufferOverflow.DROP_OLDEST).collect { event ->
                    when (event) {
                        is WsEvent.Open -> requestStream()
                        is WsEvent.CommandAck -> if (!event.ok) {
                            _streamState.value = _streamState.value.copy(error = event.error ?: "Desktop command failed")
                        }
                        is WsEvent.DesktopFrame -> {
                            handleFrame(event.jpegData, event.sequence, event.width, event.height, event.timestamp)
                        }
                        is WsEvent.Closed -> {
                            _streamState.value = _streamState.value.copy(
                                isStreaming = false,
                                error = "Connection closed"
                            )
                        }
                        is WsEvent.Failure -> {
                            _streamState.value = _streamState.value.copy(
                                isStreaming = false,
                                error = event.error.message ?: "Connection failed"
                            )
                        }
                        else -> { /* Ignore other events */ }
                    }
                }
            } catch (e: Exception) {
                _streamState.value = _streamState.value.copy(
                    isStreaming = false,
                    error = e.message ?: "Failed to start stream"
                )
            }
        }

        // FPS counter
        fpsJob?.cancel()
        fpsJob = viewModelScope.launch {
            while (isActive) {
                delay(1000)
                _streamState.value = _streamState.value.copy(fps = frameCount)
                frameCount = 0
            }
        }
    }

    fun stopStream() {
        streamJob?.cancel()
        fpsJob?.cancel()
        viewModelScope.launch {
            try {
                client.stopDesktopStream()
            } catch (e: Exception) {
                // Ignore errors on stop
            }
        }
        _streamState.value = DesktopStreamState()
    }

    fun setQuality(quality: StreamQuality) {
        if (!_streamState.value.isStreaming) return
        currentQuality = quality
        viewModelScope.launch {
            try {
                client.setDesktopOptions(
                    quality = quality.name,
                    maxFps = quality.maxFps,
                    maxWidth = quality.maxWidth,
                    maxHeight = quality.maxHeight
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    private suspend fun handleFrame(jpegData: ByteArray, sequence: Int, width: Int, height: Int, timestamp: Long) {
        try {
            // Decode JPEG to bitmap
            val bitmap = withContext(Dispatchers.Default) { BitmapFactory.decodeByteArray(jpegData, 0, jpegData.size) } ?: return
            if (bitmap.width != width || bitmap.height != height) return
            val imageBitmap = bitmap.asImageBitmap()

            // Calculate latency (simple estimate based on frame time)
            val now = System.currentTimeMillis()
            val latency = if (timestamp > 0) (now - timestamp).coerceIn(0, 60000).toInt() else 0
            lastFrameTime = now

            frameCount++

            _streamState.value = _streamState.value.copy(
                frame = imageBitmap,
                isStreaming = true,
                error = null,
                frameWidth = width,
                frameHeight = height,
                sequence = sequence,
                latencyMs = latency.coerceIn(0, 500)
            )
        } catch (e: Exception) {
            // Skip bad frames
        }
    }

    // Input methods

    fun sendClick(x: Int, y: Int, button: String = "left", double: Boolean = false) {
        viewModelScope.launch {
            try {
                client.sendDesktopInput(
                    type = "click",
                    x = x,
                    y = y,
                    button = button,
                    double = double
                )
            } catch (e: Exception) {
                // Ignore input errors
            }
        }
    }

    fun sendMove(x: Int, y: Int, relative: Boolean = false) {
        viewModelScope.launch {
            try {
                client.sendDesktopInput(
                    type = "move",
                    x = x,
                    y = y,
                    relative = relative
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun sendDrag(x: Int, y: Int, button: String = "left") {
        viewModelScope.launch {
            try {
                client.sendDesktopInput(
                    type = "drag",
                    x = x,
                    y = y,
                    button = button
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun sendScroll(deltaY: Int) {
        viewModelScope.launch {
            try {
                client.sendDesktopInput(
                    type = "scroll",
                    deltaY = deltaY
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun sendKey(key: String, text: String? = null, modifiers: List<String>? = null) {
        viewModelScope.launch {
            try {
                client.sendDesktopInput(
                    type = "key",
                    key = key,
                    text = text,
                    modifiers = modifiers
                )
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopStream()
    }
}
