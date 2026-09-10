import { requireNativeModule } from "expo-modules-core";

const GlassModule = requireNativeModule("GlassModule");

type Subscription = { remove: () => void };

/**
 * Glass device capabilities
 */
export interface GlassCapabilities {
  audio: boolean;
  sensors: boolean;
  display: boolean;
  wakeword: boolean;
  battery: boolean;
  camera: boolean;
}

/**
 * Connection states for glass devices
 */
export enum GlassConnectionState {
  IDLE = "IDLE",
  SCANNING = "SCANNING",
  CONNECTING = "CONNECTING",
  CONNECTED = "CONNECTED",
  DISCONNECTING = "DISCONNECTING",
  ERROR = "ERROR",
}

/**
 * Event types emitted by the glass module
 */
export interface StreamReadyEvent {
  host: string;
  port: number;
}

/** WHIP stream lifecycle event from glass (sc_stream BLE reply) */
export interface StreamStatusEvent {
  status: "connecting" | "started" | "stopped" | "error";
  msg?: string;
}

export const GlassEvents = {
  DEVICE_FOUND: "GLASS_DEVICE_FOUND",
  STATE_CHANGED: "GLASS_STATE_CHANGED",
  CONNECTED: "GLASS_CONNECTED",
  DISCONNECTED: "GLASS_DISCONNECTED",
  BATTERY: "GLASS_BATTERY",
  READY: "GLASS_READY",
  ERROR: "GLASS_ERROR",
  SYNC_STARTED: "GLASS_SYNC_STARTED",
  SYNC_PROGRESS: "GLASS_SYNC_PROGRESS",
  SYNC_COMPLETED: "GLASS_SYNC_COMPLETED",
  SYNC_FILE: "GLASS_SYNC_FILE",
  WEAR_STATUS: "GLASS_WEAR_STATUS",
  BT_STATUS: "GLASS_BT_STATUS",
  STORAGE_INFO: "GLASS_STORAGE_INFO",
  STREAM_READY: "GLASS_STREAM_READY",
  STREAM_STOPPED: "GLASS_STREAM_STOPPED",
  STREAM_STATUS: "GLASS_STREAM_STATUS",
  OTA_PROGRESS: "GLASS_OTA_PROGRESS",
  OTA_COMPLETED: "GLASS_OTA_COMPLETED",
  OTA_LINUX_COMPLETED: "GLASS_OTA_LINUX_COMPLETED",
  OTA_ERROR: "GLASS_OTA_ERROR",
} as const;

/**
 * Parsed BLE advertisement for a discovered device. Used by JS to classify the
 * vendor (so the rule is OTA-updatable). Surfaced by the generic native scanner.
 */
export interface GlassAdvertisement {
  /** Advertised service UUIDs, normalized lowercase (16-bit short form e.g. "4860", "3802"). */
  serviceUuids: string[];
  /** Manufacturer-specific data: company-id hex (e.g. "1234") → payload hex. */
  manufacturerData: Record<string, string>;
  /** Service data: service-uuid (lowercase) → payload hex. */
  serviceData: Record<string, string>;
  /** Full raw advertisement bytes as hex. Populated on Android; empty on iOS (CoreBluetooth doesn't expose raw AD). */
  rawAdvertisement: string;
  /** BLE Appearance (AD type 0x19) as a number, if advertised (e.g. K900 = 14667). */
  appearance?: number;
}

/**
 * Device found event data
 */
export interface DeviceFoundEvent {
  deviceId: string;
  deviceName: string;
  rssi?: number;
  /** Full advertisement for JS-side vendor classification (may be undefined on legacy paths). */
  advertisement?: GlassAdvertisement;
}

/**
 * The native scanner sends the advertisement as a JSON string (to avoid Bundle
 * nesting). Parse it into a `GlassAdvertisement`, tolerating missing/invalid data.
 */
