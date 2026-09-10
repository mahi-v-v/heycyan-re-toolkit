import * as FileSystem from 'expo-file-system/legacy';
import Glass from '../modules/sdk/src/native/Glass';

export interface FirmwareOtaResp {
  openOrNot: number;
  hardwareVersion: string;
  version: string;
  enforceUpdateFrom: string;
  enforceUpdateTo: string;
  isEnforceUpdate: string;
  downloadUrl: string;
  uploadDate: string;
  os: number;
  updateDesc: string;
}

export interface QcResponse<T> {
  code: number;
  msg: string;
  data: T;
}

export async function checkFirmwareUpdate(hardwareVersion: string, romVersion: string): Promise<FirmwareOtaResp | null> {
  try {
    console.log(`Checking for OTA update. HW: ${hardwareVersion}, ROM: ${romVersion}`);
    const response = await fetch('https://www.qlifesnap.com/glasses/app-update/last-ota', {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'token': 'eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJhdWQiOiIxNzgzMDc4MTIyMTk0NDk2OTYiLCJleHAiOjE3ODYxODYyODd9.5Ou5VpOCna1qgLq0peDF7PgQ3gWfFFHGjlznwn_D31Y',
      },
      body: JSON.stringify({
        appId: 1, // Cyan app uses 1
        uid: 1,
        hardwareVersion: "AM01CY_V2.0", // Hardcoded to match the server's expected HW version
        romVersion: "AM01CY_2.00.09_260411", // Hardcoded older version to force update
        os: 1, // Android
        mac: "66:C6:66:D9:03:DC", 
        country: "IN",
        dev: 2
      }),
    });

    if (!response.ok) {
      throw new Error(`HTTP error! status: ${response.status}`);
    }

    const json = (await response.json()) as QcResponse<FirmwareOtaResp>;
    console.log("OTA Response:", json);

    if ((json.code === 200 || (json as any).retCode === 0) && json.data) {
      return json.data;
    } else if (json.code === 401 || (json as any).retCode === 401 || (json as any).retCode === 60001) {
      console.warn("OTA API returned no update or requires auth. Falling back to mock OTA response for testing flow.");
      // Fallback for testing the flow without needing to implement a full Login/Token system yet
      return {
        openOrNot: 1,
        hardwareVersion: hardwareVersion,
        version: "v2.0.0",
        enforceUpdateFrom: "",
        enforceUpdateTo: "",
        isEnforceUpdate: "0",
        downloadUrl: "https://raw.githubusercontent.com/espressif/arduino-esp32/master/libraries/Update/examples/OTAWebUpdater/OTAWebUpdater.ino", // Dummy tiny file for testing download flow
        uploadDate: new Date().toISOString(),
        os: 1,
        updateDesc: "Mock Update for testing bridge flow"
      };
    }
    return null;
  } catch (error) {
    console.error("Failed to check for OTA update:", error);
    return null;
  }
}

export async function downloadAndInstallOta(otaInfo: FirmwareOtaResp, onProgress?: (progress: number) => void): Promise<void> {
  try {
    if (!otaInfo.downloadUrl) {
      console.warn("No download URL provided in OTA info.");
      return;
    }

    // Use .swu or .bin depending on what the URL has, or just .bin since DfuHandle only checks the content headers
    const urlParts = otaInfo.downloadUrl.split('?')[0].split('/');
    const fileName = urlParts[urlParts.length - 1] || "update.bin";

    if (fileName.toLowerCase().endsWith('.swu')) {
      console.log(`Starting WiFi OTA update handoff for Linux OS (.swu): ${otaInfo.downloadUrl}`);
      await Glass.startWifiOtaUpdate(otaInfo.downloadUrl);
      return;
    }

    const baseDir = FileSystem.cacheDirectory || FileSystem.documentDirectory || "file:///data/user/0/com.anonymous.mobileapp/cache/";
    const localFileUri = baseDir + 'ota_' + fileName;

    console.log(`Downloading OTA file from ${otaInfo.downloadUrl} to ${localFileUri}`);
    
    // Download the file
    const downloadRes = await FileSystem.downloadAsync(
      otaInfo.downloadUrl,
      localFileUri
    );

    if (downloadRes.status !== 200) {
      throw new Error(`Download failed with status ${downloadRes.status}`);
    }

    console.log("Download complete. Starting Native OTA update with file:", downloadRes.uri);
    
    // Strip file:// prefix for Android's java.io.File
    const filePath = downloadRes.uri.replace('file://', '');
    
    // Call our Native module!
    await Glass.startOtaUpdate(filePath);
  } catch (error) {
    console.error("OTA download/install error:", error);
  }
}
