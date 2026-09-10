package expo.modules.glasssdk.stream

import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import android.view.Surface
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import livekit.org.webrtc.CapturerObserver
import livekit.org.webrtc.SurfaceTextureHelper
import livekit.org.webrtc.VideoCapturer
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * LiveKit [VideoCapturer] that reads H.264 NAL units from the glass TCP stream,
 * decodes them with [MediaCodec], and delivers frames to LiveKit via
 * [SurfaceTextureHelper].
 *
 * Manages its own TCP connection — no broker needed. The same [LocalVideoTrack]
 * that receives these frames is also rendered locally by attaching a
 * [TextureViewRenderer] via [LocalVideoTrack.addRenderer].
 *
 * Follows LiveKit's BitmapFrameCapturer pattern:
 *   initialize() — store helper + observer, create Surface from surfaceTexture
 *   startCapture() — setTextureSize, startListening, launch TCP coroutine
 *   stopCapture() — cancel TCP, stopListening
 */
class GlassVideoCapturer(
    private val host: String,
    private val port: Int,
    private val context: Context,
) : VideoCapturer {

    companion object {
        private const val TAG = "[Glass:VideoCapturer]"
        private const val MIME_TYPE = "video/avc"
        private const val WIDTH = 1280
        private const val HEIGHT = 720
        private const val RECONNECT_DELAY_MS = 1500L
    }

    private var surfaceTextureHelper: SurfaceTextureHelper? = null
    private var capturerObserver: CapturerObserver? = null
    private var decoderSurface: Surface? = null
    private var decoder: MediaCodec? = null
    private var captureScope: CoroutineScope? = null
    private var disposed = false

    private val stateLock = Any()

    // ── VideoCapturer ─────────────────────────────────────────────────────────

    override fun initialize(
        helper: SurfaceTextureHelper,
        context: Context,
        observer: CapturerObserver,
    ) {
        synchronized(stateLock) {
            surfaceTextureHelper = helper
            capturerObserver = observer
            decoderSurface = Surface(helper.surfaceTexture)
            Log.d(TAG, "Initialized (host=$host port=$port)")
        }
    }

    override fun startCapture(width: Int, height: Int, fps: Int) {
        synchronized(stateLock) {
            if (disposed) return
            val helper = surfaceTextureHelper ?: return
            // Tell SurfaceTextureHelper the frame dimensions and wire it to forward
            // each rendered frame to the CapturerObserver (→ WebRTC encoder → LiveKit).
            // startListening must be called here (not in initialize) — matches
            // LiveKit's BitmapFrameCapturer reference implementation.
            helper.setTextureSize(WIDTH, HEIGHT)
            helper.startListening { frame -> capturerObserver?.onFrameCaptured(frame) }

            captureScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            captureScope!!.launch { connectAndDecode() }

            capturerObserver?.onCapturerStarted(true)
            Log.d(TAG, "Capture started")
        }
    }

    @Throws(InterruptedException::class)
    override fun stopCapture() {
        synchronized(stateLock) {
            captureScope?.cancel()
            captureScope = null
            releaseDecoder()
            surfaceTextureHelper?.stopListening()
            capturerObserver?.onCapturerStopped()
            Log.d(TAG, "Capture stopped")
        }
    }

    override fun changeCaptureFormat(width: Int, height: Int, fps: Int) {
        // Format is dictated by the glass encoder
    }

    override fun dispose() {
        synchronized(stateLock) {
            if (disposed) return
            stopCapture()
            decoderSurface?.release()
            decoderSurface = null
            disposed = true
            Log.d(TAG, "Disposed")
        }
    }

    override fun isScreencast(): Boolean = false

    // ── TCP → MediaCodec loop ─────────────────────────────────────────────────

    /**
     * Create a socket on the WiFi network that shares a /24 subnet with the glass IP.
     * Uses Network.socketFactory (not bindSocket) so the network is attached from creation.
     *
     * Selection priority:
     *   1. WiFi network whose link address is on the same /24 subnet as the glass IP
     *   2. Active network if it is WiFi
     *   3. Any WiFi network
     *   4. Plain Socket (default routing)
     */
    private fun createSocket(): Socket {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val glassSubnet = host.substringBeforeLast(".")

        fun isWifi(net: Network) =
            cm?.getNetworkCapabilities(net)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true

        val wifiNetwork = cm?.allNetworks
            ?.filter { isWifi(it) }
            ?.maxByOrNull { net ->
                // Rank: 2 if link address is on same subnet, 1 otherwise
                val onSubnet = cm.getLinkProperties(net)
                    ?.linkAddresses
                    ?.any { la -> la.address.hostAddress?.startsWith("$glassSubnet.") == true }
                    ?: false
                if (onSubnet) 2 else 1
            }
            ?: cm?.activeNetwork?.takeIf { isWifi(it) }

        val socket: Socket = if (wifiNetwork != null) {
            try {
                val sock = wifiNetwork.socketFactory.createSocket()
                    ?: throw Exception("socketFactory returned null")
                val linkAddr = cm?.getLinkProperties(wifiNetwork)
                    ?.linkAddresses
                    ?.firstOrNull { it.address.hostAddress?.startsWith("$glassSubnet.") == true }
                    ?.address?.hostAddress ?: "?"
                Log.d(TAG, "Socket created on WiFi network (local=$linkAddr) for $host:$port")
                sock
            } catch (e: Exception) {
                Log.w(TAG, "WiFi socketFactory failed: ${e.message} — using plain socket")
                Socket()
            }
        } else {
            Log.w(TAG, "No WiFi network found — using default routing for $host:$port")
            Socket()
        }

        socket.tcpNoDelay = true
        socket.connect(InetSocketAddress(host, port), 5000)
        socket.soTimeout = 5000
        return socket
    }

    private suspend fun connectAndDecode() {
        while (currentCoroutineContext().isActive) {
            var socket: Socket? = null
            try {
                Log.d(TAG, "Connecting to $host:$port")
                socket = createSocket()
                Log.d(TAG, "TCP connected — decoding")
                val input = DataInputStream(socket.getInputStream())
                while (currentCoroutineContext().isActive && !socket.isClosed) {
                    val length = input.readInt()
                    if (length <= 0 || length > 2_000_000) {
                        Log.w(TAG, "Invalid NAL length: $length — skipping")
                        continue
                    }
                    val data = ByteArray(length)
                    input.readFully(data)
                    onNalUnit(data)
                }
            } catch (e: Exception) {
                if (!currentCoroutineContext().isActive) break
                Log.w(TAG, "Stream error: ${e.message}")
            } finally {
                socket?.runCatching { close() }
                releaseDecoder()
            }
            if (!currentCoroutineContext().isActive) break
            Log.d(TAG, "Reconnecting in ${RECONNECT_DELAY_MS}ms…")
            delay(RECONNECT_DELAY_MS)
        }
    }

    private fun onNalUnit(data: ByteArray) {
        val surf = decoderSurface ?: return
        if (decoder == null) {
            decoder = tryConfigureDecoder(surf) ?: return
        }
        feedToDecoder(data)
        drainDecoder()
    }

    // ── MediaCodec helpers ────────────────────────────────────────────────────

    private fun tryConfigureDecoder(surface: Surface): MediaCodec? {
        return try {
            val format = MediaFormat.createVideoFormat(MIME_TYPE, WIDTH, HEIGHT)
            if (android.os.Build.VERSION.SDK_INT >= 30) {
                format.setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
            }
            val codec = MediaCodec.createDecoderByType(MIME_TYPE)
            codec.configure(format, surface, null, 0)
            codec.start()
            Log.d(TAG, "Decoder started")
            codec
        } catch (e: Exception) {
            Log.e(TAG, "Failed to configure decoder", e)
            null
        }
    }

    private fun feedToDecoder(nalUnit: ByteArray) {
        val codec = decoder ?: return
        try {
            val idx = codec.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val buf = codec.getInputBuffer(idx) ?: return
                buf.clear()
                buf.put(nalUnit)
                codec.queueInputBuffer(idx, 0, nalUnit.size, System.nanoTime() / 1000, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Feed error: ${e.message}")
        }
    }

    private fun drainDecoder() {
        val codec = decoder ?: return
        try {
            val info = MediaCodec.BufferInfo()
            var idx = codec.dequeueOutputBuffer(info, 0)
            while (idx >= 0) {
                // render=true → hardware blit to Surface → SurfaceTextureHelper picks it up
                codec.releaseOutputBuffer(idx, true)
                idx = codec.dequeueOutputBuffer(info, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Drain error: ${e.message}")
        }
    }

    private fun releaseDecoder() {
        decoder?.runCatching { stop(); release() }
        decoder = null
    }
}