export function parseAdvertisement(
  raw: unknown,
): GlassAdvertisement | undefined {
  if (!raw) return undefined;
  try {
    const a = typeof raw === "string" ? JSON.parse(raw) : raw;
    return {
      serviceUuids: Array.isArray(a?.serviceUuids) ? a.serviceUuids : [],
      manufacturerData: a?.manufacturerData ?? {},
      serviceData: a?.serviceData ?? {},
      rawAdvertisement:
        typeof a?.rawAdvertisement === "string" ? a.rawAdvertisement : "",
      appearance: typeof a?.appearance === "number" ? a.appearance : undefined,
    };
  } catch {
    return undefined;
  }
}

/**
 * State changed event data
 */
export interface StateChangedEvent {
  oldState: GlassConnectionState;
  newState: GlassConnectionState;
  reason?: string;
}

/**
 * Connected event data
 */
export interface ConnectedEvent {
  deviceId: string;
  deviceName: string;
}

/**
 * Disconnected event data
 */
export interface DisconnectedEvent {
  deviceId: string;
  reason: string;
  wasExpected: boolean;
}

/**
 * Battery event data
 */
export interface BatteryEvent {
  level: number;
  isCharging: boolean;
  voltage?: number;
}

/**
 * Storage information
 */
export interface GlassStorageInfo {
  totalSize: number;
  freeSize: number;
  usedSize: number;
  photoCount: number;
  videoCount: number;
  audioCount: number;
}

/**
 * System information including battery status
 */
export interface GlassSystemInfo {
  firmwareVersion: string;
  appVersion: string;
  deviceModel: string;
  batteryLevel: number;
  isCharging: boolean;
  batteryVoltage: number;
  isReady: boolean;
}

/**
 * Error event data
 */
export interface ErrorEvent {
  error: string;
  code?: string;
  isFatal: boolean;
}

/**
 * File sync started event
 */
export interface SyncStartedEvent {
  // No additional data
}

/**
 * File sync progress event (overall sync progress)
 */
export interface SyncProgressEvent {
  current: number; // Files completed so far
  total: number; // Total files to sync
  percent: number; // Overall progress percentage (0-100)
}

/**
 * Wearable status event data
 */
export interface WearStatusEvent {
  worn: boolean;
}

/**
 * Bluetooth Classic connection status event data
 */
export interface BtStatusEvent {
  connected: boolean;
}

/**
 * File sync completed event
 */
export interface SyncCompletedEvent {
  totalFiles: number;
  totalBytes: number;
  success: boolean;
  error?: string;
}

/**
 * OTA update progress event
 */
export interface OtaProgressEvent {
  percent: number;
}

/**
 * OTA update completed event
 */
export interface OtaCompletedEvent {
  success: boolean;
}

/**
 * A single file finished syncing and is written to disk. Emitted per-file so the gallery can
 * index + thumbnail it incrementally instead of re-listing the whole media directory.
 */
export interface FileSyncedEvent {
  path: string; // Absolute path of the written file
  type: string; // "photo" | "video" | "audio"
}

/**
 * Result of a photo capture operation
 */
export interface PhotoResult {
  success: boolean;
  errorCode: number;
  photoCount: number;
  filename?: string;
}

/**
 * Result of a video operation
 */
export interface VideoResult {
  success: boolean;
  errorCode: number;
  videoCount: number;
}

/**
 * Result of an audio operation
 */
export interface AudioResult {
  success: boolean;
  errorCode: number;
  audioCount: number;
}

/**
 * Media configuration for photo capture
 */
export interface PhotoConfig {
  width: number;
  height: number;
}

/**
 * Media configuration for video recording
 */
export interface VideoConfig {
  width: number;
  height: number;
  quality: number;
  duration: number;
}

/**
 * Media configuration for audio recording
 */
export interface AudioConfig {
  duration: number;
}

/**
 * Complete media configuration
 */
export interface MediaConfig {
  photo: PhotoConfig;
  video: VideoConfig;
  audio: AudioConfig;
}

/**
 * Partial media configuration for updates
 */
export interface PartialMediaConfig {
  photo?: PhotoConfig;
  video?: VideoConfig;
  audio?: AudioConfig;
}

/**
 * Generic, vendor-agnostic AI status the glasses can display. Transition-based: each value is a
 * state change the adapter maps to its device's native AI indicator.
 */
export type GlassAiStatus =
  | "speaking_start"
  | "speaking_stop"
  | "thinking_start"
  | "thinking_stop";

