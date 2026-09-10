import React, { useState, useEffect, useRef } from 'react';
import { StyleSheet, Text, View, Button, ScrollView, TextInput } from 'react-native';
import Glass, { GlassEventSubscriptions } from '@/modules/sdk/src/native/Glass';
import { checkFirmwareUpdate, downloadAndInstallOta } from '@/utils/otaApi';
import * as DocumentPicker from 'expo-document-picker';

export default function OTATestingScreen() {
  const [otaProgress, setOtaProgress] = useState<number | null>(null);
  const [otaStatus, setOtaStatus] = useState<string>('Idle');
  const [otaPhase, setOtaPhase] = useState<'idle' | 'linux_flashing' | 'waiting_reboot' | 'ble_flashing'>('idle');
  // Mirror otaPhase into a ref so the one-time event listeners (registered with []
  // deps) read the current phase instead of the value captured at mount ('idle').
  const otaPhaseRef = useRef(otaPhase);
  useEffect(() => { otaPhaseRef.current = otaPhase; }, [otaPhase]);
  const [localIp, setLocalIp] = useState<string>('192.168.1.100'); // Default placeholder
  const [logs, setLogs] = useState<string[]>([]);
  
  // Track selected file for BLE DFU
  const [bleFirmwareUri, setBleFirmwareUri] = useState<string | null>(null);

  const addLog = (msg: string) => {
    const timestamp = new Date().toLocaleTimeString();
    setLogs((prev) => [`[${timestamp}] ${msg}`, ...prev]);
    console.log(`[OTA UI] ${msg}`);
  };

  useEffect(() => {
    addLog('OTA screen mounted. Listeners registered.');

    const subProgress = GlassEventSubscriptions.onOtaProgress((event) => {
      setOtaProgress(event.percent);
      // WiFi/Linux OTA progress is the combined download+flash percentage the glasses
      // report over BLE (0-29% = transfer to glasses, 30-100% = actual flash write).
      // BLE DFU progress is the AM01CY MCU flash write.
      const phase = otaPhaseRef.current;
      const label =
        phase === 'linux_flashing'
          ? `WiFi update in progress... ${event.percent}%`
          : phase === 'ble_flashing'
          ? `BLE update in progress... ${event.percent}%`
          : `Updating... ${event.percent}%`;
      setOtaStatus(label);
      addLog(`Progress Event: ${event.percent}%`);
    });
    
    const subCompleted = GlassEventSubscriptions.onOtaCompleted((event) => {
      setOtaProgress(100);
      setOtaStatus(event.success ? 'Success!' : 'Failed');
      setOtaPhase('idle');
      addLog(`Completion Event: ${event.success ? 'SUCCESS' : 'FAILED'}`);
    });
    
    const subLinuxCompleted = GlassEventSubscriptions.onOtaLinuxCompleted((event) => {
      setOtaProgress(100);
      setOtaStatus('Linux Flashed. Waiting for reboot...');
      setOtaPhase('waiting_reboot');
      addLog(`Linux OTA Completed. Glasses rebooting...`);
    });

    const subError = GlassEventSubscriptions.onOtaError((event) => {
      setOtaStatus('Error: ' + event.error);
      setOtaPhase('idle');
      addLog(`Error Event: ${event.error}`);
    });

    const subTrace = GlassEventSubscriptions.onError((event: any) => {
      addLog(`[NATIVE] ${event.error}`);
    });

    return () => {
      addLog('OTA screen unmounted. Listeners cleared.');
      subProgress.remove();
      subCompleted.remove();
      subLinuxCompleted.remove();
      subError.remove();
      subTrace.remove();
    };
  }, []);

  const handleConnectP2p = async () => {
    try {
      setOtaStatus('Connecting Wi-Fi P2P link...');
      addLog('Triggered: Connect P2P Link');
      addLog('Executing: Glass.syncFiles()');
      await Glass.syncFiles();
      setOtaStatus('P2P connection handshake initiated.');
      addLog('Observe the prompt on your phone/glasses to connect.');
    } catch (e: any) {
      setOtaStatus('Error starting P2P: ' + e.message);
      addLog(`P2P Error: ${e.message}`);
    }
  };

  const handleCloseWifi = async () => {
    try {
      setOtaStatus('Turning off glasses Wi-Fi...');
      addLog('Triggered: Turn Off Glasses Wi-Fi');
      addLog('Executing: Glass.sendCommand("exitTransferMode")');
      await Glass.sendCommand('exitTransferMode');
      setOtaStatus('Wi-Fi disable command sent.');
      addLog('Glasses Wi-Fi disable command acknowledged.');
    } catch (e: any) {
      setOtaStatus('Error closing Wi-Fi: ' + e.message);
      addLog(`Close Wi-Fi Error: ${e.message}`);
    }
  };

  const handleStartOtaMode = async () => {
    try {
      setOtaStatus('Turning on glasses OTA Wi-Fi AP...');
      addLog('Triggered: Start OTA Mode (AP CY01_...)');
      addLog('Executing: Glass.sendCommand("startOtaMode") (0x02, 0x01, 0x05)');
      await Glass.sendCommand('startOtaMode');
      setOtaStatus('OTA AP Mode enabled. Connect your laptop to CY01_... network with password 123456789.');
      addLog('Glasses should now broadcast CY01_<MAC> Wi-Fi.');
    } catch (e: any) {
      setOtaStatus('Error starting OTA mode: ' + e.message);
      addLog(`Start OTA Mode Error: ${e.message}`);
    }
  };

  const handleLocalWifiOta = async () => {
    try {
      setOtaProgress(0);
      const url = `http://${localIp.trim()}:8080/firmware_patched.swu`;
      setOtaStatus(`Sending WiFi OTA request...`);
      addLog(`Triggered: Local Patched WiFi OTA`);
      addLog(`Target URL: ${url}`);
      addLog('Executing: Glass.startWifiOtaUpdate(url)');
      
      await Glass.startWifiOtaUpdate(url);
      setOtaStatus('Local WiFi OTA request acknowledged.');
      addLog('WiFi OTA writeIpToSoc command successfully sent to SoC.');
      addLog('Watch the server logs on your laptop for connection/download progress.');
    } catch (e: any) {
      setOtaStatus('Local WiFi OTA Error: ' + e.message);
      addLog(`WiFi OTA Error: ${e.message}`);
    }
  };

  const handlePickAndFlash = async () => {
    try {
      addLog('Opening file picker...');
      const result = await DocumentPicker.getDocumentAsync({
        type: '*/*',
        copyToCacheDirectory: true
      });

      if (result.canceled) {
        addLog('File selection canceled.');
        return;
      }

      const file = result.assets[0];
      addLog(`Selected file: ${file.name}`);
      
      let filePath = file.uri;
      // In Android, DocumentPicker can return content:// URIs. However, we copied it to cache.
      // Cache directory files usually have file:// URI. If not, the native module will handle it.
      
      setOtaStatus('Triggering local OTA sequence...');
      setOtaPhase('linux_flashing');
      setOtaProgress(0);
      addLog(`Initiating local HTTP Server OTA for: ${filePath}`);
      addLog('Executing: Glass.startWifiOtaUpdate(fileUri)');
      await Glass.startWifiOtaUpdate(filePath);
      
      setOtaStatus('OTA Request sent. Glasses should connect to phone automatically.');
      addLog('Wait for glasses to connect. The phone will automatically spin up the server and serve the file!');
    } catch (e: any) {
      setOtaStatus('Error picking file: ' + e.message);
      setOtaPhase('idle');
      addLog(`File Picker Error: ${e.message}`);
    }
  };
  
  const handlePickAndFlashBle = async () => {
    try {
      addLog('Opening file picker for BLE DFU...');
      const result = await DocumentPicker.getDocumentAsync({
        type: '*/*',
        copyToCacheDirectory: true
      });

      if (result.canceled) {
        return;
      }
      
      const file = result.assets[0];
      setBleFirmwareUri(file.uri);
      
      setOtaStatus('Triggering BLE DFU...');
      setOtaPhase('ble_flashing');
      setOtaProgress(0);
      // DfuHandle.checkFile() does `new File(path).exists()`, so it needs a raw
      // filesystem path — NOT the file:// URI the picker returns. Strip the scheme.
      const filePath = decodeURIComponent(file.uri.replace(/^file:\/\//, ''));
      addLog(`Initiating BLE DFU for: ${filePath}`);

      await Glass.startOtaUpdate(filePath);
    } catch (e: any) {
      setOtaStatus('Error picking BLE file: ' + e.message);
      setOtaPhase('idle');
      addLog(`File Picker Error: ${e.message}`);
    }
  };

  const handleCheckAndDownloadOta = async () => {
    try {
      setOtaProgress(0);
      setOtaStatus('Checking for update...');
      addLog('Triggered: Fetch & Install Real OTA');
      const hwVersion = "v1.0"; 
      const romVersion = "1.0.0"; 

      addLog(`Checking firmware updates with HW=${hwVersion}, ROM=${romVersion}`);
      const otaInfo = await checkFirmwareUpdate(hwVersion, romVersion);
      
      if (!otaInfo) {
        setOtaStatus('No updates found or error checking.');
        addLog('No updates returned from server or network failure.');
        return;
      }
      
      setOtaStatus(`Found v${otaInfo.version}. Downloading...`);
      addLog(`Found ROM Version: ${otaInfo.version}`);
      addLog(`Server Download URL: ${otaInfo.downloadUrl}`);
      
      addLog('Executing download and install sequence...');
      await downloadAndInstallOta(otaInfo);
      
      setOtaStatus('Transferring to glasses...');
      addLog('OTA transfer handshake triggered. Observe device reboot.');
    } catch (e: any) {
      setOtaStatus('Error checking/downloading: ' + e.message);
      addLog(`Real OTA Error: ${e.message}`);
    }
  };

  const handleSetWakeWord = async (enabled: boolean) => {
    try {
      setOtaStatus(`${enabled ? 'Enabling' : 'Disabling'} wake word...`);
      addLog(`Triggered: ${enabled ? 'Enable' : 'Disable'} wake word (aiVoiceWake 0x44)`);
      await Glass.setWakeWord(enabled);
      addLog(`Command sent. Watch for [NATIVE] [WAKE] ACK below, then say "Hey Cyan" to test. Reboot + retest to confirm it persists.`);
    } catch (e: any) {
      setOtaStatus('Wake word error: ' + e.message);
      addLog(`Wake Word Error: ${e.message}`);
    }
  };

  const handleQueryWakeWord = async () => {
    try {
      addLog('Triggered: Query wake-word status (aiVoiceWake get)');
      await Glass.getWakeWord();
      addLog('Query sent. Watch for [NATIVE] [WAKE] status below.');
    } catch (e: any) {
      addLog(`Wake Word Query Error: ${e.message}`);
    }
  };

  return (
    <ScrollView style={styles.container} contentContainerStyle={styles.content}>
      <View style={styles.section}>
        <Text style={styles.sectionTitle}>OTA Updates</Text>
        <View style={styles.statusBox}>
          <Text style={styles.statusText}>State: {otaStatus}</Text>
          {otaProgress !== null && (
            <Text style={styles.statusText}>Progress: {otaProgress}%</Text>
          )}
        </View>

        <View style={styles.localOtaBox}>
          <Text style={styles.inputLabel}>Phase 1: Local Linux Server OTA (.swu)</Text>
          <Text style={{fontSize: 12, marginBottom: 10, color: '#457b9d'}}>
            Tap below to select the stock or patched .swu file from your phone's storage. 
            The app will automatically start a local server and stream it to the glasses via P2P.
          </Text>
          <Button title="Pick Linux Firmware & Flash" onPress={handlePickAndFlash} color="#2a9d8f" disabled={otaPhase === 'linux_flashing' || otaPhase === 'ble_flashing'} />
        </View>
        
        <View style={[styles.localOtaBox, otaPhase === 'waiting_reboot' ? { borderColor: '#e76f51', borderWidth: 2 } : null]}>
          <Text style={[styles.inputLabel, { color: '#e76f51' }]}>Direct BLE DFU (.bin) — AM01CY MCU</Text>
          <Text style={{fontSize: 12, marginBottom: 10, color: '#457b9d'}}>
            {otaPhase === 'waiting_reboot'
              ? 'Linux OS flashed and the glasses are rebooting. Once they reconnect to BLE, tap below.'
              : 'Flash the AM01CY BLE MCU firmware (.bin) directly over Bluetooth. Pick the stock or patched .bin from your phone (e.g. test_ota.bin).'}
          </Text>
          <Button title="Pick BLE Firmware & Flash" onPress={handlePickAndFlashBle} color="#e76f51" disabled={otaPhase === 'linux_flashing' || otaPhase === 'ble_flashing'} />
        </View>

        <View style={styles.buttonContainer}>
          <Button title="Legacy: Connect P2P Link (WiFi)" onPress={handleConnectP2p} color="#457b9d" />
        </View>
        <View style={styles.buttonContainer}>
          <Button title="Legacy: Turn Off Glasses Wi-Fi" onPress={handleCloseWifi} color="#6c757d" />
        </View>
        <View style={styles.buttonContainer}>
          <Button title="Fetch & Install Real OTA" onPress={handleCheckAndDownloadOta} color="#1d3557" />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Wake Word Control (BLE — no flashing)</Text>
        <Text style={{ fontSize: 12, marginBottom: 10, color: '#457b9d' }}>
          Sends aiVoiceWake (BLE opcode 0x44) to turn the on-device "Hey Cyan" detector
          on/off. After disabling, say "Hey Cyan" — it should not respond. Reboot and retest
          to confirm the setting persists. Watch for the [NATIVE] [WAKE] ACK in the logs.
        </Text>
        <View style={styles.buttonContainer}>
          <Button title="Disable Wake Word" onPress={() => handleSetWakeWord(false)} color="#e63946" />
        </View>
        <View style={styles.buttonContainer}>
          <Button title="Enable Wake Word" onPress={() => handleSetWakeWord(true)} color="#2a9d8f" />
        </View>
        <View style={styles.buttonContainer}>
          <Button title="Query Wake Word Status" onPress={handleQueryWakeWord} color="#457b9d" />
        </View>
      </View>

      <View style={styles.section}>
        <Text style={styles.sectionTitle}>Real-time logs</Text>
        <ScrollView style={styles.logContainer} nestedScrollEnabled={true}>
          {logs.length === 0 ? (
            <Text style={styles.noLogText}>No logs yet. Trigger an action above.</Text>
          ) : (
            logs.map((log, index) => (
              <Text key={index} style={styles.logText}>{log}</Text>
            ))
          )}
        </ScrollView>
        {logs.length > 0 && (
          <Button title="Clear Logs" onPress={() => setLogs([])} color="#6c757d" />
        )}
      </View>
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
  statusBox: {
    backgroundColor: '#edf2f4',
    padding: 10,
    borderRadius: 5,
    marginBottom: 15,
  },
  statusText: {
    fontSize: 16,
    color: '#2b2d42',
    marginBottom: 5,
  },
  buttonContainer: {
    marginBottom: 10,
  },
  localOtaBox: {
    backgroundColor: '#f1faee',
    padding: 12,
    borderRadius: 8,
    borderWidth: 1,
    borderColor: '#a8dadc',
    marginBottom: 20,
  },
  inputLabel: {
    fontSize: 14,
    fontWeight: '600',
    color: '#1d3557',
    marginBottom: 5,
  },
  input: {
    backgroundColor: '#ffffff',
    borderWidth: 1,
    borderColor: '#ccc',
    borderRadius: 4,
    padding: 8,
    fontSize: 15,
    color: '#333',
    marginBottom: 10,
  },
  logContainer: {
    maxHeight: 250,
    backgroundColor: '#1e1e1e',
    borderRadius: 6,
    padding: 10,
    marginBottom: 15,
  },
  noLogText: {
    color: '#888888',
    fontSize: 14,
    fontStyle: 'italic',
    textAlign: 'center',
    paddingVertical: 20,
  },
  logText: {
    fontFamily: 'monospace',
    fontSize: 12,
    color: '#00ff00',
    marginBottom: 4,
  },
});
