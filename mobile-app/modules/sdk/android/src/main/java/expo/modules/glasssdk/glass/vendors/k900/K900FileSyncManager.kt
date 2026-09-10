package expo.modules.glasssdk.glass.vendors.k900

import android.content.Context
import android.util.Log
import com.xy.ksdk.api.cmd.CmdSendManager
import com.xy.ksdk.api.cmd.CmdType
import com.xy.ksdk.api.wifi.FileSaver
import com.xy.ksdk.api.wifi.FileSaverListener
import com.xy.ksdk.api.wifi.SocketListener
import com.xy.ksdk.api.wifi.SocketManager
import com.xy.ksdk.protos.FileBody.FileMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.io.File

/**
 * Manages K900 file sync via WiFi connection
 * Flow: Glass creates hotspot → Phone connects to glass WiFi → Socket connection → File transfer
 */
class K900FileSyncManager(
    private val context: Context,
    private val mediaDirectory: String,
    private val scope: CoroutineScope
) {
    companion object {
        private const val TAG = "[Glass:K900FileSync]"
    }

    interface FileSyncCallback {
        fun onFileProgress(fileType: Int, filePath: String, fileSize: Int, percent: Int)
        fun onFileFinish(fileType: Int, filePath: String, fileSize: Int)
        fun onError(error: Exception)
        fun onFilesRequested()
        fun onWifiConnectedForStream(host: String, port: Int)
    }

    private var callback: FileSyncCallback? = null
    private var socketManager: SocketManager? = null
    private var wifiConnector: WifiConnector? = null
    private var isSocketConnected = false
    private var isTransferInProgress = false
    private var streamMode = false

    fun init(callback: FileSyncCallback) {
        this.callback = callback

        // Initialize FileSaver
        FileSaver.getInstance().init(context, object : FileSaverListener {

            override fun onGetSaveFolder(p0: Context?, fileType: Int, p2: String?): String {
                val subFolder = when (fileType.toByte()) {
                    CmdType.CMD_TYPE_PHOTO -> "photo"
                    CmdType.CMD_TYPE_VIDEO -> "video"
                    CmdType.CMD_TYPE_AUDIO -> "audio"
                    else -> "other"
                }

                val dir = File(mediaDirectory, subFolder)
                if (!dir.exists()) dir.mkdirs()

                return dir.absolutePath
            }

            override fun onFileRecvProgress(p0: Int, p1: String, p2: Int, p3: Int) {
                callback.onFileProgress(p0, p1, p2, p3)
            }

            override fun onFileRecvFinish(p0: Int, p1: String, p2: Int) {
                callback.onFileFinish(p0, p1, p2)
            }
        })

        // Initialize WiFi connector
        wifiConnector = WifiConnector.getInstance()
        wifiConnector?.init(context, object : WifiConnector.WifiListener {
            override fun onWifiConnectResult(handle: Long, success: Boolean) {
                if (success) {
                    val glassIP = wifiConnector?.getServerIP()
                    if (glassIP != null && glassIP.isNotEmpty()) {
                        if (streamMode) {
                            streamMode = false
                            Log.d(TAG, "WiFi connected for streaming, host=$glassIP")
                            scope.launch { callback?.onWifiConnectedForStream(glassIP, K900StreamManager.STREAM_PORT) }
                        } else {
                            Log.d(TAG, "Connected to glass WiFi - discovering IP")
                            startSocket(glassIP)
                        }
                    } else {
                        scope.launch {
                            callback?.onError(Exception("Failed to get glass IP address"))
                        }
                    }
                } else {
                    Log.e(TAG, "Failed to connect to glass WiFi")
                    scope.launch {
                        callback?.onError(Exception("Failed to connect to glass WiFi"))
                    }
                }
            }

            override fun onWifiConnectTimeout() {
                Log.e(TAG, "WiFi connection timeout")
                streamMode = false
                scope.launch {
                    callback?.onError(Exception("WiFi connection timeout"))
                }
            }
        })
    }

    /**
     * Start file sync - sends command to glass to create hotspot
     */
    fun startFileSync() {
        Log.d(TAG, "Requesting glass to create WiFi hotspot")
        CmdSendManager.getInstance().requestServerOpenHotspot()
    }

    /**
     * Request glass hotspot for streaming (not file transfer).
     * Sets streamMode=true so WiFi connect callback fires onWifiConnectedForStream instead of startSocket.
     */
    fun startStreamWifi() {
        streamMode = true
        Log.d(TAG, "Requesting glass hotspot for streaming (streamMode=true)")
        CmdSendManager.getInstance().requestServerOpenHotspot()
    }

    /**
     * Connect to glass WiFi hotspot
     * Call this when glass sends SSID/password via BLE
     */
    fun connectToGlassWifi(ssid: String, password: String) {
        Log.d(TAG, "Connecting to glass WiFi: $ssid")

        // Use WifiConnector to connect to glass hotspot
        wifiConnector?.connect(ssid, password)
        // onWifiConnectResult callback will fire when connected
    }

    /**
     * Start socket connection to glass at given IP
     */
    private fun startSocket(glassIP: String) {
        Log.d(TAG, "Starting socket connection to glass at $glassIP")

        try {
            // Create socket manager if needed
            if (socketManager == null) {
                socketManager = SocketManager(context)
                socketManager?.registerListener(object : SocketListener {
                    override fun onFileConnectState(connected: Boolean) {
                        scope.launch {
                            isSocketConnected = connected
                            if (connected) {
                                Log.d(TAG, "Socket connected to glass!")
                                isTransferInProgress = true
                                requestFiles()
                            } else {
                                // Socket disconnected
                                if (isTransferInProgress) {
                                    // Normal: Glass closes socket after transfer completes
                                    Log.d(TAG, "Socket disconnected (transfer complete)")
                                    isTransferInProgress = false
                                } else {
                                    // Error: Socket disconnected unexpectedly
                                    Log.e(TAG, "Socket disconnected unexpectedly")
                                    callback?.onError(Exception("Socket connection lost"))
                                }
                            }
                        }
                    }

                    override fun onFileReceive(fileMessage: FileMessage) {
                        // FileSaver handles file writing via its own listener
                        FileSaver.getInstance().write(fileMessage)
                    }
                })
            }

            // Connect to glass
            socketManager?.connectServer(glassIP)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to start socket connection", e)
            scope.launch {
                callback?.onError(e)
            }
        }
    }

    private fun requestFiles() {
        Log.d(TAG, "Requesting all media files from glass")
        CmdSendManager.getInstance().askFile(CmdType.CMD_TYPE_ALL_MEDIA)
        callback?.onFilesRequested()
    }

    /**
     * Clean up resources
     */
    fun cleanup() {
        Log.d(TAG, "Cleaning up file sync resources")

        wifiConnector?.disconnect()
        socketManager?.destroy()
        socketManager = null
        isSocketConnected = false
        isTransferInProgress = false
        callback = null
    }

    /**
     * Abort an in-progress sync. Tears down the WiFi connection and socket but
     * deliberately keeps `callback` so a later re-sync still works (unlike cleanup()).
     */
    fun cancelSync() {
        Log.d(TAG, "Cancelling file sync — tearing down WiFi + socket")

        wifiConnector?.disconnect()
        socketManager?.destroy()
        socketManager = null
        isSocketConnected = false
        isTransferInProgress = false
    }
}
