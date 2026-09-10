package expo.modules.glasssdk.glass.vendors.cyan

import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanResult
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.oudmon.ble.base.bluetooth.BleAction
import com.oudmon.ble.base.bluetooth.BleBaseControl
import com.oudmon.ble.base.bluetooth.BleOperateManager
import com.oudmon.ble.base.bluetooth.QCBluetoothCallbackCloneReceiver
import com.oudmon.ble.base.communication.LargeDataHandler
import com.oudmon.ble.base.scan.BleScannerHelper
import com.oudmon.ble.base.scan.ScanWrapperCallback
import java.nio.charset.StandardCharsets

class CyanBleConnection(private val context: Context) {

    companion object {
        private const val TAG = "[Glass:CyanBle]"
        const val DEVICE_ID_PREFIX = "cyan:"
    }

    interface ConnectionCallback {
        fun onConnectStateChange(connected: Boolean)
        fun onServiceDiscovered()
        fun onDeviceFound(deviceId: String, deviceName: String, rssi: Int)
        fun onDeviceIpReceived(ip: String)
    }

    private var callback: ConnectionCallback? = null
    private var connectedMac: String? = null
    private var classicScanReceiverRegistered = false
    private var initialized = false
    private var serviceDiscovered = false

    @SuppressLint("MissingPermission")
    private val classicScanReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val mac = connectedMac ?: return
                    if (!device.address.equals(mac, ignoreCase = true)) return
                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                        Log.i(TAG, "Classic BT: device $mac already bonded — skipping")
                        stopClassicScan()
                        return
                    }
                    Log.i(TAG, "Classic BT: device $mac found, not bonded — initiating bond")
                    BleOperateManager.getInstance().createBondBluetoothJieLi(device)
                    stopClassicScan()
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    Log.d(TAG, "Classic BT discovery finished")
                    stopClassicScan()
                }
            }
        }
    }

    private fun startClassicScanAndBond(mac: String) {
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)
            ?.adapter ?: return
        // Check if already bonded — no need to scan
        try {
            val bonded = adapter.bondedDevices?.any { it.address.equals(mac, ignoreCase = true) } == true
            if (bonded) {
                Log.i(TAG, "Classic BT: $mac already in bonded devices — skipping scan")
                return
            }
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot check bonded devices: ${e.message}")
        }

        Log.i(TAG, "Classic BT: starting discovery to bond $mac")
        if (!classicScanReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            context.registerReceiver(classicScanReceiver, filter)
            classicScanReceiverRegistered = true
        }
        try {
            adapter.startDiscovery()
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot start classic BT discovery: ${e.message}")
            stopClassicScan()
        }
    }

    private fun stopClassicScan() {
        if (!classicScanReceiverRegistered) return
        try {
            context.unregisterReceiver(classicScanReceiver)
        } catch (_: Exception) {}
        classicScanReceiverRegistered = false
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
            adapter?.cancelDiscovery()
        } catch (_: SecurityException) {}
    }

    private val bleReceiver = object : QCBluetoothCallbackCloneReceiver() {
        override fun connectStatue(device: BluetoothDevice?, connected: Boolean) {
            Log.d(TAG, "connectStatue: connected=$connected device=${device?.address}")
            if (!connected) serviceDiscovered = false
            callback?.onConnectStateChange(connected)
        }

        override fun onServiceDiscovered() {
            if (serviceDiscovered) {
                Log.w(TAG, "onServiceDiscovered fired again while already connected — ignoring")
                return
            }
            serviceDiscovered = true
            Log.d(TAG, "onServiceDiscovered")
            LargeDataHandler.getInstance().initEnable()
            BleOperateManager.getInstance().isReady = true
            callback?.onServiceDiscovered()
            // After BLE is fully connected, scan classic BT and auto-bond if not already paired
            connectedMac?.let { startClassicScanAndBond(it) }
        }

        override fun onCharacteristicChange(address: String?, uuid: String?, data: ByteArray?) {
            if (data == null) return
            val msg = data.toString(StandardCharsets.UTF_8)
            val ip = Regex("""\b(?:(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\.){3}(?:25[0-5]|2[0-4][0-9]|[01]?[0-9][0-9]?)\b""").find(msg)?.value ?: return
            Log.d(TAG, "Device IP from BLE characteristic: $ip")
            callback?.onDeviceIpReceived(ip)
        }
    }

    private val scanCallback = object : ScanWrapperCallback {
        @SuppressLint("MissingPermission")
        override fun onLeScan(device: BluetoothDevice?, rssi: Int, scanRecord: ByteArray?) {
            if (device == null || device.name.isNullOrEmpty()) return
            val rawName = device.name
            if (!rawName.lowercase().startsWith("cy")) return
            val displayName = if (rawName.startsWith("O_")) rawName.substring(2) else rawName
            val deviceId = "$DEVICE_ID_PREFIX${device.address}"
            Log.d(TAG, "Found Cyan device: $displayName ($deviceId) rssi=$rssi")
            callback?.onDeviceFound(deviceId, displayName, rssi)
        }

        override fun onStart() {}
        override fun onStop() {}
        override fun onScanFailed(p0: Int) {}

        override fun onParsedData(
            p0: BluetoothDevice?,
            p1: com.oudmon.ble.base.scan.ScanRecord?
        ) {
            onLeScan(p0, 0, null)
        }
        override fun onBatchScanResults(p0: MutableList<ScanResult>?) {}
    }

    

    fun init(cb: ConnectionCallback) {
        this.callback = cb
        if (initialized) {
            Log.w(TAG, "init() called again — skipping duplicate receiver registration")
            return
        }
        initialized = true

        val app = context.applicationContext as Application
        BleBaseControl.getInstance(app).setmContext(app)
        BleOperateManager.getInstance(app).apply {
            setApplication(app)
            init()
        }

        LocalBroadcastManager.getInstance(app)
            .registerReceiver(bleReceiver, BleAction.getIntentFilter())
    }

    fun connect(deviceId: String) {
        val mac = deviceId.removePrefix(DEVICE_ID_PREFIX)
        connectedMac = mac
        Log.d(TAG, "Connecting to $mac")
        BleOperateManager.getInstance().connectDirectly(mac)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        BleOperateManager.getInstance().unBindDevice()
    }

    fun startScan() {
        Log.d(TAG, "Starting BLE scan")
        // Always stop first — re-registering the same ScanCallback without stopping causes
        // SCAN_FAILED_ALREADY_STARTED in the Android BLE stack, silently dropping the scan.
        try { BleScannerHelper.getInstance().stopScan(context) } catch (_: Exception) {}
        BleScannerHelper.getInstance().scanDevice(context, null, scanCallback)
    }

    fun stopScan() {
        Log.d(TAG, "Stopping BLE scan")
        BleScannerHelper.getInstance().stopScan(context)
        // Do NOT call reSetCallback() — it wipes BleScannerHelper's internal scan state,
        // forcing a full re-init on the next scanDevice() call. Leaving the callback registered
        // keeps the scanner warm and ready for the next startScan().
    }

    fun isConnected(): Boolean = BleOperateManager.getInstance().isConnected

    fun destroy() {
        stopClassicScan()
        if (initialized) {
            try {
                val app = context.applicationContext as Application
                LocalBroadcastManager.getInstance(app).unregisterReceiver(bleReceiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver", e)
            }
            initialized = false
        }
        callback = null
    }
}