/**
 * Glass API interface
 */
export interface GlassAPI {
  /**
   * Connect to a glass device. Vendor is detected on the JS side and passed in,
   * so native routes directly to the matching adapter (no native detection).
   * On iOS, rediscovers the peripheral via a background scan when needed (e.g.
   * reconnect after app restart); on Android, connects directly by MAC address.
   * @param deviceId Device identifier (UUID on iOS, MAC address on Android)
   * @param vendor Vendor string ("k900" | "cyan" | "mock") — used as the native adapter key
   * @param name Optional display name (used for the Android foreground-service notification)
   */
  connect(deviceId: string, vendor: string, name?: string): Promise<void>;

  /**
   * Disconnect from the current device
   */
  disconnect(): Promise<void>;

  /**
   * Send a command to the device
   * @param command Command name
   * @param params Command parameters
   */
  sendCommand(command: string, params?: Record<string, any>): Promise<void>;

  /**
   * Get device capabilities
   */
  getCapabilities(): Promise<GlassCapabilities>;

  /**
   * Get current connection state
   */
  getCurrentState(): Promise<GlassConnectionState>;

  /**
   * Check if connected to a device
   */
  isConnected(): Promise<boolean>;

  /**
   * Get storage information
   */
  getStorageInfo(): Promise<GlassStorageInfo>;

  /**
   * Get system information including battery status
   */
  getSystemInfo(): Promise<GlassSystemInfo>;

  /**
   * Check if the Bluetooth radio is powered on
   */
  isBluetoothEnabled(): Promise<boolean>;

  /**
   * Start scanning for nearby glass devices
   * Discovered devices will be emitted via DEVICE_FOUND events
   */
  startScan(): Promise<void>;

  /**
   * Stop scanning for nearby glass devices
   */
  stopScan(): Promise<void>;

  /**
   * Take a photo with optional parameters
   * @param id Optional photo ID
   * @param filename Optional filename for the photo
   * @returns PhotoResult with success status and error code
   */
  takePhoto(id?: number, filename?: string): Promise<PhotoResult>;

  /**
   * Take an AI-enhanced image
   * @returns File path to the captured AI image
   */
  takeAiImage(): Promise<string>;

  /**
   * Start video recording
   * @returns VideoResult with success status
   */
  startVideo(id?: number, filename?: string): Promise<VideoResult>;

  /**
   * Stop video recording
   * @returns VideoResult with success status
   */
  stopVideo(): Promise<VideoResult>;

  /**
   * Start audio recording
   * @returns AudioResult with success status
   */
  startAudio(filename?: string): Promise<AudioResult>;

  /**
   * Stop audio recording
   * @returns AudioResult with success status
   */
  stopAudio(): Promise<AudioResult>;

  getMediaDirectory(): Promise<string>;
  /**
   * Get current media configuration from device
   * @returns MediaConfig with all media settings
   */
  getMediaConfig(): Promise<MediaConfig>;

  /**
   * Update media configuration on device
   * @param config Partial config with optional photo/video/audio sections
   * @returns Promise<boolean> indicating success
   */
  setMediaConfig(config: PartialMediaConfig): Promise<boolean>;

  /**
   * Sync (download) all media files from glasses to phone
   * Uses WiFi Hotspot for file transfer
   * Progress tracked via events: onSyncStarted, onSyncProgress, onSyncCompleted
   */
  syncFiles(): Promise<void>;

  /**
   * Cancel an in-progress media sync. Tears down the WiFi hotspot/P2P and
   * returns the glasses to capture mode. Resolves immediately; the sync's
   * onSyncCompleted fires with success=false, error="Cancelled".
   */
  cancelSync(): Promise<void>;

  /**
   * Set the glasses' device name. The adapter sets whatever name(s) the vendor
   * supports (e.g. Bluetooth Classic name on K900; no-op where unsupported).
   * @param name Name to set on the device
   */
  setDeviceName(name: string): Promise<void>;

  /** Delete all media (photos/videos/audio) from the glasses' storage. */
  clearMedia(): Promise<void>;

