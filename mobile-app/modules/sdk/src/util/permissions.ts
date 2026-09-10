import { Alert, Linking, PermissionsAndroid, Platform } from "react-native";

export interface PermissionResult {
  granted: boolean;
  canAskAgain: boolean;
  error?: string;
}

export interface AllPermissionsResult {
  camera: PermissionResult;
  microphone: PermissionResult;
  location: PermissionResult;
  contacts: PermissionResult;
  bluetooth?: PermissionResult;
}

export class PermissionManager {
  private static instance: PermissionManager;

  static getInstance(): PermissionManager {
    if (!PermissionManager.instance) {
      PermissionManager.instance = new PermissionManager();
    }
    return PermissionManager.instance;
  }

  async requestBluetoothPermission(): Promise<PermissionResult> {
    try {
      if (Platform.OS !== "android") {
        return { granted: true, canAskAgain: false };
      }

      const permissions = [
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
        PermissionsAndroid.PERMISSIONS.ACCESS_COARSE_LOCATION,
        PermissionsAndroid.PERMISSIONS.NEARBY_WIFI_DEVICES,
      ];

      const results = await PermissionsAndroid.requestMultiple(permissions);
      console.log(
        "🚀 ~ PermissionManager ~ requestBluetoothPermission ~ results:",
        results,
      );

      const allGranted = Object.values(results).every(
        (result) => result === PermissionsAndroid.RESULTS.GRANTED,
      );

      return {
        granted: allGranted,
        canAskAgain: Object.values(results).some(
          (result) => result !== PermissionsAndroid.RESULTS.NEVER_ASK_AGAIN,
        ),
        error: allGranted ? undefined : "Bluetooth permission denied",
      };
    } catch (error) {
      console.error("Failed to request Bluetooth permission:", error);
      return {
        granted: false,
        canAskAgain: false,
        error:
          error instanceof Error
            ? error.message
            : "Unknown Bluetooth permission error",
      };
    }
  }

  async checkBluetoothPermission(): Promise<PermissionResult> {
    try {
      if (Platform.OS !== "android") {
        return { granted: true, canAskAgain: false };
      }

      const scanGranted = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_SCAN,
      );
      const connectGranted = await PermissionsAndroid.check(
        PermissionsAndroid.PERMISSIONS.BLUETOOTH_CONNECT,
      );

      return {
        granted: scanGranted && connectGranted,
        canAskAgain: true,
      };
    } catch (error) {
      console.error("Failed to check Bluetooth permission:", error);
      return {
        granted: false,
        canAskAgain: false,
        error: "Failed to check Bluetooth permission",
      };
    }
  }

  // Show Permission Denied Alert
  showPermissionDeniedAlert(permissionName: string, result: PermissionResult) {
    const title = `${permissionName} Permission Required`;
    const message =
      result.error ||
      `This app requires ${permissionName.toLowerCase()} access to function properly.`;

    if (result.canAskAgain) {
      Alert.alert(title, message, [
        { text: "Cancel", style: "cancel" },
        {
          text: "Grant Permission",
          onPress: () => this.requestPermissionByName(permissionName),
        },
      ]);
    } else {
      Alert.alert(
        title,
        `${message}\n\nPlease enable ${permissionName.toLowerCase()} permission in your device settings.`,
        [
          { text: "Cancel", style: "cancel" },
          { text: "Open Settings", onPress: () => this.openAppSettings() },
        ],
      );
    }
  }

  private async requestPermissionByName(
    permissionName: string,
  ): Promise<PermissionResult> {
    switch (permissionName.toLowerCase()) {
      case "bluetooth":
        return this.requestBluetoothPermission();
      default:
        return {
          granted: false,
          canAskAgain: false,
          error: "Unknown permission type",
        };
    }
  }

  private openAppSettings() {
    if (Platform.OS === "ios") {
      Linking.openURL("app-settings:");
    } else if (Platform.OS === "android") {
      Linking.openSettings();
    }
  }

  showMissingPermissionsAlert(missingPermissions: string[]) {
    if (missingPermissions.length === 0) return;

    const permissionList = missingPermissions.join(", ");
    Alert.alert(
      "Permissions Required",
      `This app needs the following permissions to work properly:\n\n${permissionList}\n\nPlease grant these permissions in your device settings.`,
      [
        { text: "Cancel", style: "cancel" },
        { text: "Open Settings", onPress: () => this.openAppSettings() },
      ],
    );
  }
}

export const permissionManager = PermissionManager.getInstance();
