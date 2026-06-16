package com.qemuapk.input

import android.util.Log
import android.view.MotionEvent
import com.qemuapk.display.VncCanvasView

/**
 * Forwards touch events from the host Android view to the guest VM
 * via VNC pointer events. Handles coordinate mapping and gesture translation.
 */
class TouchForwarder(
    private val vncCanvasView: VncCanvasView
) {
    companion object {
        private const val TAG = "TouchForwarder"

        // VNC button mask bits
        const val BUTTON_LEFT = 0x01
        const val BUTTON_MIDDLE = 0x02
        const val BUTTON_RIGHT = 0x04
        const val BUTTON_SCROLL_UP = 0x08
        const val BUTTON_SCROLL_DOWN = 0x10
    }

    private var lastX = 0f
    private var lastY = 0f
    private var isDown = false

    // For scroll/fling detection
    private var scrollAccumulatorY = 0f
    private val scrollThreshold = 50f // pixels of movement before sending scroll event

    /**
     * Handle a touch event from the host view.
     * Maps coordinates and sends appropriate VNC pointer events.
     *
     * @return true if the event was consumed
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                isDown = true
                lastX = x
                lastY = y
                scrollAccumulatorY = 0f

                // Send mouse button down + move to position
                vncCanvasView.sendPointerEvent(x, y, BUTTON_LEFT)
                Log.v(TAG, "Touch down at ($x, $y)")
            }

            MotionEvent.ACTION_MOVE -> {
                if (isDown) {
                    // Accumulate scroll delta
                    scrollAccumulatorY += (y - lastY)

                    // Send mouse move with button held
                    vncCanvasView.sendPointerEvent(x, y, BUTTON_LEFT)
                } else {
                    // Hover move (no button)
                    vncCanvasView.sendPointerEvent(x, y, 0)
                }

                lastX = x
                lastY = y
            }

            MotionEvent.ACTION_UP -> {
                // Check if this was a scroll gesture
                if (Math.abs(scrollAccumulatorY) > scrollThreshold) {
                    // Already handled as scroll during move
                }

                // Release button at current position
                vncCanvasView.sendPointerEvent(x, y, BUTTON_LEFT)
                // Then release (no buttons)
                vncCanvasView.sendPointerEvent(x, y, 0)

                isDown = false
                scrollAccumulatorY = 0f
                Log.v(TAG, "Touch up at ($x, $y)")
            }

            MotionEvent.ACTION_CANCEL -> {
                // Release any held buttons
                vncCanvasView.sendPointerEvent(lastX, lastY, 0)
                isDown = false
                scrollAccumulatorY = 0f
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                // Multi-touch: send as right-click for MVP (context menu)
                if (event.pointerCount == 2) {
                    vncCanvasView.sendPointerEvent(x, y, BUTTON_RIGHT)
                    vncCanvasView.sendPointerEvent(x, y, 0)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                // Multi-touch release
            }
        }

        return true
    }

    /**
     * Send a scroll event.
     * @param deltaY Positive = scroll down, negative = scroll up
     */
    fun sendScroll(deltaY: Float) {
        val buttonMask = if (deltaY > 0) BUTTON_SCROLL_DOWN else BUTTON_SCROLL_UP
        vncCanvasView.sendPointerEvent(lastX, lastY, buttonMask)
        vncCanvasView.sendPointerEvent(lastX, lastY, 0)
    }
}
