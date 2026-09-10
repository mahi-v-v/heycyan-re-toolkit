package expo.modules.glasssdk.glass.vendors.k900;

import android.annotation.SuppressLint;
import android.app.Service;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.xy.bt.api.BTServiceListener;
import com.xy.bt.api.BluetoothListener;
import com.xy.bt.base.BluetoothA2dpController;
import com.xy.bt.util.BtUtil;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * A2DP audio service for K900 smart glasses.
 * Refactored to NOT extend A2dpBaseService (which creates unwanted foreground notification).
 * Uses BluetoothA2dpController directly, mimicking A2dpBaseService logic without foreground service.
 */
public class A2dpService extends Service implements BTServiceListener {
    private static final String TAG = "[K900:A2DP]";
    public static final String EXTRA_DEVICE_ADDRESS = "device_address";
    public static final String EXTRA_COMMAND = "cmd";
    public static final String CMD_START = "start";
    public static final String CMD_STOP = "stop";

    public interface BtStateCallback {
        void onBtStateChanged(boolean connected);
    }
    private static BtStateCallback btStateCallback;
    public static void setBtStateCallback(BtStateCallback cb) { btStateCallback = cb; }

    private static final int MSG_CHECK_CONNECT = 2001;
    private String mDeviceAddr = null;
    private BluetoothDevice mDevice;
    private BluetoothA2dpController a2dpController;

    @SuppressLint("HandlerLeak")
    private Handler mHandler = new Handler()
    {
        @Override
        public void handleMessage(@NonNull Message msg) {
            if(msg.what == MSG_CHECK_CONNECT)
            {
                if(mDevice == null)
                {
                    startBT();
                }
            }
        }
    };

    // Listener for A2dp controller callbacks (mimics A2dpBaseService.mListener)
    private BluetoothListener mListener = new BluetoothListener() {
        @Override
        public void onActionStateChanged(int preState, int state) {
            // Not needed for basic A2DP
        }

        @Override
        public void onActionDiscoveryStateChanged(String discoveryState) {
            if ("android.bluetooth.adapter.action.DISCOVERY_FINISHED".equals(discoveryState)) {
                onDiscoveryStateChanged(true);
            }
        }

        @Override
        public void onActionScanModeChanged(int preScanMode, int scanMode) {
            // Not needed
        }

        @Override
        public void onBluetoothServiceStateChanged(int state) {
            Log.d(TAG, "a2dp service state: " + state);
            if (state == 3) { // Connected
                onBTConnected(a2dpController.getConnectedDevice());
            } else if (state == 4) { // Disconnected
                onBTDisconnect();
            }
        }

        @Override
        public void onActionDeviceFound(BluetoothDevice device, short rssi, byte[] advData) {
            onBTDeviceFound(device, rssi);
        }

        @Override
        public void onBondStateChange(BluetoothDevice device, int state) {
            onBTBond(device, state);
        }

        @Override
        public void onA2dpConnectResult(BluetoothDevice device, boolean bSucc) {
            A2dpService.this.onA2dpConnectResult(device, bSucc);
        }

        @Override
        public void onReadData(BluetoothDevice device, byte[] data) {
            onBTReadData(device, data);
        }
    };