  /** Reboot/restart the device. The connection will drop as the device restarts. */
  reboot(): Promise<void>;

  /** Factory-reset the device. The connection will drop and the device may unpair. */
  factoryReset(): Promise<void>;

  /**
   * Set the on-device AI status indicator (speaking/thinking). Vendor-agnostic;
   * adapters map it to the device's native AI status (no-op where unsupported).
   */
  setAiStatus(status: GlassAiStatus): Promise<void>;

  /**
   * Start WHIP stream. Glass connects to WiFi and streams camera directly to the WHIP URL.
   * Returns immediately — listen to GLASS_STREAM_STATUS events for lifecycle.
   */
  startStream(params: {
    ssid: string;
    pwd: string;
    url: string;
    streamKey?: string;
    fps?: number;
    bitrate?: number;
  }): Promise<void>;

  /** Stop the WHIP stream. */
  stopStream(): Promise<void>;

  /**
   * Start live camera stream — Mode A (auto local hotspot).
   * Phone creates a local hotspot, glass joins it. Phone's WiFi unaffected.
   * Falls back to glass AP mode if local hotspot fails.
   */
  startLiveStream(): Promise<StreamReadyEvent>;

  /**
   * Start live camera stream — Mode B (user WiFi).
   * Glass joins the user's existing WiFi network using provided credentials.
   * Credentials should be saved and reused by the caller.
   */
  startLiveStreamWithWifi(
    ssid: string,
    password: string,
  ): Promise<StreamReadyEvent>;

  /** Stop live stream and release WiFi connection. */
  stopLiveStream(): Promise<void>;

  /**
   * Returns true if a glass video track is currently active (or pending connection).
   * Use on screen mount to restore streaming UI state after back-navigation.
   */
  isStreamingActive(): Promise<boolean>;

  /**
   * Start OTA firmware update
   * @param filePath Path to the firmware file
   */
  startOtaUpdate(filePath: string): Promise<void>;

  /**
   * Send a WiFi OTA URL to the glasses (Linux OS update)
   * @param url The URL of the .swu file
   */
  startWifiOtaUpdate(url: string): Promise<void>;

  /**
   * Enable or disable the on-device wake word ("Hey Cyan") over BLE
   * (vendor SDK `aiVoiceWake`, opcode 0x44). It's a device setting — same get/set
   * shape as wear-detection — so it very likely persists across reboots.
   * Fire-and-forget: the device's ACK arrives asynchronously via the native
   * `[WAKE]` trace (GLASS_ERROR/onError) logs.
   * @param enabled true = wake word on, false = off
   */
  setWakeWord(enabled: boolean): Promise<void>;

  /**
   * Query the current on-device wake-word enabled state (`aiVoiceWake` get).
   * The result arrives asynchronously via the native `[WAKE]` trace logs.
   */
  getWakeWord(): Promise<void>;
}

/**
 * Glass module API
 */
