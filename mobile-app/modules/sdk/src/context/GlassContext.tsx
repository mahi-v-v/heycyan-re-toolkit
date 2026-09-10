import AsyncStorage from "@react-native-async-storage/async-storage";
import React, {
  createContext,
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Glass, {
  GlassConnectionState as ConnectionState,
  GlassEvents,
  parseAdvertisement,
  subscribeToGlassEvents,
  type AudioResult,
  type GlassAiStatus,
  type GlassCapabilities,
  type GlassStorageInfo,
  type GlassSystemInfo,
  type MediaConfig,
  type PhotoResult,
  type VideoResult,
} from "../native/Glass";
import { classifyVendor } from "../util";

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

export interface GlassDevice {
  id: string;
  name: string;
  rssi?: number;
  vendor?: string;
}

export interface SavedDevice {
  id: string;
  name: string;
  vendor: string;
}

export interface GlassState {
  // Connection State
  connectionState: ConnectionState;
  connectedDevice: GlassDevice | null;
  discoveredDevices: GlassDevice[];

  // Device Information
  capabilities: GlassCapabilities | null;
  systemInfo: GlassSystemInfo | null;
  storage: GlassStorageInfo | null;

  // Battery
  batteryLevel: number;
  batteryVoltage: number;
  isCharging: boolean;

  // Device Ready
  isReady: boolean;

  // Wearable Status
  isWorn: boolean;

  // Bluetooth Classic Status
  isBtConnected: boolean;
  btName: string | null;

  // Media Configuration
  mediaConfig: MediaConfig | null;

  // File Sync State
  sync: {
    isActive: boolean;
    progress: number; // 0-100
    completedFiles: number;
    totalFiles: number;
    totalBytes: number;
    error: string | null;
  };

  // Saved Devices
  savedDevices: SavedDevice[];

  // Error State
  error: string | null;
}

export interface GlassActions {
  // Connection Management
  connect: (deviceId: string, name?: string) => Promise<void>;
  disconnect: () => Promise<void>;

  // Scanning
  startScan: () => Promise<void>;
  stopScan: () => Promise<void>;

  // Device Info
  refreshCapabilities: () => Promise<void>;
  refreshSystemInfo: () => Promise<void>;
  refreshStorageInfo: () => Promise<void>;

  // Media Capture
  takePhoto: (id?: number, filename?: string) => Promise<PhotoResult>;
  takeAiImage: () => Promise<string>;
  startVideo: (id?: number, filename?: string) => Promise<VideoResult>;
  stopVideo: () => Promise<VideoResult>;
  startAudio: (filename?: string) => Promise<AudioResult>;
  stopAudio: () => Promise<void>;

  // Media Configuration
  getMediaDirectory: () => Promise<string>;
  getMediaConfig: () => Promise<MediaConfig>;
  setMediaConfig: (config: Partial<MediaConfig>) => Promise<boolean>;

  // File Sync
  syncFiles: () => Promise<void>;
  cancelSync: () => Promise<void>;

  // Commands
  sendCommand: (command: string) => Promise<void>;

  // Device Management
  clearMedia: () => Promise<void>;
  reboot: () => Promise<void>;
  factoryReset: () => Promise<void>;
  setAiStatus: (status: GlassAiStatus) => Promise<void>;

  // State Management
  clearError: () => void;
  clearDiscoveredDevices: () => void;
  forgetDevice: (deviceId?: string) => Promise<void>;
  connectSaved: (device: SavedDevice) => Promise<void>;
}

export interface EventSubscribers {
  onDeviceFound: (handler: (device: GlassDevice) => void) => () => void;
  onStateChanged: (handler: (state: ConnectionState) => void) => () => void;
  onConnected: (handler: (device: GlassDevice) => void) => () => void;
  onDisconnected: (handler: () => void) => () => void;
  onReady: (handler: () => void) => () => void;
  onBattery: (
    handler: (level: number, voltage: number, charging: boolean) => void,
  ) => () => void;
  onSyncStarted: (handler: () => void) => () => void;
  onSyncProgress: (
    handler: (completed: number, total: number, percent: number) => void,
  ) => () => void;
  onSyncCompleted: (
    handler: (success: boolean, totalBytes: number, error?: string) => void,
  ) => () => void;
  onFileSynced: (
    handler: (path: string, type: string) => void,
  ) => () => void;
}

export interface GlassContextValue {
  state: GlassState;
  actions: GlassActions;
  subscribe: EventSubscribers;
}

// ============================================================================
// EXTERNAL STORE (bypasses React Context re-rendering)
// ============================================================================

export interface GlassStore {
  getState: () => GlassState;
  setState: (
    partial: Partial<GlassState> | ((prev: GlassState) => Partial<GlassState>),
  ) => void;
  subscribe: (listener: () => void) => () => void;
}

function createGlassStore(initial: GlassState): GlassStore {
  let state = initial;
  const listeners = new Set<() => void>();

  return {
    getState: () => state,
    setState: (partial) => {
      const next = typeof partial === "function" ? partial(state) : partial;
      const merged = { ...state, ...next };

      // Only notify if something actually changed
      let changed = false;
      for (const key of Object.keys(next) as (keyof GlassState)[]) {
        if (!Object.is(state[key], merged[key])) {
          changed = true;
          break;
        }
      }

      if (changed) {
        state = merged;
        listeners.forEach((l) => l());
      }
    },
    subscribe: (listener) => {
      listeners.add(listener);
      return () => {
        listeners.delete(listener);
      };
    },
  };
}

// ============================================================================
// CONTEXT CREATION — store (stable ref) and actions (stable)
// ============================================================================

const GlassStoreContext = createContext<GlassStore | null>(null);
const GlassActionsContext = createContext<{
  actions: GlassActions;
  subscribe: EventSubscribers;
} | null>(null);

// Legacy context kept for backwards compatibility
const GlassContext = createContext<GlassContextValue | null>(null);

// AsyncStorage key for multi-device persistence
const SAVED_DEVICES_KEY = "@glass_saved_devices";

// ============================================================================
// INITIAL STATE
// ============================================================================

const initialState: GlassState = {
  connectionState: ConnectionState.IDLE,
  connectedDevice: null,
  discoveredDevices: [],
  capabilities: null,
  systemInfo: null,
  storage: null,
  batteryLevel: 0,
  batteryVoltage: 0,
  isCharging: false,
  isReady: false,
  isWorn: false,
  isBtConnected: true,
  btName: null,
  mediaConfig: null,
  sync: {
    isActive: false,
    progress: 0,
    completedFiles: 0,
    totalFiles: 0,
    totalBytes: 0,
    error: null,
  },
  savedDevices: [],
  error: null,
};

// ============================================================================
// PROVIDER COMPONENT
// ============================================================================

export const GlassProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const storeRef = useRef(createGlassStore(initialState));
  const store = storeRef.current;
  const [, setIsInitialized] = useState(false);

  // Track active subscriptions for cleanup
  const subscriptionsRef = useRef<{ remove: () => void }[]>([]);

  // ============================================================================
  // FETCH INITIAL STATE (on mount)
  // ============================================================================

  useEffect(() => {
    const fetchInitialState = async () => {
      try {
        console.log("[GlassContext] Fetching initial state...");

        // Get current connection state
        const currentState = await Glass.getCurrentState();
        const isConnected = await Glass.isConnected();

        console.log("[GlassContext] Initial state:", {
          currentState,
          isConnected,
        });

        // If connected, fetch device info
        if (isConnected && currentState === "CONNECTED") {
          try {
            // Fetch all available info in parallel
            const [capabilities, systemInfo, storage] =
              await Promise.allSettled([
                Glass.getCapabilities(),
                Glass.getSystemInfo(),
                Glass.getStorageInfo(),
              ]);

            const batteryLevel =
              systemInfo.status === "fulfilled"
                ? systemInfo.value.batteryLevel
                : 0;
            const batteryVoltage =
              systemInfo.status === "fulfilled"
                ? systemInfo.value.batteryVoltage
                : 0;
            const isCharging =
              systemInfo.status === "fulfilled"
                ? systemInfo.value.isCharging
                : false;
            const isReady =
              systemInfo.status === "fulfilled"
                ? systemInfo.value.isReady
                : false;

            console.log("[GlassContext] Battery info from systemInfo:", {
              batteryLevel,
              batteryVoltage,
              isCharging,
              systemInfoValue:
                systemInfo.status === "fulfilled" ? systemInfo.value : null,
            });

            const savedRawConnected = await AsyncStorage.getItem(
              SAVED_DEVICES_KEY,
            ).catch(() => null);
            const savedDevicesConnected: SavedDevice[] = savedRawConnected
              ? JSON.parse(savedRawConnected)
              : [];
            // JS owns device identity — recover the connected device from the
            // saved list (most-recent first; JS auto-connects to that one).
            const connectedDevice: GlassDevice | null =
              savedDevicesConnected.length > 0
                ? {
                    id: savedDevicesConnected[0].id,
                    name: savedDevicesConnected[0].name,
                    vendor: savedDevicesConnected[0].vendor,
                  }
                : null;

            store.setState({
              connectionState: currentState,
              connectedDevice,
              capabilities:
                capabilities.status === "fulfilled" ? capabilities.value : null,
              systemInfo:
                systemInfo.status === "fulfilled" ? systemInfo.value : null,
              storage: storage.status === "fulfilled" ? storage.value : null,
              batteryLevel,
              batteryVoltage,
              isCharging,
              isReady,
              savedDevices: savedDevicesConnected,
            });

            console.log("[GlassContext] Initial state restored from native", {
              connectedDevice,
              batteryLevel,
              batteryVoltage,
            });
          } catch (error) {
            console.error("[GlassContext] Error fetching device info:", error);
            // Still set connection state even if device info fails
            store.setState({ connectionState: currentState });
          }
        } else {
          // Not connected, just set the state
          store.setState({ connectionState: currentState });

          // Load saved devices and attempt silent auto-connect to most recent
          try {
            const savedRaw = await AsyncStorage.getItem(SAVED_DEVICES_KEY);
            const savedDevices: SavedDevice[] = savedRaw
              ? JSON.parse(savedRaw)
              : [];
            store.setState({ savedDevices });
            if (savedDevices.length > 0) {
              const { id, name, vendor } = savedDevices[0];
              console.log(
                "[GlassContext] Auto-connecting to last device:",
                id,
                vendor,
                name,
              );
              Glass.connect(id, vendor, name).catch((err: any) => {
                console.log(
                  "[GlassContext] Auto-connect failed (non-fatal):",
                  err?.message,
                );
              });
            }
          } catch {
            // Non-critical; user can connect manually
          }
        }

        setIsInitialized(true);
      } catch (error) {
        console.error("[GlassContext] Error fetching initial state:", error);
        setIsInitialized(true);
      }
    };

    fetchInitialState();
  }, []);

  // ============================================================================
  // EVENT SUBSCRIPTIONS (Internal - update state)
  // ============================================================================

  useEffect(() => {
    const subs = [
      // Device Found — generic scanner surfaces ALL BLE devices; classify the
      // vendor here (JS-owned, OTA-updatable) and only keep known glasses.
      subscribeToGlassEvents(GlassEvents.DEVICE_FOUND, (event: any) => {
        const advertisement = parseAdvertisement(event.advertisement);

        const vendor = classifyVendor(advertisement, event.deviceName);

        if (vendor === "unknown") return; // not a supported glass — ignore

        const device: GlassDevice = {
          id: event.deviceId,
          name: event.deviceName,
          rssi: event.rssi,
          vendor,
        };
        store.setState((prev) => {
          const idx = prev.discoveredDevices.findIndex(
            (d) => d.id === device.id,
          );
          if (idx === -1) {
            console.log(
              "[GlassContext] DEVICE_FOUND:",
              device.id,
              device.name,
              vendor,
              "rssi:",
              device.rssi,
            );
            return { discoveredDevices: [...prev.discoveredDevices, device] };
          }
          // Already known — refresh rssi/name/vendor in place.
          const updated = prev.discoveredDevices.slice();
          updated[idx] = { ...updated[idx], ...device };
          return { discoveredDevices: updated };
        });
      }),

      // State Changed
      subscribeToGlassEvents(GlassEvents.STATE_CHANGED, (event: any) => {
        store.setState({ connectionState: event.newState });
      }),

      // Connected
      subscribeToGlassEvents(GlassEvents.CONNECTED, (event: any) => {
        setTimeout(() => {
          refreshStorageInfo();
        }, 1000);

        const BT_NAME = "Glasses BT";
        // The native CONNECTED name can be a generic vendor fallback (e.g. K900
        // connects by MAC and never sees the BLE name). Prefer the advertised name
        // we captured while scanning.
        const discoveredName = store
          .getState()
          .discoveredDevices.find((d) => d.id === event.deviceId)?.name;
        store.setState({
          connectedDevice: {
            id: event.deviceId,
            name: discoveredName || event.deviceName,
            vendor: event.vendor,
          },
          btName: BT_NAME,
          discoveredDevices: [],
        });
        // Upsert device into saved list (most recent first), preserving saved name
        if (event.vendor) {
          AsyncStorage.getItem(SAVED_DEVICES_KEY)
            .then((raw) => {
              const existing: SavedDevice[] = raw ? JSON.parse(raw) : [];
              const savedName = existing.find(
                (d) => d.id === event.deviceId,
              )?.name;
              const resolvedName =
                savedName ?? discoveredName ?? event.deviceName;
              store.setState({
                connectedDevice: {
                  id: event.deviceId,
                  name: resolvedName,
                  vendor: event.vendor,
                },
              });
              const existingIndex = existing.findIndex(
                (d) => d.id === event.deviceId,
              );
              const updatedDevice: SavedDevice = {
                id: event.deviceId,
                name: resolvedName,
                vendor: event.vendor,
              };
              const updated: SavedDevice[] =
                existingIndex === -1
                  ? [updatedDevice, ...existing]
                  : existing.map((d, i) =>
                      i === existingIndex ? updatedDevice : d,
                    );
              store.setState({ savedDevices: updated });
              return AsyncStorage.setItem(
                SAVED_DEVICES_KEY,
                JSON.stringify(updated),
              );
            })
            .catch(() => {});
        }
      }),

      // Disconnected
      subscribeToGlassEvents(GlassEvents.DISCONNECTED, () => {
        store.setState({
          connectedDevice: null,
          isReady: false,
          isWorn: false,
          isBtConnected: false,
          btName: null,
          capabilities: null,
          systemInfo: null,
        });
      }),

      // Ready
      subscribeToGlassEvents(GlassEvents.READY, () => {
        store.setState({ isReady: true });
        refreshStorageInfo();
      }),

      // Battery
      subscribeToGlassEvents(GlassEvents.BATTERY, (event: any) => {
        store.setState({
          batteryLevel: event.level || 0,
          batteryVoltage:
            typeof event.voltage === "string"
              ? parseFloat(event.voltage)
              : event.voltage || 0,
          isCharging: event.isCharging || false,
        });
      }),

      // Sync Started
      subscribeToGlassEvents(GlassEvents.SYNC_STARTED, () => {
        store.setState({
          sync: {
            isActive: true,
            progress: 0,
            completedFiles: 0,
            totalFiles: 0,
            totalBytes: 0,
            error: null,
          },
        });
      }),

      // Sync Progress
      subscribeToGlassEvents(GlassEvents.SYNC_PROGRESS, (event: any) => {
        const progress = event.percent || 0;

        store.setState((prev) => ({
          sync: {
            ...prev.sync,
            progress,
            completedFiles: event.current || 0,
            totalFiles: event.total || 0,
          },
        }));
      }),

      // Wear Status
      subscribeToGlassEvents(GlassEvents.WEAR_STATUS, (event: any) => {
        store.setState({ isWorn: event.worn });
      }),

      // BT Classic Status
      subscribeToGlassEvents(GlassEvents.BT_STATUS, (event: any) => {
        console.log("[GlassContext] BT_STATUS:", event);
        store.setState({ isBtConnected: event.connected });
      }),

      // Storage info updated (after photo/video/audio)
      subscribeToGlassEvents(GlassEvents.STORAGE_INFO, (event: any) => {
        store.setState((prev) => ({
          storage: prev.storage
            ? {
                ...prev.storage,
                photoCount: event.photoCount,
                videoCount: event.videoCount,
                audioCount: event.audioCount,
              }
            : {
                totalSize: 0,
                freeSize: 0,
                usedSize: 0,
                photoCount: event.photoCount,
                videoCount: event.videoCount,
                audioCount: event.audioCount,
              },
        }));
      }),

      // Sync Completed
      subscribeToGlassEvents(GlassEvents.SYNC_COMPLETED, (event: any) => {
        store.setState((prev) => ({
          sync: {
            ...prev.sync,
            isActive: false,
            progress: event.success ? 100 : prev.sync.progress,
            totalBytes: event.totalBytes || 0,
            error: event.success ? null : event.error || "Sync failed",
          },
        }));
      }),
    ];

    subscriptionsRef.current = subs;
    return () => subs.forEach((sub) => sub.remove());
  }, []);

  // ============================================================================
  // ACTIONS
  // ============================================================================

  // Connection — vendor comes from the classified discovered device (or the saved
  // list for a known device); native routes by that vendor string.
  const connect = useCallback(async (deviceId: string, name?: string) => {
    try {
      store.setState({ error: null });
      const s = store.getState();
      const known =
        s.discoveredDevices.find((d) => d.id === deviceId) ??
        s.savedDevices.find((d) => d.id === deviceId);
      const vendor = known?.vendor ?? "k900";
      // Prefer the advertised/known name (e.g. "Glass_BLE0002") so the notification
      // and connected-device name aren't a generic vendor fallback.
      const resolvedName = name ?? known?.name;
      await Glass.connect(deviceId, vendor, resolvedName);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Connection failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const disconnect = useCallback(async () => {
    try {
      await Glass.disconnect();
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Disconnect failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  // Scanning
  const startScan = useCallback(async () => {
    try {
      store.setState({ discoveredDevices: [], error: null });
      try {
        await Glass.stopScan();
      } catch {}
      await new Promise((r) => setTimeout(r, 500));
      await Glass.startScan();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Scan failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const stopScan = useCallback(async () => {
    try {
      await Glass.stopScan();
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Stop scan failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  // Device Info
  const refreshCapabilities = useCallback(async () => {
    try {
      const capabilities = await Glass.getCapabilities();
      store.setState({ capabilities });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Get capabilities failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const refreshSystemInfo = useCallback(async () => {
    try {
      const systemInfo = await Glass.getSystemInfo();
      console.log("🚀 ~ GlassProvider ~ systemInfo:", systemInfo);
      store.setState({ systemInfo });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Get system info failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const refreshStorageInfo = useCallback(async () => {
    try {
      const storage = await Glass.getStorageInfo();
      console.log("[GlassContext] refreshStorageInfo result:", storage);
      store.setState({ storage });
    } catch (error) {
      console.log("[GlassContext] refreshStorageInfo error:", error);
      const message =
        error instanceof Error ? error.message : "Get storage info failed";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  // Device Management
  const clearMedia = useCallback(async () => {
    await Glass.clearMedia();
    await refreshStorageInfo();
  }, [refreshStorageInfo]);

  const reboot = useCallback(async () => {
    await Glass.reboot();
  }, []);

  const factoryReset = useCallback(async () => {
    await Glass.factoryReset();
  }, []);

  const setAiStatus = useCallback(async (status: GlassAiStatus) => {
    await Glass.setAiStatus(status);
  }, []);

  // Media Capture
  const takePhoto = useCallback(async (id?: number, filename?: string) => {
    return await Glass.takePhoto(id, filename);
  }, []);

  const takeAiImage = useCallback(async () => {
    return await Glass.takeAiImage();
  }, []);

  const startVideo = useCallback(async (id?: number, filename?: string) => {
    return await Glass.startVideo(id, filename);
  }, []);

  const stopVideo = useCallback(async () => {
    return await Glass.stopVideo();
  }, []);

  const startAudio = useCallback(async (filename?: string) => {
    return await Glass.startAudio(filename);
  }, []);

  const stopAudio = useCallback(async () => {
    await Glass.stopAudio();
  }, []);

  const getMediaDirectory = useCallback(async () => {
    const directory = await Glass.getMediaDirectory();
    return directory;
  }, []);

  // Media Configuration
  const getMediaConfig = useCallback(async () => {
    const config = await Glass.getMediaConfig();
    store.setState({ mediaConfig: config });
    return config;
  }, []);

  const setMediaConfig = useCallback(async (config: Partial<MediaConfig>) => {
    const success = await Glass.setMediaConfig(config);
    if (success) {
      // Refresh config after successful update
      const newConfig = await Glass.getMediaConfig();
      store.setState({ mediaConfig: newConfig });
    }
    return success;
  }, []);

  // File Sync
  const syncFiles = useCallback(async () => {
    try {
      store.setState((prev) => ({
        sync: { ...prev.sync, error: null },
      }));
      await Glass.syncFiles();
    } catch (error) {
      const message = error instanceof Error ? error.message : "Sync failed";
      store.setState((prev) => ({
        sync: { ...prev.sync, error: message },
      }));
      throw error;
    }
  }, []);

  const cancelSync = useCallback(async () => {
    await Glass.cancelSync();
  }, []);

  // Commands
  const sendCommand = useCallback(async (command: string) => {
    await Glass.sendCommand(command);
  }, []);

  // State Management
  const clearError = useCallback(() => {
    store.setState({ error: null });
  }, []);

  const clearDiscoveredDevices = useCallback(() => {
    store.setState({ discoveredDevices: [] });
  }, []);

  const forgetDevice = useCallback(async (deviceId?: string) => {
    const raw = await AsyncStorage.getItem(SAVED_DEVICES_KEY);
    const existing: SavedDevice[] = raw ? JSON.parse(raw) : [];
    const updated = deviceId ? existing.filter((d) => d.id !== deviceId) : [];
    await AsyncStorage.setItem(SAVED_DEVICES_KEY, JSON.stringify(updated));
    store.setState({ savedDevices: updated });
  }, []);

  const connectSaved = useCallback(async (device: SavedDevice) => {
    if (store.getState().connectionState === ConnectionState.CONNECTED) {
      await Glass.disconnect();
    }
    await Glass.connect(device.id, device.vendor, device.name);
  }, []);

  const actions: GlassActions = useMemo(
    () => ({
      connect,
      disconnect,
      startScan,
      stopScan,
      refreshCapabilities,
      refreshSystemInfo,
      refreshStorageInfo,
      takePhoto,
      takeAiImage,
      startVideo,
      stopVideo,
      startAudio,
      stopAudio,
      getMediaDirectory,
      getMediaConfig,
      setMediaConfig,
      syncFiles,
      cancelSync,
      sendCommand,
      clearMedia,
      reboot,
      factoryReset,
      setAiStatus,
      clearError,
      clearDiscoveredDevices,
      forgetDevice,
      connectSaved,
    }),
    [
      connect,
      disconnect,
      startScan,
      stopScan,
      refreshCapabilities,
      refreshSystemInfo,
      refreshStorageInfo,
      takePhoto,
      takeAiImage,
      startVideo,
      stopVideo,
      startAudio,
      stopAudio,
      getMediaDirectory,
      getMediaConfig,
      setMediaConfig,
      syncFiles,
      cancelSync,
      sendCommand,
      clearMedia,
      reboot,
      factoryReset,
      setAiStatus,
      clearError,
      clearDiscoveredDevices,
      forgetDevice,
      connectSaved,
    ],
  );

  // ============================================================================
  // EVENT SUBSCRIBERS (External - for components)
  // ============================================================================

  const onDeviceFound = useCallback(
    (handler: (device: GlassDevice) => void) => {
      const sub = subscribeToGlassEvents(
        GlassEvents.DEVICE_FOUND,
        (event: any) => {
          const advertisement = parseAdvertisement(event.advertisement);
          const vendor = classifyVendor(advertisement);
          if (vendor === "unknown") return; // only surface supported glasses
          handler({
            id: event.deviceId,
            name: event.deviceName,
            rssi: event.rssi,
            vendor,
          });
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onStateChanged = useCallback(
    (handler: (state: ConnectionState) => void) => {
      const sub = subscribeToGlassEvents(
        GlassEvents.STATE_CHANGED,
        (event: any) => {
          handler(event.newState);
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onConnected = useCallback((handler: (device: GlassDevice) => void) => {
    const sub = subscribeToGlassEvents(GlassEvents.CONNECTED, (event: any) => {
      const device: GlassDevice = {
        id: event.deviceId,
        name: event.deviceName,
      };
      handler(device);
    });
    return () => sub.remove();
  }, []);

  const onDisconnected = useCallback((handler: () => void) => {
    const sub = subscribeToGlassEvents(GlassEvents.DISCONNECTED, () => {
      handler();
    });
    return () => sub.remove();
  }, []);

  const onReady = useCallback((handler: () => void) => {
    const sub = subscribeToGlassEvents(GlassEvents.READY, () => {
      handler();
    });
    return () => sub.remove();
  }, []);

  const onBattery = useCallback(
    (handler: (level: number, voltage: number, charging: boolean) => void) => {
      const sub = subscribeToGlassEvents(GlassEvents.BATTERY, (event: any) => {
        handler(event.level, event.voltage, event.isCharging);
      });
      return () => sub.remove();
    },
    [],
  );

  const onSyncStarted = useCallback((handler: () => void) => {
    const sub = subscribeToGlassEvents(GlassEvents.SYNC_STARTED, () => {
      handler();
    });
    return () => sub.remove();
  }, []);

  const onSyncProgress = useCallback(
    (handler: (completed: number, total: number, percent: number) => void) => {
      const sub = subscribeToGlassEvents(
        GlassEvents.SYNC_PROGRESS,
        (event: any) => {
          handler(event.current, event.total, event.percent);
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onSyncCompleted = useCallback(
    (
      handler: (success: boolean, totalBytes: number, error?: string) => void,
    ) => {
      const sub = subscribeToGlassEvents(
        GlassEvents.SYNC_COMPLETED,
        (event: any) => {
          handler(event.success, event.totalBytes, event.error);
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onFileSynced = useCallback(
    (handler: (path: string, type: string) => void) => {
      const sub = subscribeToGlassEvents(
        GlassEvents.SYNC_FILE,
        (event: any) => {
          handler(event.path, event.type);
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const subscribe: EventSubscribers = useMemo(
    () => ({
      onDeviceFound,
      onStateChanged,
      onConnected,
      onDisconnected,
      onReady,
      onBattery,
      onSyncStarted,
      onSyncProgress,
      onSyncCompleted,
      onFileSynced,
    }),
    [
      onDeviceFound,
      onStateChanged,
      onConnected,
      onDisconnected,
      onReady,
      onBattery,
      onSyncStarted,
      onSyncProgress,
      onSyncCompleted,
      onFileSynced,
    ],
  );

  // Stable actions+subscribe value (never changes after mount)
  const actionsValue = useMemo(
    () => ({ actions, subscribe }),
    [actions, subscribe],
  );

  return (
    <GlassActionsContext.Provider value={actionsValue}>
      <GlassStoreContext.Provider value={store}>
        {children}
      </GlassStoreContext.Provider>
    </GlassActionsContext.Provider>
  );
};

export { GlassActionsContext, GlassStoreContext };
export default GlassContext;