    public void startBT()
    {
        Log.d(TAG, "a2dp startBT mDevice="+mDevice+",mDeviceAddr="+mDeviceAddr);
        if(mDeviceAddr != null && a2dpController != null) {
            BluetoothDevice device = getBondDeviceByAddress(mDeviceAddr);
            Log.d(TAG, "a2dp startBT device="+device);
            if (device != null) {
                mDevice = device;
                a2dpController.connect(mDevice);
                return;
            }
            try {
                mDevice = getDeviceByAddress(mDeviceAddr);
                if (mDevice != null) {
                    BtUtil.createBond(mDevice);
                }
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        Log.d(TAG, "A2DP service onCreate - initializing controller");

        // Initialize A2dp controller (mimics A2dpBaseService.initBT() without foreground service)
        a2dpController = BluetoothA2dpController.getInstance().build(this);
        a2dpController.setAppUuid(UUID.fromString("00001101-0000-1000-8000-00805f9b34fb"));
        a2dpController.setBluetoothListener(mListener);

        // Open bluetooth if not already enabled
        if (a2dpController != null && !a2dpController.isEnabled()) {
            a2dpController.openBluetooth();
        }

        Log.d(TAG, "A2dpController initialized");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null) {
            String cmd = intent.getStringExtra(EXTRA_COMMAND);
            if (cmd != null) {
                onCommand(intent, cmd);
            }
        }
        return START_STICKY;
    }

    public void onCommand(Intent intent, String cmd) {
        Log.i(TAG, "Command: " + cmd);
        // Get device address from intent if provided
        String deviceAddress = intent.getStringExtra(EXTRA_DEVICE_ADDRESS);
        if (deviceAddress != null && !deviceAddress.isEmpty()) {
            Log.d(TAG, "Received device address: " + deviceAddress);
            mDeviceAddr = deviceAddress;
        }

        if(CMD_START.equals(cmd) || "start".equals(cmd)) {
            List<BluetoothDevice> list = getConnectedDevices();
            if(list == null || list.isEmpty()) {
                Log.d(TAG, "A2DP not connected, starting BT");
                startBT();
            } else {
                Log.d(TAG, "A2DP already connected");
            }
        } else if(CMD_STOP.equals(cmd) || "stop".equals(cmd)) {
            Log.d(TAG, "Stopping A2DP service");
            finish();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "A2DP service destroyed");

        // Clean up
        mHandler.removeCallbacksAndMessages(null);
        if (a2dpController != null) {
            a2dpController.disconnect();
            a2dpController.release();
            a2dpController = null;
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void finish() {
        stopSelf();
    }

    // Helper methods from A2dpBaseService (copied logic)
    private BluetoothDevice getBondDeviceByAddress(String address) {
        if (address == null || a2dpController == null) {
            return null;
        }
        Set<BluetoothDevice> list = a2dpController.getBondedDevices();
        if (list != null && list.size() > 0) {
            for (BluetoothDevice dev : list) {
                if (address.equals(dev.getAddress())) {
                    return dev;
                }
            }
        }
        return null;
    }

    private BluetoothDevice getDeviceByAddress(String deviceAddress) {
        return (a2dpController != null && deviceAddress != null)
                ? a2dpController.getDeviceByAddress(deviceAddress)
                : null;
    }

    private List<BluetoothDevice> getConnectedDevices() {
        return a2dpController != null ? a2dpController.getConnectedDevices() : null;
    }

    // BTServiceListener implementation
    @Override
    public void onBTConnected(BluetoothDevice device) {
        Log.i(TAG, "A2DP connected to: " + (device != null ? device.getAddress() : "null"));
        if (btStateCallback != null) btStateCallback.onBtStateChanged(true);
    }

    @Override
    public void onBTDisconnect() {
        Log.e(TAG, "A2DP disconnected");
        if (btStateCallback != null) btStateCallback.onBtStateChanged(false);
    }

    @Override
    public void onBTDeviceFound(BluetoothDevice device, short rssi) {
        // Not used in this implementation
    }

    @Override
    public void onBTReadData(BluetoothDevice device, byte[] data) {
        // Not used for A2DP
    }

    @Override
    public void onBTBond(BluetoothDevice device, int state) {
        Log.d(TAG, "+++ a2dp onBTBond state="+state);
        if(state == BluetoothDevice.BOND_BONDED)
        {
            if (a2dpController != null) {
                a2dpController.connect(device);
            }
        }
        else if(state == BluetoothDevice.BOND_NONE)
        {
            mDevice = null;
            mHandler.removeMessages(MSG_CHECK_CONNECT);
            mHandler.sendEmptyMessage(MSG_CHECK_CONNECT);
        }
    }

    @Override
    public void onDiscoveryStateChanged(boolean bScanFinish) {
        if(bScanFinish) {
            mHandler.removeMessages(MSG_CHECK_CONNECT);
            mHandler.sendEmptyMessage(MSG_CHECK_CONNECT);
        }
    }

    @Override
    public void onA2dpConnectResult(BluetoothDevice device, boolean bSucc) {
        Log.d(TAG, "a2dp connected bSucc="+bSucc);
        if(bSucc)
        {
            mDeviceAddr = device.getAddress();
        }
        else {
            mHandler.removeMessages(MSG_CHECK_CONNECT);
            mHandler.sendEmptyMessageDelayed(MSG_CHECK_CONNECT, 1000);
        }
    }
}
