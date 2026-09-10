package expo.modules.glasssdk.glass.vendors.k900

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import android.content.Context
import android.util.Log
import com.xy.bt.api.BluetoothLEListener
import com.xy.bt.base.BluetoothLEController
import com.xy.bt.util.BtUtil

/**
 * K900 BLE connection helper - mimics BleBaseService without foreground service
 * Copies logic from BleService.java (lines 164-223) using K900 SDK's BluetoothLEController
 */
class K900BleConnection(private val context: Context) {
    companion object {
        private const val TAG = "[K900:BLE]"
    }

    private var bleController: BluetoothLEController? = null
    private var connectionCallback: ConnectionCallback? = null
    private val btMap = HashMap<String, String>()

    interface ConnectionCallback {
        fun onConnectStateChange(connected: Boolean)
        fun onBleRecvData(data: ByteArray)
        fun onDeviceFound(btAddress: String, device: BluetoothDevice, rssi: Short)
        fun onAiImageData(imageData: ByteArray)
    }

    fun init(callback: ConnectionCallback) {
        Log.d(TAG, "Initializing K900 BLE connection")
        this.connectionCallback = callback

        // Create BluetoothLEController (from K900 SDK, used by BleBaseService)
        bleController = BluetoothLEController.getInstance().build(context)

        // Set up callbacks (mimics BleBaseService.mBleListener)
        bleController?.setBluetoothListener(object : BluetoothLEListener {
            override fun onActionStateChanged(preState: Int, state: Int) {}

            override fun onActionDiscoveryStateChanged(discoveryState: String?) {
                if ("android.bluetooth.adapter.action.DISCOVERY_FINISHED" == discoveryState) {
                    Log.d(TAG, "Scan finished")
                }
            }

            override fun onActionScanModeChanged(preScanMode: Int, scanMode: Int) {}

            override fun onBluetoothServiceStateChanged(state: Int) {
                Log.d(TAG, "BLE service state: $state")
                when (state) {
                    3 -> callback.onConnectStateChange(true)
                    4, 5 -> callback.onConnectStateChange(false)
                }
            }

            @SuppressLint("MissingPermission")
            override fun onActionDeviceFound(
                device: BluetoothDevice?,
                rssi: Short,
                advData: ByteArray?
            ) {
                if (device != null && advData != null) {
                    val node = BtUtil.getBTAddress(advData)
                    if (node != null && !btMap.containsKey(device.address)) {
                        btMap[device.address] = device.name ?: "Unknown"
                        Log.d(TAG, "Device found: ${device.name} (${node.btAddress})")
                        callback.onDeviceFound(node.btAddress, device, rssi)
                    }
                }
            }


            override fun onBondStateChange(device: BluetoothDevice?, state: Int) {}

            override fun onA2dpConnectResult(device: BluetoothDevice?, bSucc: Boolean) {}

            override fun onReadData(characteristic: BluetoothGattCharacteristic?) {}

            override fun onWriteData(characteristic: BluetoothGattCharacteristic?) {}

            override fun onReadData(data: ByteArray?) {
                data?.let {
                    Log.d(TAG, "Received ${it.size} bytes")
                    callback.onBleRecvData(it)
                }
            }

            override fun onDiscoveringCharacteristics(characteristics: MutableList<BluetoothGattCharacteristic>?) {}

            override fun onDiscoveringServices(services: MutableList<BluetoothGattService>?) {}
        })

        // Configure UUIDs (from BleBaseService.initBT)
        bleController?.setServiceUUid("00004860-0000-1000-8000-00805f9b34fb")
        bleController?.setReadCharacteristic("000070FF-0000-1000-8000-00805f9b34fb")
        bleController?.setWriteCharacteristic("000071FF-0000-1000-8000-00805f9b34fb")

        // Open Bluetooth if not enabled
        if (bleController?.isEnabled == false) {
            bleController?.openBluetooth()
        }

        // Set up AI file listener
        bleController?.setAiFileListener { imageData ->
            Log.d(TAG, "Received AI image data: ${imageData.size} bytes")
            callback.onAiImageData(imageData)
        }
    }

    fun connect(macAddress: String) {
        Log.d(TAG, "Connecting to: $macAddress")
        bleController?.connect(macAddress)
    }

    fun disconnect() {
        Log.d(TAG, "Disconnecting")
        bleController?.disconnect()
    }

    fun startScan(): Boolean {
        Log.d(TAG, "Starting BLE scan $bleController")
        btMap.clear()
        bleController?.setScanTime(10000) // 10 seconds scan time
        return bleController?.startScan() ?: false
    }

    fun cancelScan() {
        Log.d(TAG, "Canceling BLE scan")
        bleController?.cancelScan()
    }

    fun isConnected(): Boolean {
        // Note: BluetoothLEController doesn't have isConnected() method
        // Connection state is tracked via onBluetoothServiceStateChanged callback
        return bleController != null
    }

    fun getBleController(): BluetoothLEController? = bleController

    fun destroy() {
        Log.d(TAG, "Destroying BLE connection")
        bleController?.disconnect()
        bleController?.release()
        bleController = null
        btMap.clear()
    }
}
