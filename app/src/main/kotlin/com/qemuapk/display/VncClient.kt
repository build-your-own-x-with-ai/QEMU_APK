package com.qemuapk.display

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.net.Socket

/**
 * Minimal VNC (RFB - Remote Framebuffer) protocol client implementation.
 * Connects to QEMU's built-in VNC server to receive framebuffer updates
 * and send input events.
 *
 * Implements RFB protocol version 3.8 (QEMU default).
 */
class VncClient(
    private val host: String = "127.0.0.1",
    private val port: Int = 5900
) {
    companion object {
        private const val TAG = "VncClient"

        // RFB Protocol message types
        const val MSG_FRAMEBUFFER_UPDATE = 0
        const val MSG_SET_COLOR_MAP = 1
        const val MSG_BELL = 2
        const val MSG_SERVER_CUT_TEXT = 3

        // Client message types
        const val CLIENT_SET_PIXEL_FORMAT = 0
        const val CLIENT_SET_ENCODINGS = 2
        const val CLIENT_FRAMEBUFFER_UPDATE_REQUEST = 3
        const val CLIENT_KEY_EVENT = 4
        const val CLIENT_POINTER_EVENT = 5
        const val CLIENT_CUT_TEXT = 6

        // Encoding types
        const val ENC_RAW = 0
        const val ENC_COPY_RECT = 1
        const val ENC_RRE = 2
        const val ENC_HEXTILE = 5
        const val ENC_ZRLE = 16
        const val ENC_CURSOR = -239
        const val ENC_DESKTOP_SIZE = -223
    }

    private var socket: Socket? = null
    private var input: DataInputStream? = null
    private var output: DataOutputStream? = null

    private var connected = false
    private var updateJob: Job? = null

    // Server display properties
    var serverWidth: Int = 0
        private set
    var serverHeight: Int = 0
        private set

    // Pixel format
    private var bitsPerPixel: Int = 32
    private var depth: Int = 24
    private var bigEndian: Boolean = false
    private var trueColor: Boolean = true
    private var redMax: Int = 255
    private var greenMax: Int = 255
    private var blueMax: Int = 255
    private var redShift: Int = 16
    private var greenShift: Int = 8
    private var blueShift: Int = 0

    /** Callback invoked when a framebuffer update is received */
    var onFramebufferUpdate: ((IntArray, Int, Int, Int, Int) -> Unit)? = null

    /** Callback invoked when connected */
    var onConnected: ((Int, Int) -> Unit)? = null

    /** Callback invoked on error/disconnect */
    var onDisconnected: ((String?) -> Unit)? = null

    /**
     * Connect to the VNC server.
     * @return true if connection was successful
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "Connecting to VNC server $host:$port")

            socket = Socket(host, port)
            socket?.keepAlive = true
            socket?.tcpNoDelay = true

            input = DataInputStream(socket!!.getInputStream())
            output = DataOutputStream(socket!!.getOutputStream())

            // RFB Handshake
            performHandshake()

            connected = true
            Log.d(TAG, "Connected to VNC server ($serverWidth x $serverHeight)")

            onConnected?.invoke(serverWidth, serverHeight)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to connect to VNC server", e)
            onDisconnected?.invoke(e.message)
            disconnect()
            false
        }
    }

    /**
     * Start receiving framebuffer updates in a loop.
     */
    fun startUpdateLoop(scope: CoroutineScope) {
        updateJob?.cancel()
        updateJob = scope.launch(Dispatchers.IO) {
            // Request full screen update initially
            requestFramebufferUpdate(incremental = false)
            delay(500)

            while (isActive && connected) {
                try {
                    // Request incremental update
                    requestFramebufferUpdate(incremental = true)

                    // Read server messages
                    readServerMessages()

                    // Target ~15 FPS for MVP
                    delay(66)
                } catch (e: IOException) {
                    if (connected) {
                        Log.e(TAG, "VNC connection error", e)
                        onDisconnected?.invoke(e.message)
                    }
                    connected = false
                    break
                }
            }
        }
    }

    /**
     * Disconnect from the VNC server.
     */
    fun disconnect() {
        connected = false
        updateJob?.cancel()

        try {
            socket?.close()
        } catch (_: Exception) {}

        socket = null
        input = null
        output = null
    }

    /**
     * Send a pointer (touch/mouse) event to the VNC server.
     * @param x X coordinate
     * @param y Y coordinate
     * @param buttonMask Button bitmask (bit 0 = left click)
     */
    fun sendPointerEvent(x: Int, y: Int, buttonMask: Int) {
        if (!connected) return

        try {
            val out = output ?: return
            synchronized(out) {
                out.writeByte(CLIENT_POINTER_EVENT)
                out.writeByte(buttonMask)
                out.writeShort(x)
                out.writeShort(y)
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send pointer event", e)
        }
    }

    /**
     * Send a key event to the VNC server.
     * @param down true for key press, false for key release
     * @param keySym X11 keysym value
     */
    fun sendKeyEvent(down: Boolean, keySym: Int) {
        if (!connected) return

        try {
            val out = output ?: return
            synchronized(out) {
                out.writeByte(CLIENT_KEY_EVENT)
                out.writeByte(if (down) 1 else 0)
                out.writeShort(0) // padding
                out.writeInt(keySym)
                out.flush()
            }
        } catch (e: IOException) {
            Log.w(TAG, "Failed to send key event", e)
        }
    }

    // ---- Private Protocol Implementation ----

    private fun performHandshake() {
        val inp = input!!
        val out = output!!

        // Read server protocol version
        val versionStr = ByteArray(12)
        inp.readFully(versionStr)
        val version = String(versionStr).trim()
        Log.d(TAG, "Server version: $version")

        // Send client protocol version (match server)
        out.write(versionStr)
        out.flush()

        // Security type (RFB 3.8+)
        val securityTypesCount = inp.readUnsignedByte()
        if (securityTypesCount == 0) {
            val reasonLen = inp.readInt()
            val reason = ByteArray(reasonLen)
            inp.readFully(reason)
            throw IOException("Server refused connection: ${String(reason)}")
        }

        val securityTypes = ByteArray(securityTypesCount)
        inp.readFully(securityTypes)

        // Select "None" security (type 1)
        val selectedType = securityTypes.firstOrNull { it.toInt() == 1 }
        if (selectedType != null) {
            out.writeByte(1) // Security type: None
            out.flush()
        } else {
            // Try first available
            out.writeByte(securityTypes[0].toInt())
            out.flush()
        }

        // Security result (RFB 3.8 with non-None security)
        if (selectedType == null) {
            val result = inp.readInt()
            if (result != 0) {
                throw IOException("Security handshake failed with result: $result")
            }
        }

        // Client init (shared flag = 1, allow shared sessions)
        out.writeByte(1)
        out.flush()

        // Server init
        serverWidth = inp.readUnsignedShort()
        serverHeight = inp.readUnsignedShort()

        // Pixel format (16 bytes)
        bitsPerPixel = inp.readUnsignedByte()
        depth = inp.readUnsignedByte()
        bigEndian = inp.readUnsignedByte() != 0
        trueColor = inp.readUnsignedByte() != 0
        redMax = inp.readUnsignedShort()
        greenMax = inp.readUnsignedShort()
        blueMax = inp.readUnsignedShort()
        redShift = inp.readUnsignedByte()
        greenShift = inp.readUnsignedByte()
        blueShift = inp.readUnsignedByte()
        inp.skipBytes(3) // padding

        // Server name
        val nameLen = inp.readInt()
        val nameBytes = ByteArray(nameLen)
        inp.readFully(nameBytes)
        val serverName = String(nameBytes)
        Log.d(TAG, "Server name: $serverName")

        // Set our preferred pixel format (ARGB 8888 for Android Bitmap)
        setPixelFormat()

        // Set encodings
        setEncodings()
    }

    private fun setPixelFormat() {
        val out = output!!
        synchronized(out) {
            out.writeByte(CLIENT_SET_PIXEL_FORMAT)
            out.write(ByteArray(3)) // padding
            // Pixel format: 32bpp, 24 depth, little-endian, true color
            out.writeByte(32)  // bits per pixel
            out.writeByte(24)  // depth
            out.writeByte(0)   // big-endian = false
            out.writeByte(1)   // true-color = true
            out.writeShort(255) // red-max
            out.writeShort(255) // green-max
            out.writeShort(255) // blue-max
            out.writeByte(16)  // red-shift
            out.writeByte(8)   // green-shift
            out.writeByte(0)   // blue-shift
            out.write(ByteArray(3)) // padding
            out.flush()
        }
    }

    private fun setEncodings() {
        val out = output!!
        val encodings = intArrayOf(
            ENC_COPY_RECT,
            ENC_RRE,
            ENC_HEXTILE,
            ENC_RAW,
            ENC_DESKTOP_SIZE,
            ENC_CURSOR
        )

        synchronized(out) {
            out.writeByte(CLIENT_SET_ENCODINGS)
            out.writeByte(0) // padding
            out.writeShort(encodings.size)
            for (enc in encodings) {
                out.writeInt(enc)
            }
            out.flush()
        }
    }

    private fun requestFramebufferUpdate(incremental: Boolean) {
        val out = output ?: return
        synchronized(out) {
            out.writeByte(CLIENT_FRAMEBUFFER_UPDATE_REQUEST)
            out.writeByte(if (incremental) 1 else 0)
            out.writeShort(0) // x
            out.writeShort(0) // y
            out.writeShort(serverWidth)  // width
            out.writeShort(serverHeight) // height
            out.flush()
        }
    }

    private fun readServerMessages() {
        val inp = input ?: return

        if (inp.available() < 1) return

        val msgType = inp.readUnsignedByte()

        when (msgType) {
            MSG_FRAMEBUFFER_UPDATE -> handleFramebufferUpdate(inp)
            MSG_BELL -> Log.d(TAG, "Bell!")
            MSG_SERVER_CUT_TEXT -> handleServerCutText(inp)
            else -> Log.w(TAG, "Unknown server message type: $msgType")
        }
    }

    private fun handleFramebufferUpdate(inp: DataInputStream) {
        inp.readUnsignedByte() // padding
        val numRects = inp.readUnsignedShort()

        for (i in 0 until numRects) {
            val x = inp.readUnsignedShort()
            val y = inp.readUnsignedShort()
            val width = inp.readUnsignedShort()
            val height = inp.readUnsignedShort()
            val encoding = inp.readInt()

            when (encoding) {
                ENC_RAW -> handleRawEncoding(inp, x, y, width, height)
                ENC_COPY_RECT -> handleCopyRectEncoding(inp, x, y, width, height)
                ENC_RRE -> handleRreEncoding(inp, x, y, width, height)
                ENC_HEXTILE -> handleHextileEncoding(inp, x, y, width, height)
                ENC_DESKTOP_SIZE -> {
                    serverWidth = width
                    serverHeight = height
                    onConnected?.invoke(width, height)
                }
                ENC_CURSOR -> handleCursorEncoding(inp, width, height)
                else -> Log.w(TAG, "Unsupported encoding: $encoding")
            }
        }
    }

    private fun handleRawEncoding(
        inp: DataInputStream,
        x: Int, y: Int, width: Int, height: Int
    ) {
        val pixelCount = width * height
        val pixels = IntArray(pixelCount)
        val buffer = ByteArray(pixelCount * 4)
        inp.readFully(buffer)

        for (i in 0 until pixelCount) {
            val offset = i * 4
            val b = buffer[offset].toInt() and 0xFF
            val g = buffer[offset + 1].toInt() and 0xFF
            val r = buffer[offset + 2].toInt() and 0xFF
            val a = 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }

        onFramebufferUpdate?.invoke(pixels, x, y, width, height)
    }

    private fun handleCopyRectEncoding(
        inp: DataInputStream,
        x: Int, y: Int, width: Int, height: Int
    ) {
        val srcX = inp.readUnsignedShort()
        val srcY = inp.readUnsignedShort()
        // CopyRect is complex without a full framebuffer cache;
        // for MVP, read it as raw data after requesting a full update
        Log.d(TAG, "CopyRect: src=($srcX,$srcY) dst=($x,$y) size=${width}x$height")
    }

    private fun handleRreEncoding(
        inp: DataInputStream,
        x: Int, y: Int, width: Int, height: Int
    ) {
        val numSubrects = inp.readInt()
        // Background pixel
        val bgPixel = readPixel(inp)

        // Deliver background as a full rect
        val pixels = IntArray(width * height) { bgPixel }
        onFramebufferUpdate?.invoke(pixels, x, y, width, height)

        // Sub-rectangles
        for (i in 0 until numSubrects) {
            val pixel = readPixel(inp)
            val sx = inp.readUnsignedShort()
            val sy = inp.readUnsignedShort()
            val sw = inp.readUnsignedShort()
            val sh = inp.readUnsignedShort()

            val subPixels = IntArray(sw * sh) { pixel }
            onFramebufferUpdate?.invoke(subPixels, x + sx, y + sy, sw, sh)
        }
    }

    private fun handleHextileEncoding(
        inp: DataInputStream,
        x: Int, y: Int, width: Int, height: Int
    ) {
        // Hextile is complex; for MVP, skip and request raw
        // Read minimum to avoid desync
        var bgColor = 0
        var fgColor = 0
        var ty = y
        while (ty < y + height) {
            val th = minOf(16, y + height - ty)
            var tx = x
            while (tx < x + width) {
                val tw = minOf(16, x + width - tx)
                val subEncoding = inp.readUnsignedByte()

                if (subEncoding and 0x01 != 0) {
                    // Raw sub-tile
                    val pixelCount = tw * th
                    val pixels = IntArray(pixelCount)
                    val buffer = ByteArray(pixelCount * 4)
                    inp.readFully(buffer)
                    for (i in 0 until pixelCount) {
                        val offset = i * 4
                        val b = buffer[offset].toInt() and 0xFF
                        val g = buffer[offset + 1].toInt() and 0xFF
                        val r = buffer[offset + 2].toInt() and 0xFF
                        pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                    }
                    onFramebufferUpdate?.invoke(pixels, tx, ty, tw, th)
                } else {
                    if (subEncoding and 0x02 != 0) bgColor = readPixel(inp)
                    if (subEncoding and 0x04 != 0) fgColor = readPixel(inp)

                    val pixels = IntArray(tw * th) { bgColor }
                    onFramebufferUpdate?.invoke(pixels, tx, ty, tw, th)

                    if (subEncoding and 0x08 != 0) {
                        // Subrects colored
                        val numSubrects = inp.readUnsignedByte()
                        for (i in 0 until numSubrects) {
                            readPixel(inp) // color
                            inp.readUnsignedByte() // x,y
                            inp.readUnsignedByte() // w,h
                        }
                    } else if (subEncoding and 0x10 != 0) {
                        // Subrects uncolored
                        val numSubrects = inp.readUnsignedByte()
                        for (i in 0 until numSubrects) {
                            inp.readUnsignedByte() // x,y
                            inp.readUnsignedByte() // w,h
                        }
                    }
                }
                tx += 16
            }
            ty += 16
        }
    }

    private fun handleCursorEncoding(inp: DataInputStream, width: Int, height: Int) {
        val pixelCount = width * height
        if (pixelCount > 0) {
            inp.skipBytes(pixelCount * 4) // pixel data
            inp.skipBytes(((width + 7) / 8) * height) // bitmask
        }
    }

    private fun handleServerCutText(inp: DataInputStream) {
        inp.skipBytes(3) // padding
        val length = inp.readInt()
        inp.skipBytes(length) // text content
    }

    private fun readPixel(inp: DataInputStream): Int {
        val b = inp.readUnsignedByte()
        val g = inp.readUnsignedByte()
        val r = inp.readUnsignedByte()
        inp.readUnsignedByte() // padding/alpha
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
