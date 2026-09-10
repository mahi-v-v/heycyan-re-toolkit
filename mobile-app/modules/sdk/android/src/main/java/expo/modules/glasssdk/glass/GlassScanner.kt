package expo.modules.glasssdk.glass

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

/**
 * Generic BLE scanner — one [android.bluetooth.le.BluetoothLeScanner] (NO filter)
 * that surfaces ALL nearby BLE devices with their advertisement. Vendor
 * classification happens in JS (OTA-updatable); this scanner does no filtering.
 * Vendor adapters only handle connect (by MAC).
 */
class GlassScanner(private val context: Context) {
    companion object {
        private const val TAG = "[Glass:Scanner]"
    }

    /** Called for each discovered device with (deviceId=MAC, name, rssi, advertisementJson). */
    var onDeviceFound: ((deviceId: String, name: String, rssi: Int, advertisementJson: String) -> Unit)? = null

    private val scanner get() = BluetoothAdapter.getDefaultAdapter()?.bluetoothLeScanner
    private val reported = mutableSetOf<String>()
    private var scanning = false

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED

    /** BLUETOOTH_SCAN only exists / is requested on API 31+; older devices use legacy perms. */
    private fun canScan(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            hasPermission(Manifest.permission.BLUETOOTH_SCAN)

    @SuppressLint("MissingPermission")
    fun start() {
        if (!canScan()) {
            Log.w(TAG, "BLUETOOTH_SCAN not granted — cannot scan")
            return
        }
        val s = scanner ?: run { Log.w(TAG, "No BLE scanner (adapter off?)"); return }
        reported.clear()
        if (scanning) try { s.stopScan(cb) } catch (_: Exception) {}
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            s.startScan(/* filters = */ null, settings, cb) // null filters = ALL devices
            scanning = true
            Log.i(TAG, "Started generic BLE scan")
        } catch (e: Exception) {
            Log.e(TAG, "startScan threw", e)
        }
    }

    @SuppressLint("MissingPermission")
    fun stop() {
        reported.clear()
        if (!scanning) return
        scanning = false
        if (!canScan()) return
        try { scanner?.stopScan(cb) } catch (_: Exception) {}
        Log.i(TAG, "Stopped generic BLE scan")
    }

    private val cb = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val mac = result.device.address ?: return
            // Dedupe per session to cut churn (JS also dedupes).
            if (!reported.add(mac)) return

            val rec = result.scanRecord
            val name = (try { result.device.name } catch (_: SecurityException) { null })
                ?: rec?.deviceName ?: ""

            val adv = JSONObject()
            adv.put("name", name)

            val uuids = JSONArray()
            rec?.serviceUuids?.forEach { uuids.put(it.uuid.toString().lowercase()) }
            adv.put("serviceUuids", uuids)

            val serviceData = JSONObject()
            rec?.serviceData?.forEach { (uuid, bytes) ->
                serviceData.put(uuid.uuid.toString().lowercase(), bytes.toHex())
            }
            adv.put("serviceData", serviceData)

            val mfg = JSONObject()
            rec?.manufacturerSpecificData?.let { sparse ->
                for (i in 0 until sparse.size()) {
                    val companyId = sparse.keyAt(i)
                    mfg.put(String.format("%04x", companyId), sparse.valueAt(i).toHex())
                }
            }
            adv.put("manufacturerData", mfg)

            adv.put("rawAdvertisement", rec?.bytes?.toHex() ?: "")

            onDeviceFound?.invoke(mac, name, result.rssi, adv.toString())
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "BLE scan failed: $errorCode")
        }
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { "%02x".format(it) }