export const Glass: GlassAPI = {
  connect: (deviceId: string, vendor: string, name?: string) =>
    GlassModule.connect(deviceId, vendor, name),

  disconnect: () => GlassModule.disconnect(),

  sendCommand: (command: string, params: Record<string, any> = {}) =>
    GlassModule.sendCommand(command, params),

  getCapabilities: () => GlassModule.getCapabilities(),

  getCurrentState: async () => {
    const state = await GlassModule.getCurrentState();
    return state as GlassConnectionState;
  },

  isConnected: () => GlassModule.isConnected(),

  getStorageInfo: () => GlassModule.getStorageInfo(),

  getSystemInfo: () => GlassModule.getSystemInfo(),

  isBluetoothEnabled: () => GlassModule.isBluetoothEnabled(),

  startScan: () => GlassModule.startScan(),

  stopScan: () => GlassModule.stopScan(),

  takePhoto: (id?: number, filename?: string) =>
    GlassModule.takePhoto(id, filename),

  takeAiImage: () => GlassModule.takeAiImage(),

  startVideo: (id?: number, filename?: string) =>
    GlassModule.startVideo(id, filename),

  stopVideo: () => GlassModule.stopVideo(),

  startAudio: (filename?: string) => GlassModule.startAudio(filename),

  stopAudio: () => GlassModule.stopAudio(),

  getMediaDirectory: () => GlassModule.getMediaDirectory(),

  getMediaConfig: () => GlassModule.getMediaConfig(),

  setMediaConfig: (config: PartialMediaConfig) =>
    GlassModule.setMediaConfig(config),

  syncFiles: () => GlassModule.syncFiles(),

  cancelSync: () => GlassModule.cancelSync(),

  setDeviceName: (name: string) => GlassModule.setDeviceName(name),

  clearMedia: () => GlassModule.clearMedia(),

  reboot: () => GlassModule.reboot(),

  factoryReset: () => GlassModule.factoryReset(),

  setAiStatus: (status: GlassAiStatus) => GlassModule.setAiStatus(status),

  startStream: ({ ssid, pwd, url, streamKey, fps = 20, bitrate = 800 }) =>
    GlassModule.startStream(ssid, pwd, url, streamKey ?? null, fps, bitrate),

  stopStream: () => GlassModule.stopStream(),

  startLiveStream: () => GlassModule.startLiveStream(),

  startLiveStreamWithWifi: (ssid: string, password: string) =>
    GlassModule.startLiveStreamWithWifi(ssid, password),

  stopLiveStream: () => GlassModule.stopLiveStream(),

  isStreamingActive: (): Promise<boolean> => GlassModule.isStreamingActive(),

  startOtaUpdate: (filePath: string) => GlassModule.startOtaUpdate(filePath),

  startWifiOtaUpdate: (url: string) => GlassModule.startWifiOtaUpdate(url),

  setWakeWord: (enabled: boolean) =>
    GlassModule.sendCommand("setVoiceWake", { enabled }),

  getWakeWord: () => GlassModule.sendCommand("getVoiceWake", {}),
};

/**
 * Subscribe to glass events
 * @param eventName Event name from GlassEvents
 * @param handler Event handler function
 * @returns Subscription that can be removed
 */
export function subscribeToGlassEvents(
  eventName: string,
  handler: (data: any) => void,
): Subscription {
  return GlassModule.addListener(eventName, handler);
}

/**
 * Type-safe event subscription helpers
 */
export const GlassEventSubscriptions = {
  onDeviceFound: (handler: (event: DeviceFoundEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DEVICE_FOUND, (raw: any) =>
      handler({
        ...raw,
        advertisement: parseAdvertisement(raw?.advertisement),
      }),
    ),

  onStateChanged: (handler: (event: StateChangedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.STATE_CHANGED, handler),

  onConnected: (handler: (event: ConnectedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.CONNECTED, handler),

  onDisconnected: (handler: (event: DisconnectedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.DISCONNECTED, handler),

  onBattery: (handler: (event: BatteryEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.BATTERY, handler),

  onReady: (handler: () => void) =>
    subscribeToGlassEvents(GlassEvents.READY, handler),

  onError: (handler: (event: ErrorEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.ERROR, handler),

  onSyncStarted: (handler: () => void) =>
    subscribeToGlassEvents(GlassEvents.SYNC_STARTED, handler),

  onSyncProgress: (handler: (event: SyncProgressEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.SYNC_PROGRESS, handler),

  onSyncCompleted: (handler: (event: SyncCompletedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.SYNC_COMPLETED, handler),

  onFileSynced: (handler: (event: FileSyncedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.SYNC_FILE, handler),

  onWearStatus: (handler: (event: WearStatusEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.WEAR_STATUS, handler),

  onBtStatus: (handler: (event: BtStatusEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.BT_STATUS, handler),

  onStreamStatus: (handler: (event: StreamStatusEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.STREAM_STATUS, handler),

  onOtaProgress: (handler: (event: OtaProgressEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_PROGRESS, handler),

  onOtaCompleted: (handler: (event: OtaCompletedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_COMPLETED, handler),

  onOtaLinuxCompleted: (handler: (event: OtaCompletedEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_LINUX_COMPLETED, handler),

  onOtaError: (handler: (event: ErrorEvent) => void) =>
    subscribeToGlassEvents(GlassEvents.OTA_ERROR, handler),
};

export default Glass;
