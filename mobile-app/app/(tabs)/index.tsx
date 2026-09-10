import React, { useState, useEffect } from 'react';
import { StyleSheet, Text, View, Button, ScrollView, TouchableOpacity, PermissionsAndroid, Platform } from 'react-native';
import Glass, { GlassEventSubscriptions, DeviceFoundEvent, GlassConnectionState, GlassSystemInfo } from '@/modules/sdk/src/native/Glass';

export default function ConnectionScreen() {
  const [scanning, setScanning] = useState(false);
  const [devices, setDevices] = useState<DeviceFoundEvent[]>([]);
  const [connectionState, setConnectionState] = useState<GlassConnectionState>(GlassConnectionState.IDLE);
  const [connectedDevice, setConnectedDevice] = useState<{ id: string, name: string } | null>(null);
  const [systemInfo, setSystemInfo] = useState<GlassSystemInfo | null>(null);

  useEffect(() => {
    console.log("[ConnectionScreen] Component mounted, fetching initial connection state...");
    Glass.getCurrentState().then(state => {
      console.log("[ConnectionScreen] Initial state:", state);
      setConnectionState(state);
    });
    
    const subDeviceFound = GlassEventSubscriptions.onDeviceFound((event) => {
      console.log(`[ConnectionScreen] Device Found: ${event.deviceName} (${event.deviceId}) RSSI: ${event.rssi}`);
      setDevices(prev => {
        if (prev.find(d => d.deviceId === event.deviceId)) return prev;
        return [...prev, event];
      });
    });

    const subStateChanged = GlassEventSubscriptions.onStateChanged((event) => {
      console.log(`[ConnectionScreen] State Changed: ${event.oldState} -> ${event.newState}`);
      setConnectionState(event.newState);
      if (event.newState === GlassConnectionState.CONNECTED) {
        console.log("[ConnectionScreen] Connected! Waiting 2 seconds for GATT table to populate before fetching system info...");
        setTimeout(() => {
          fetchSystemInfo();
        }, 2000);
      } else if (event.newState === GlassConnectionState.IDLE || event.newState === GlassConnectionState.ERROR) {
        console.log("[ConnectionScreen] Disconnected or Error. Clearing state.");
        setConnectedDevice(null);
        setSystemInfo(null);
        setScanning(false);
      }
    });

    const subConnected = GlassEventSubscriptions.onConnected((event) => {
      console.log(`[ConnectionScreen] Connected event fired for device: ${event.deviceName} (${event.deviceId})`);
      setConnectedDevice({ id: event.deviceId, name: event.deviceName });
    });

    const subBattery = GlassEventSubscriptions.onBattery((event) => {
      console.log(`[ConnectionScreen] Battery Update: ${event.level}% (Charging: ${event.isCharging})`);
      setSystemInfo(prev => prev ? { ...prev, batteryLevel: event.level, isCharging: event.isCharging } : null);
    });

    return () => {
      console.log("[ConnectionScreen] Component unmounting, removing subscriptions.");
      subDeviceFound.remove();
      subStateChanged.remove();
      subConnected.remove();
      subBattery.remove();
    };
  }, []);

  const requestPermissions = async () => {
    if (Platform.OS === 'android') {
      console.log("[ConnectionScreen] Requesting Android Bluetooth permissions...");
      try {
        const granted = await PermissionsAndroid.requestMultiple([
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
          PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
          PermissionsAndroid.PERMISSIONS.ACCESS_FINE_LOCATION,
          PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES,
        ]);
        console.log("[ConnectionScreen] Permissions result:", granted);
        const allGranted = Object.values(granted).every(status => status === PermissionsAndroid.RESULTS.GRANTED);
        return allGranted;
      } catch (err) {
        console.warn("[ConnectionScreen] Permissions error:", err);
        return false;
      }
    }
    return true; // iOS permissions are usually handled natively via Info.plist automatically on first use
  };

  const fetchSystemInfo = async (retries = 3) => {
    try {
      console.log(`[ConnectionScreen] Calling Glass.getSystemInfo()... (Retries left: ${retries})`);
      const info = await Glass.getSystemInfo();
      console.log("[ConnectionScreen] System Info received:", info);
      
      if (info && info.firmwareVersion === "" && retries > 0) {
        console.log("[ConnectionScreen] System info is still blank, retrying in 2 seconds...");
        setTimeout(() => fetchSystemInfo(retries - 1), 2000);
      } else {
        setSystemInfo(info);
      }
    } catch (e) {
      console.error("[ConnectionScreen] Failed to get system info", e);
    }
  };

  const handleToggleScan = async () => {
    if (scanning) {
      console.log("[ConnectionScreen] Stopping scan...");
      await Glass.stopScan();
      setScanning(false);
    } else {
      console.log("[ConnectionScreen] Starting scan process...");
      const hasPermission = await requestPermissions();
      if (!hasPermission) {
        console.warn("[ConnectionScreen] Missing required Bluetooth permissions to scan.");
        return;
      }
      setDevices([]);
      console.log("[ConnectionScreen] Calling Glass.startScan()...");
      await Glass.startScan();
      setScanning(true);
    }
  };

  const handleConnect = async (device: DeviceFoundEvent) => {
    try {
      console.log(`[ConnectionScreen] Attempting to connect to ${device.deviceName} (${device.deviceId})...`);
      if (scanning) {
        console.log("[ConnectionScreen] Stopping scan before connecting...");
        await Glass.stopScan();
        setScanning(false);
      }
      console.log("[ConnectionScreen] Calling Glass.connect('cyan')...");
      await Glass.connect(device.deviceId, 'cyan', device.deviceName);
    } catch (e: any) {
      console.error("[ConnectionScreen] Connection failed", e);
    }
  };

  const handleDisconnect = async () => {
    try {
      console.log("[ConnectionScreen] Calling Glass.disconnect()...");
      await Glass.disconnect();
    } catch (e: any) {
      console.error("[ConnectionScreen] Disconnect failed", e);
    }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Status</Text>
        <Text style={styles.statusText}>Connection State: {connectionState}</Text>
        {connectedDevice && (
          <Text style={styles.statusText}>Connected To: {connectedDevice.name}</Text>
        )}
      </View>

      {systemInfo && (
        <View style={styles.section}>
          <Text style={styles.sectionTitle}>Device Info</Text>
          <Text style={styles.statusText}>Battery: {systemInfo.batteryLevel}% {systemInfo.isCharging ? '(Charging)' : ''}</Text>
          <Text style={styles.statusText}>Hardware: {systemInfo.deviceModel}</Text>
          <Text style={styles.statusText}>Firmware: {systemInfo.firmwareVersion}</Text>
        </View>
      )}

      {connectionState !== GlassConnectionState.CONNECTED && connectionState !== GlassConnectionState.CONNECTING && (
        <View style={styles.section}>
          <View style={styles.buttonContainer}>
            <Button 
              title={scanning ? "Stop Scanning" : "Start Scanning"} 
              onPress={handleToggleScan} 
              color={scanning ? "#d62828" : "#457b9d"} 
            />
          </View>
          
          {devices.length > 0 && (
            <View style={styles.deviceList}>
              <Text style={{fontWeight: 'bold', marginBottom: 10}}>Discovered Devices:</Text>
              {devices.map(d => (
                <TouchableOpacity key={d.deviceId} style={styles.deviceItem} onPress={() => handleConnect(d)}>
                  <Text style={styles.deviceName}>{d.deviceName || 'Unknown Device'}</Text>
                  <Text style={styles.deviceId}>{d.deviceId} (RSSI: {d.rssi})</Text>
                </TouchableOpacity>
              ))}
            </View>
          )}
        </View>
      )}

      {connectionState === GlassConnectionState.CONNECTED && (
        <View style={styles.section}>
          <Button title="Disconnect" onPress={handleDisconnect} color="#d62828" />
        </View>
      )}
    </ScrollView>
  );
}

const styles = StyleSheet.create({
  container: {
    flex: 1,
    backgroundColor: '#f8f9fa',
  },
  content: {
    padding: 20,
  },
  section: {
    backgroundColor: '#ffffff',
    padding: 15,
    borderRadius: 8,
    marginBottom: 20,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 3,
    elevation: 2,
  },
  sectionTitle: {
    fontSize: 18,
    fontWeight: 'bold',
    marginBottom: 10,
    color: '#1d3557',
  },
  statusText: {
    fontSize: 16,
    color: '#2b2d42',
    marginBottom: 5,
  },
  buttonContainer: {
    marginBottom: 10,
  },
  deviceList: {
    marginTop: 15,
  },
  deviceItem: {
    backgroundColor: '#edf2f4',
    padding: 12,
    borderRadius: 6,
    marginBottom: 8,
  },
  deviceName: {
    fontSize: 16,
    fontWeight: '600',
    color: '#1d3557',
  },
  deviceId: {
    fontSize: 12,
    color: '#8d99ae',
    marginTop: 4,
  }
});
