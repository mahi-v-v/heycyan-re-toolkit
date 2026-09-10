package expo.modules.glasssdk.glass.vendors.k900;

import static android.provider.Settings.ACTION_WIFI_ADD_NETWORKS;
import static android.provider.Settings.EXTRA_WIFI_NETWORK_LIST;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.companion.CompanionDeviceManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.DhcpInfo;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSpecifier;
import android.net.wifi.WifiNetworkSuggestion;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.os.Parcelable;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;

public class WifiConnector {
    public static final int COMPANION_DEVICE_REQUEST_CODE = 1001;
    private static final String TAG = "WifiConnector";
    private static final int STATE_FAIL = 0;
    private static final int STATE_CANCEL = 1;
    private static final int STATE_OK = 2;
    private static final int TIMEOUT = 20000;
    private static final int MSG_CONNECT_RESULT = 2201;
    private static final int MSG_CONNECT_DELAY_RESULT = 2202;
    private static final int MSG_CONNECT_DELAY_RECONNECT = 2203;
    private static WifiConnector st;
    private Context mContext;
    private String mSSid, mPwd;
    private WifiManager wifiManager;
    private WifiConnectThread mConnector;
    private ConnectivityManager mConnectivityManager;
    private CompanionDeviceManager mCompanionDeviceManager;
    private boolean mbRegister = false;
    private Activity mActivity; // Store Activity reference for pairing
    private boolean mPairingInProgress = false;
    private WifiListener mListener;
    private WifiNetworkSuggestion mSuggestion = null;
    private WifiNetworkSuggestion mSuggestion2 = null;
    private WifiNetworkSuggestion mSuggestion3 = null;
    private Network mBoundNetwork = null;
    private Handler mHandler = new Handler(android.os.Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            if (msg.what == MSG_CONNECT_RESULT) {
                Log.i(TAG, "+++++++++ wifi connect result =" + msg.arg1);
                if (msg.arg1 == STATE_FAIL || msg.arg1 == STATE_CANCEL) {
                    unregisterNetwork();
                    if (mListener != null) {
                        mListener.onWifiConnectResult(0, false);
                    }
                } else {
                    mHandler.removeMessages(MSG_CONNECT_DELAY_RECONNECT);
                    mHandler.removeMessages(MSG_CONNECT_DELAY_RESULT);
                    mHandler.sendMessageDelayed(Message.obtain(mHandler, MSG_CONNECT_DELAY_RESULT, STATE_OK, 0), 200);
                }
            } else if (msg.what == MSG_CONNECT_DELAY_RESULT) {
                if (mListener != null) {
                    mListener.onWifiConnectResult(0, msg.arg1 == STATE_OK);
                }
            } else if (msg.what == MSG_CONNECT_DELAY_RECONNECT) {
                Log.e(TAG, "timeout unregisterNetwork.....");
                unregisterNetwork();
                if (mListener != null) {
                    mListener.onWifiConnectTimeout();
                }
            }

        }
    };
    private LinkProperties mLinkProperties = null;
    private BroadcastReceiver broadcastReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (action != null) {
                if (action.equals(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)) {
                    Log.e(TAG, "-- boradcast post connect");
                }
                if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                    NetworkInfo info = intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (info != null)
                        Log.e(TAG, "-- wifi state =" + info.isAvailable());
                    else
                        Log.e(TAG, "-- wifi state null");
                }
            }
        }
    };

    private WifiConnector() {
    }

    public static WifiConnector getInstance() {
        if (st == null) {
            synchronized (WifiConnector.class) {
                if (st == null) {
                    st = new WifiConnector();
                }
            }
        }
        return st;
    }

    public static boolean isWifiAdded(WifiManager wm, String ssid) {
        if (ssid == null)
            return false;
        String __wifiName__ = "\"" + ssid + "\"";
        @SuppressLint("MissingPermission")
        List wifiList = wm.getConfiguredNetworks();
        if (wifiList != null) {
            for (int i = 0; i < wifiList.size(); ++i) {
                WifiConfiguration wifiInfo0 = (WifiConfiguration) wifiList.get(i);
                if (__wifiName__.equals(wifiInfo0.SSID) || ssid.equals(wifiInfo0.SSID)) {
                    return true;
                }
            }
        }
        return false;
    }

    public static WifiConfiguration createWifiInfo(String ssid, String password) {
        WifiConfiguration config = new WifiConfiguration();
        config.allowedAuthAlgorithms.clear();
        config.allowedGroupCiphers.clear();
        config.allowedKeyManagement.clear();
        config.allowedPairwiseCiphers.clear();
        config.allowedProtocols.clear();
        config.SSID = "\"" + ssid + "\"";
        config.preSharedKey = "\"" + password + "\"";
        config.allowedAuthAlgorithms.set(WifiConfiguration.AuthAlgorithm.OPEN);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.TKIP);
        config.allowedGroupCiphers.set(WifiConfiguration.GroupCipher.CCMP);
        config.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.WPA_PSK);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.TKIP);
        config.allowedPairwiseCiphers.set(WifiConfiguration.PairwiseCipher.CCMP);
        config.allowedProtocols.set(WifiConfiguration.Protocol.RSN);
        config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        config.status = WifiConfiguration.Status.ENABLED;
        return config;
    }

    private static String intToIp(int i) {
        return (i & 0xFF) + "." + ((i >> 8) & 0xFF) + "." + ((i >> 16) & 0xFF) + "."
                + ((i >> 24) & 0xFF);
    }

    public void init(Context context, WifiListener listener) {
        mContext = context;
        mListener = listener;
        // Try to get Activity reference from context
        if (context instanceof Activity) {
            mActivity = (Activity) context;
        }
        wifiManager = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        mConnectivityManager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mCompanionDeviceManager = (CompanionDeviceManager) context.getSystemService(Context.COMPANION_DEVICE_SERVICE);
        }
        registerNetBroadcast();
    }

    public void setActivity(Activity activity) {
        mActivity = activity;
    }

    public String getCurrentWifiSsid() {
        if (wifiManager == null) {
            return null;
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        if (wifiInfo != null) {
            String ssid = wifiInfo.getSSID();
            if (ssid != null)
                ssid = ssid.replaceAll("\"", "");
            return ssid;
        }
        return null;
    }

    public String getServerIP() {
        // For Android Q+ with bound network, get gateway from LinkProperties
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mBoundNetwork != null) {
            try {
                // Use cached LinkProperties first, fall back to querying ConnectivityManager
                LinkProperties linkProperties = mLinkProperties;
                if (linkProperties == null) {
                    linkProperties = mConnectivityManager.getLinkProperties(mBoundNetwork);
                }

                if (linkProperties != null) {
                    // Log all available IP information
                    Log.i(TAG, "=== Network Info Debug ===");

                    // Log DNS servers
                    if (linkProperties.getDnsServers() != null) {
                        for (java.net.InetAddress dns : linkProperties.getDnsServers()) {
                            if (dns instanceof java.net.Inet4Address) {
                                Log.i(TAG, "DNS server (IPv4): " + dns.getHostAddress());
                            }
                        }
                    }

                    // Log link addresses (device IPs)
                    if (linkProperties.getLinkAddresses() != null) {
                        for (android.net.LinkAddress linkAddr : linkProperties.getLinkAddresses()) {
                            java.net.InetAddress addr = linkAddr.getAddress();
                            if (addr instanceof java.net.Inet4Address) {
                                Log.i(TAG, "Link address (device IP): " + addr.getHostAddress());
                            }
                        }
                    }

                    // Log all routes and gateways
                    if (linkProperties.getRoutes() != null) {
                        for (android.net.RouteInfo route : linkProperties.getRoutes()) {
                            if (route.getGateway() != null) {
                                java.net.InetAddress gateway = route.getGateway();
                                Log.i(TAG, "Route gateway: " + gateway.getHostAddress() + " (IPv" +
                                        (gateway instanceof java.net.Inet4Address ? "4" : "6") + ")");
                            }
                        }
                    }

                    Log.i(TAG, "=== End Network Info ===");

                    // First try: Use DNS server (often points to the glasses in hotspot mode)
                    if (linkProperties.getDnsServers() != null && !linkProperties.getDnsServers().isEmpty()) {
                        for (java.net.InetAddress dns : linkProperties.getDnsServers()) {
                            if (dns instanceof java.net.Inet4Address) {
                                String dnsIP = dns.getHostAddress();
                                if (!dnsIP.equals("0.0.0.0")) {
                                    Log.i(TAG, "Using DNS server as server IP: " + dnsIP);
                                    return dnsIP;
                                }
                            }
                        }
                    }

                    // Second try: Use gateway
                    if (linkProperties.getRoutes() != null) {
                        for (android.net.RouteInfo route : linkProperties.getRoutes()) {
                            if (route.getGateway() != null) {
                                java.net.InetAddress gateway = route.getGateway();
                                if (gateway instanceof java.net.Inet4Address) {
                                    String gatewayIP = gateway.getHostAddress();
                                    if (!gatewayIP.equals("0.0.0.0")) {
                                        Log.i(TAG, "Using gateway as server IP: " + gatewayIP);
                                        return gatewayIP;
                                    }
                                }
                            }
                        }
                    }
                }
                Log.w(TAG, "No valid IPv4 server IP found in LinkProperties");
            } catch (Exception e) {
                Log.e(TAG, "Error getting server IP from bound network", e);
            }
        }

        // Fallback to legacy method for older Android versions
        if (wifiManager == null) {
            return null;
        }
        if (!wifiManager.isWifiEnabled()) {
            wifiManager.setWifiEnabled(true);
        }
        WifiInfo wifiInfo = wifiManager.getConnectionInfo();
        String IPAddress = intToIp(wifiInfo.getIpAddress());
        Log.i(TAG, "Device IP (legacy): " + IPAddress);

        DhcpInfo dhcpinfo = wifiManager.getDhcpInfo();
        String gatewayIP = intToIp(dhcpinfo.gateway);
        String serverAddress = intToIp(dhcpinfo.serverAddress);
        String dns1 = intToIp(dhcpinfo.dns1);
        String dns2 = intToIp(dhcpinfo.dns2);

        Log.i(TAG, "Gateway (legacy): " + gatewayIP);
        Log.i(TAG, "DHCP Server (legacy): " + serverAddress);
        Log.i(TAG, "DNS1 (legacy): " + dns1);
        Log.i(TAG, "DNS2 (legacy): " + dns2);

        return serverAddress;
    }

    public boolean connect(String ssid, String pwd) {
        mSSid = ssid;
        mPwd = pwd;
        if (mConnector != null) {
            mConnector.interrupt();
            mConnector = null;
        }
        mConnector = new WifiConnectThread();
        mConnector.start();
        return true;
    }

    private void reconnect() {
        if (mConnector != null) {
            mConnector.interrupt();
            mConnector = null;
        }
        mConnector = new WifiConnectThread();
        mConnector.start();
    }

    public void disconnect() {
        if (mConnector != null) {
            mConnector.interrupt();
            mConnector = null;
        }
    }

    private boolean addWifi(String wifiName, String wifiPwd) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.Q) {
            WifiConfiguration wifiNewConfiguration = createWifiInfo(wifiName, wifiPwd);
            int newNetworkId = wifiManager.addNetwork(wifiNewConfiguration);
            Log.e(TAG, "addWifi newNetworkId=" + newNetworkId);
            if (newNetworkId == -1) {
                Log.e(TAG, "addWifi fail");
                return false;
            }
        }
        return true;
    }

    private boolean connect(WifiManager wm, String ssid, String pwd) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            String __wifiName__ = "\"" + mSSid + "\"";
            @SuppressLint("MissingPermission")
            List<WifiConfiguration> list = wm.getConfiguredNetworks();
            boolean bConnect = false;
            for (WifiConfiguration i : list) {
                if (i.SSID != null && i.SSID.equals(__wifiName__)) {
                    wm.disconnect();
                    wm.enableNetwork(i.networkId, true);
                    wm.reconnect();
                    bConnect = true;
                    break;
                }
            }
            Log.e(TAG, " connect bConnect=" + bConnect);
            if (!bConnect) {
                Message msg = new Message();
                msg.what = MSG_CONNECT_RESULT;
                msg.arg1 = STATE_FAIL;
                mHandler.sendMessage(msg);
            }
            return bConnect;
        } else {
            return new_addNetWork(ssid, pwd);
        }
    }

    public boolean addNetworkSuggestions(String ssid, String pwd) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Always recreate suggestions for the new SSID
            mSuggestion = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setIsAppInteractionRequired(false) // Changed to false for persistent connection
                    .build();

            mSuggestion2 = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pwd)
                    .setIsAppInteractionRequired(false) // Changed to false for persistent connection
                    .build();

            mSuggestion3 = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa3Passphrase(pwd)
                    .setIsAppInteractionRequired(false) // Changed to false for persistent connection
                    .build();

            List<WifiNetworkSuggestion> suggestionsList = new ArrayList<>();
            suggestionsList.add(mSuggestion);
            suggestionsList.add(mSuggestion2);
            suggestionsList.add(mSuggestion3);
            int status = wifiManager.addNetworkSuggestions(suggestionsList);

            if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
                Log.i(TAG, "Network suggestions added successfully for " + ssid);
                return true;
            } else if (status == WifiManager.STATUS_NETWORK_SUGGESTIONS_ERROR_ADD_DUPLICATE) {
                Log.i(TAG, "Network suggestions already exist for " + ssid);
                return true;
            } else {
                Log.e(TAG, "Failed to add network suggestions, status=" + status);
                return false;
            }
        }
        return false;
    }

    public boolean removeNetworkSuggestions() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            if (mSuggestion2 != null) {
                List<WifiNetworkSuggestion> suggestionsList = new ArrayList<>();
                suggestionsList.add(mSuggestion);
                suggestionsList.add(mSuggestion2);
                suggestionsList.add(mSuggestion3);
                wifiManager.removeNetworkSuggestions(suggestionsList);
                mSuggestion = null;
                mSuggestion2 = null;
                mSuggestion3 = null;
            }
        }
        return false;
    }

    private boolean isNetworkInSuggestions(String ssid) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // Check if we already have suggestions for this SSID
            if (mSuggestion != null || mSuggestion2 != null || mSuggestion3 != null) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if device is already associated as a companion device
     */
    private boolean isDeviceAssociated(String ssid) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mCompanionDeviceManager != null) {
            List<String> associations = mCompanionDeviceManager.getAssociations();
            for (String association : associations) {
                Log.i(TAG, "Found association: " + association);
                // Check if this association matches our SSID
                if (association.contains(ssid)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Handle the result from companion device pairing
     * Call this from your Activity's onActivityResult
     */
    public void handleCompanionDevicePairingResult(int resultCode, Intent data) {
        mPairingInProgress = false;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && resultCode == Activity.RESULT_OK) {
            String deviceAddress = data.getStringExtra(CompanionDeviceManager.EXTRA_DEVICE);
            Log.i(TAG, "Companion device paired: " + deviceAddress);
            // Now proceed with WiFi connection without popup
            proceedWithConnection(mSSid);
        } else {
            Log.w(TAG, "Companion device pairing cancelled or failed");
            // User cancelled pairing, proceed anyway (will show WiFi popup)
            proceedWithConnection(mSSid);
        }
    }

    /**
     * Request companion device pairing (one-time setup)
     * This will show a system dialog for user to select the device
     * After pairing, subsequent WiFi connections won't show popup
     */
//    public void requestCompanionDevicePairing(Activity activity, String ssid) {
//        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && mCompanionDeviceManager != null) {
//            // Create a filter for WiFi devices matching the SSID pattern
//            WifiDeviceFilter deviceFilter = new WifiDeviceFilter.Builder()
//                    .setNamePattern(Pattern.compile(Pattern.quote(ssid)))
//                    .build();
//
//            AssociationRequest pairingRequest = new AssociationRequest.Builder()
//                    .addDeviceFilter(deviceFilter)
//                    .setSingleDevice(false) // Allow multiple devices with same pattern
//                    .build();
//
//            CompanionDeviceManager.Callback callback = new CompanionDeviceManager.Callback() {
//                @Override
//                public void onDeviceFound(IntentSender chooserLauncher) {
//                    Log.i(TAG, "Companion device found, launching chooser");
//                    mPairingInProgress = true;
//                    if (mListener != null) {
//                        mListener.onCompanionDevicePairingRequired(chooserLauncher);
//                    }
//                    try {
//                        activity.startIntentSenderForResult(chooserLauncher,
//                                COMPANION_DEVICE_REQUEST_CODE, null, 0, 0, 0);
//                    } catch (IntentSender.SendIntentException e) {
//                        Log.e(TAG, "Failed to launch companion device chooser", e);
//                        mPairingInProgress = false;
//                    }
//                }
//
//                @Override
//                public void onFailure(CharSequence error) {
//                    Log.e(TAG, "Companion device pairing failed: " + error);
//                    mPairingInProgress = false;
//                    // Proceed with connection anyway (will show WiFi popup)
//                    proceedWithConnection(ssid);
//                }
//            };
//
//            mCompanionDeviceManager.associate(pairingRequest, callback, null);
//        } else {
//            Log.w(TAG, "CompanionDeviceManager not available on this Android version");
//        }
//    }
    public boolean new_addNetWork(String name, String pwd) {
        mSSid = name;
        mPwd = pwd;

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            // First, add network suggestions to make the WiFi persist
            addNetworkSuggestions(name, pwd);

            // Check if device is already associated (paired as companion device)
            boolean isAssociated = isDeviceAssociated(name);
            if (isAssociated) {
                Log.i(TAG, "+++++++++ Device already associated, connecting without popup");
                proceedWithConnection(name);
            } else {
                Log.i(TAG, "+++++++++ Device not associated");
                // For now, just connect directly without pairing
                // CompanionDeviceManager pairing with WiFi is tricky and may not work reliably
                // The WiFi popup will appear, but after first approval, network suggestions will persist
                Log.i(TAG, "Proceeding with direct WiFi connection (network suggestions added for persistence)");
                proceedWithConnection(name);
            }
        }
        return true;
    }

    /**
     * Proceed with the actual WiFi connection
     * This is called after pairing is complete or if pairing is not needed
     */
    private void proceedWithConnection(String name) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q && mPwd != null) {
            Log.i(TAG, "+++++++++ proceedWithConnection called for " + name + ", mbRegister=" + mbRegister);

            // Always unregister first to clean up any previous state
            if (mbRegister) {
                Log.i(TAG, "Cleaning up previous network request before making new one");
                unregisterNetwork();
            }

            // Use WifiNetworkSpecifier
            // Note: This will show a popup on Android 10+ unless device is paired as companion
            WifiNetworkSpecifier specifier =
                    new WifiNetworkSpecifier.Builder()
                            .setSsid(name)
                            .setWpa2Passphrase(mPwd)
                            .build();

            NetworkRequest request =
                    new NetworkRequest.Builder()
                            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
                            .addCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
                            .setNetworkSpecifier(specifier)
                            .build();

            mbRegister = true;
            Log.i(TAG, "Requesting network connection for SSID: " + name);
            mConnectivityManager.requestNetwork(request, networkCallback, mHandler, TIMEOUT);
        } else {
            Log.e(TAG, "Cannot proceed with connection - invalid state: SDK=" + Build.VERSION.SDK_INT + ", pwd=" + (mPwd != null));
        }
    }

    public void unregisterNetwork() {
        if (mbRegister) {
            Log.i(TAG, "---------unregisterNetwork");
            // Don't remove network suggestions - they should persist
            // removeNetworkSuggestions();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mConnectivityManager.bindProcessToNetwork(null);
            }
            try {
                mConnectivityManager.unregisterNetworkCallback(networkCallback);
            } catch (Exception e) {
                Log.w(TAG, "Error unregistering network callback: " + e.getMessage());
            }
            // Don't call timeout callback here - this is just cleanup
        }
        mBoundNetwork = null;
        mLinkProperties = null;
        mbRegister = false;
    }

    private void registerNetBroadcast() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
        intentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mContext.registerReceiver(broadcastReceiver, intentFilter);
    }

    private void unregisterNetBroadcast() {
        mContext.unregisterReceiver(broadcastReceiver);
    }

    public void startWifiSaveActivity(Activity act, String ssid, String pwd, int requestCode) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            WifiNetworkSuggestion ss = new WifiNetworkSuggestion.Builder()
                    .setSsid(ssid)
                    .setWpa2Passphrase(pwd)
                    .build();
            List<WifiNetworkSuggestion> suggestionsList = new ArrayList<>();
            suggestionsList.add(ss);
            Bundle bundle = new Bundle();
            bundle.putParcelableArrayList(EXTRA_WIFI_NETWORK_LIST, (ArrayList<? extends Parcelable>) suggestionsList);
            Intent intent = new Intent(ACTION_WIFI_ADD_NETWORKS);
            intent.putExtras(bundle);
            act.startActivityForResult(intent, requestCode);
        }
    }

    public interface WifiListener {
        public void onWifiConnectResult(long handle, boolean bSucc);

        public void onWifiConnectTimeout();

//        public void onCompanionDevicePairingRequired(IntentSender intentSender);
    }

    ConnectivityManager.NetworkCallback networkCallback = new ConnectivityManager.NetworkCallback() {
        @Override
        public void onAvailable(Network network) {
            super.onAvailable(network);
            Log.i(TAG, "+++++++++ connectWifi success");
            Message msg = new Message();
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                mBoundNetwork = network;
                mConnectivityManager.bindProcessToNetwork(network);
                msg.obj = network.getNetworkHandle();
                Log.i(TAG, "+++++++++ connectWifi success, network handle=" + network.getNetworkHandle());
            }
            msg.what = MSG_CONNECT_RESULT;
            msg.arg1 = STATE_OK;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onUnavailable() {
            super.onUnavailable();
            Log.i(TAG, "+++++++++ connectWifi fail");
            Message msg = new Message();
            msg.what = MSG_CONNECT_RESULT;
            msg.arg1 = STATE_FAIL;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onLost(Network network) {
            super.onLost(network);
            Log.i(TAG, "+++++++++ onLost network");
            Message msg = new Message();
            msg.what = MSG_CONNECT_RESULT;
            msg.arg1 = STATE_CANCEL;
            mHandler.sendMessage(msg);
        }

        @Override
        public void onLinkPropertiesChanged(@NonNull Network network, @NonNull LinkProperties linkProperties) {
            super.onLinkPropertiesChanged(network, linkProperties);
            mLinkProperties = linkProperties;
            Log.i(TAG, "+++++++++ onLinkPropertiesChanged");
        }

        @Override
        public void onCapabilitiesChanged(@NonNull Network network, @NonNull NetworkCapabilities networkCapabilities) {
            super.onCapabilitiesChanged(network, networkCapabilities);
            Log.i(TAG, "+++++++++ onCapabilitiesChanged");
        }

        @Override
        public void onBlockedStatusChanged(@NonNull Network network, boolean blocked) {
            super.onBlockedStatusChanged(network, blocked);
            Log.i(TAG, "+++++++++ onBlockedStatusChanged");
        }
    };

    private class WifiConnectThread extends Thread {
        @Override
        public void run() {
            Log.e(TAG, "++++++++++++ WifiConnectThread start");
            if (!isWifiAdded(wifiManager, mSSid)) {
                addWifi(mSSid, mPwd);
            }
            mHandler.removeMessages(MSG_CONNECT_DELAY_RECONNECT);
            mHandler.sendEmptyMessageDelayed(MSG_CONNECT_DELAY_RECONNECT, TIMEOUT + 5000);
            connect(wifiManager, mSSid, mPwd);
            Log.e(TAG, "++++++++++++ WifiConnectThread finish");
        }
    }


}