package com.qemuapk.display

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * SurfaceView that renders the guest VM display via VNC protocol.
 * Handles both the VNC connection and the rendering loop.
 */
class VncCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : SurfaceView(context, attrs, defStyleAttr), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "VncCanvasView"
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val displayManager = DisplayManager()
    private var vncClient: VncClient? = null
    private var renderJob: Job? = null
    private val backgroundPaint = Paint().apply { color = Color.BLACK }

    // Pending framebuffer updates
    private var pendingUpdate: FramebufferUpdate? = null
    private val updateLock = Object()

    data class FramebufferUpdate(
        val pixels: IntArray,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int
    )

    /** Connection state callbacks */
    var onVncConnected: ((Int, Int) -> Unit)? = null
    var onVncDisconnected: ((String?) -> Unit)? = null

    init {
        holder.addCallback(this)
        isFocusable = true
    }

    /**
     * Connect to a VNC server.
     * @param host VNC server hostname/IP
     * @param port VNC server port (default 5900)
     */
    fun connect(host: String = "127.0.0.1", port: Int = 5900) {
        disconnect()

        vncClient = VncClient(host, port).apply {
            onConnected = { width, height ->
                Log.d(TAG, "VNC connected: ${width}x$height")
                onVncConnected?.invoke(width, height)
            }

            onDisconnected = { reason ->
                Log.d(TAG, "VNC disconnected: $reason")
                onVncDisconnected?.invoke(reason)
            }

            onFramebufferUpdate = { pixels, x, y, width, height ->
                synchronized(updateLock) {
                    pendingUpdate = FramebufferUpdate(pixels, x, y, width, height)
                }
            }
        }

        scope.launch(Dispatchers.IO) {
            val connected = vncClient?.connect() ?: false
            if (connected) {
                vncClient?.startUpdateLoop(scope)
                startRenderLoop()
            }
        }
    }

    /**
     * Disconnect from the VNC server.
     */
    fun disconnect() {
        vncClient?.disconnect()
        vncClient = null
        renderJob?.cancel()
    }

    /**
     * Send a pointer event to the VNC server.
     * Coordinates are in host view space and will be mapped to guest coordinates.
     */
    fun sendPointerEvent(hostX: Float, hostY: Float, buttonMask: Int) {
        val guestCoords = displayManager.mapHostToGuest(hostX, hostY)
        if (guestCoords != null) {
            vncClient?.sendPointerEvent(guestCoords.first, guestCoords.second, buttonMask)
        }
    }

    /**
     * Send a key event to the VNC server.
     * @param down true for press, false for release
     * @param keySym X11 keysym value
     */
    fun sendKeyEvent(down: Boolean, keySym: Int) {
        vncClient?.sendKeyEvent(down, keySym)
    }

    /**
     * Get the display manager for coordinate mapping.
     */
    fun getDisplayManager(): DisplayManager = displayManager

    /**
     * Check if VNC is connected.
     */
    fun isConnected(): Boolean = vncClient != null

    // ---- SurfaceView Callbacks ----

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        displayManager.updateHostDimensions(width, height)
        Log.d(TAG, "Surface changed: ${width}x$height")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        renderJob?.cancel()
    }

    // ---- Rendering ----

    private fun startRenderLoop() {
        renderJob?.cancel()
        renderJob = scope.launch(Dispatchers.Default) {
            while (true) {
                renderFrame()
                kotlinx.coroutines.delay(16) // ~60 FPS render loop
            }
        }
    }

    private fun renderFrame() {
        val canvas: Canvas
        try {
            canvas = holder.lockCanvas() ?: return
        } catch (e: Exception) {
            return
        }

        try {
            // Clear background
            canvas.drawRect(0f, 0f, canvas.width.toFloat(), canvas.height.toFloat(), backgroundPaint)

            // Apply pending framebuffer update
            val update: FramebufferUpdate?
            synchronized(updateLock) {
                update = pendingUpdate
                pendingUpdate = null
            }

            if (update != null) {
                displayManager.updateFramebuffer(
                    update.pixels, update.width, update.height
                )
            }

            // Draw the framebuffer
            displayManager.drawFramebuffer(canvas)
        } finally {
            try {
                holder.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to post canvas", e)
            }
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        disconnect()
        scope.cancel()
    }
}
