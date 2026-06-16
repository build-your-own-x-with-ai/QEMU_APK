package com.qemuapk.display

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages display scaling and rotation between the guest VM display
 * and the host Android screen.
 */
class DisplayManager(
    private val guestWidth: Int = 720,
    private val guestHeight: Int = 1280
) {
    companion object {
        private const val TAG = "DisplayManager"
    }

    /** Current host view width */
    private var hostWidth: Int = 0

    /** Current host view height */
    private var hostHeight: Int = 0

    /** Scale factor from guest to host */
    private val _scaleFactor = MutableStateFlow(1.0f)
    val scaleFactor: StateFlow<Float> = _scaleFactor.asStateFlow()

    /** Offset for centering the guest display on the host */
    private val _offsetX = MutableStateFlow(0f)
    val offsetX: StateFlow<Float> = _offsetX.asStateFlow()

    private val _offsetY = MutableStateFlow(0f)
    val offsetY: StateFlow<Float> = _offsetY.asStateFlow()

    /** Guest framebuffer bitmap */
    private var framebuffer: Bitmap? = null

    val guestDisplayWidth: Int get() = guestWidth
    val guestDisplayHeight: Int get() = guestHeight

    /**
     * Update the host view dimensions and recalculate scaling.
     */
    fun updateHostDimensions(width: Int, height: Int) {
        hostWidth = width
        hostHeight = height
        recalculateScale()
    }

    /**
     * Map a host touch coordinate to guest coordinates.
     * @return Pair of (guestX, guestY) or null if outside the guest display area
     */
    fun mapHostToGuest(hostX: Float, hostY: Float): Pair<Int, Int>? {
        val guestX = ((hostX - _offsetX.value) / _scaleFactor.value).toInt()
        val guestY = ((hostY - _offsetY.value) / _scaleFactor.value).toInt()

        if (guestX < 0 || guestX >= guestWidth || guestY < 0 || guestY >= guestHeight) {
            return null
        }

        return Pair(guestX, guestY)
    }

    /**
     * Map guest coordinates to host view coordinates.
     * @return Pair of (hostX, hostY)
     */
    fun mapGuestToHost(guestX: Int, guestY: Int): Pair<Float, Float> {
        val hostX = guestX * _scaleFactor.value + _offsetX.value
        val hostY = guestY * _scaleFactor.value + _offsetY.value
        return Pair(hostX, hostY)
    }

    /**
     * Update or create the framebuffer bitmap.
     */
    fun updateFramebuffer(pixels: IntArray, width: Int, height: Int) {
        if (framebuffer == null || framebuffer!!.width != width || framebuffer!!.height != height) {
            framebuffer?.recycle()
            framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
        framebuffer?.setPixels(pixels, 0, width, 0, 0, width, height)
    }

    /**
     * Draw the guest framebuffer onto the host canvas with proper scaling.
     */
    fun drawFramebuffer(canvas: Canvas) {
        val fb = framebuffer ?: return

        val destRect = Rect(
            _offsetX.value.toInt(),
            _offsetY.value.toInt(),
            (_offsetX.value + guestWidth * _scaleFactor.value).toInt(),
            (_offsetY.value + guestHeight * _scaleFactor.value).toInt()
        )

        canvas.drawBitmap(fb, null, destRect, null)
    }

    /**
     * Get the current framebuffer bitmap (for direct rendering).
     */
    fun getFramebuffer(): Bitmap? = framebuffer

    private fun recalculateScale() {
        if (hostWidth == 0 || hostHeight == 0) return

        val scaleX = hostWidth.toFloat() / guestWidth
        val scaleY = hostHeight.toFloat() / guestHeight
        val scale = minOf(scaleX, scaleY)

        _scaleFactor.value = scale

        // Calculate centering offset
        val scaledWidth = guestWidth * scale
        val scaledHeight = guestHeight * scale
        _offsetX.value = (hostWidth - scaledWidth) / 2f
        _offsetY.value = (hostHeight - scaledHeight) / 2f

        Log.d(TAG, "Scale: $scale, Offset: (${_offsetX.value}, ${_offsetY.value})")
    }
}
