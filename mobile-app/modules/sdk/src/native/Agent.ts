import { requireNativeModule } from "expo-modules-core";

const AgentModule = requireNativeModule("AgentModule");

type Subscription = { remove: () => void };

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

export enum AgentState {
  DISCONNECTED = "DISCONNECTED",
  CONNECTING = "CONNECTING",
  CONNECTED = "CONNECTED",
  RECONNECTING = "RECONNECTING",
  FAILED = "FAILED",
}

export const AgentEvents = {
  STATE_CHANGED: "AGENT_STATE_CHANGED",
  PARTICIPANT_EVENT: "AGENT_PARTICIPANT_EVENT",
  AUDIO_TRACK_EVENT: "AGENT_AUDIO_TRACK_EVENT",
  TEXT_EVENT: "AGENT_TEXT_EVENT",
  DATA_EVENT: "AGENT_DATA_EVENT",
  ERROR: "AGENT_ERROR",
  TOOL_CALL: "AGENT_TOOL_CALL",
  TOOL_RESULT: "AGENT_TOOL_RESULT",
  ACTIVE_SPEAKERS: "AGENT_ACTIVE_SPEAKERS",
  TOOL_EXECUTED: "AGENT_TOOL_EXECUTED",
  TOOL_CALLING: "AGENT_TOOL_CALLING",
  RPC_METHOD_CALLED: "AGENT_RPC_METHOD_CALLED",
} as const;

export interface AgentStateEvent {
  state: AgentState;
}

export interface AgentParticipantEvent {
  type: "joined" | "left" | "speaking" | "stopped_speaking";
  participantId: string;
  identity: string;
  name: string;
  isSpeaking: boolean;
  isAgent?: boolean;
}

export interface AgentAudioTrackEvent {
  type:
    | "subscribed"
    | "unsubscribed"
    | "muted"
    | "unmuted"
    | "speaker_enabled"
    | "speaker_disabled";
  participantId: string;
  trackId: string;
  isLocal: boolean;
  isMuted: boolean;
}

export interface AgentTextEvent {
  id: string;
  text: string;
  isFinal: boolean;
  isUser: boolean;
  isTranscription: boolean;
  segmentId?: string;
}

export interface AgentDataEvent {
  participantId: string;
  data: string; // Base64 encoded
  topic?: string;
}

export interface AgentErrorEvent {
  code: string;
  message: string;
}

export interface AgentToolCallEvent {
  toolName: string;
  params: string; // JSON string
}

export interface AgentToolResultEvent {
  toolName: string;
  success: boolean;
  result: string; // JSON string
}

export interface AgentToolCallingEvent {
  type: "tool_calling";
  toolName: string;
  verb: string | null;
  input: Record<string, any>;
  status: "calling";
}

export interface AgentToolExecutedEvent {
  type: "tool_executed";
  toolName: string;
  mcpName: string;
  mcpImage?: string;
  input: Record<string, any>;
  output: Record<string, any>;
  status: "success" | "error";
  message?: string;
}

export interface AgentActiveSpeaker {
  participantId: string;
  identity: string;
  name: string;
  audioLevel: number; // 0.0 - 1.0
  isAgent: boolean;
}

export interface AgentActiveSpeakersEvent {
  speakers: AgentActiveSpeaker[]; // ordered by audioLevel desc, empty = silence
}

export interface AgentRpcMethodCalledEvent {
  method: string;
  payload: string;
}

// ============================================================================
// API INTERFACE
// ============================================================================

export interface AgentAPI {
  start(audio?: boolean): Promise<void>;
  stop(): Promise<void>;
  sendText(text: string): Promise<void>;
  sendFile(path: string): Promise<void>;
  sendData(data: Uint8Array, reliable?: boolean): Promise<void>;
  setMicEnabled(enabled: boolean): Promise<void>;
  setSpeakerEnabled(enabled: boolean): Promise<void>;
  toggleAudio(): Promise<void>;
  setAudioEnabled(enabled: boolean): Promise<void>;
  getConnectionState(): Promise<AgentState>;
  getMicEnabled(): Promise<boolean>;
  getSpeakerEnabled(): Promise<boolean>;
  getRoomId(): Promise<string | null>;
  startApp(appId: string): Promise<void>;
  reloadTools(): Promise<void>;
  performRpc(method: string, payload: string): Promise<string>;
  setCookie(cookie: string): Promise<boolean>;
  sendFiles(urls: string[], message: string): Promise<void>;
  setAgentName(name: string | null): Promise<void>;

  /** Publish phone camera as a LiveKit LocalVideoTrack in the current room. */
  startPhoneStream(fps?: number, bitrate?: number): Promise<void>;
  /** Unpublish phone camera track and stop capture. */
  stopPhoneStream(): Promise<void>;
  /** Toggle front/back camera on the phone. */
  flipPhoneCamera(): Promise<void>;
  /** Returns true if phone camera is currently streaming. */
  isPhoneStreaming(): Promise<boolean>;

  /** Fetch WHIP credentials from backend and start glass WHIP stream over provided WiFi. */
  startGlassStream(
    ssid: string,
    pwd: string,
    fps?: number,
    bitrate?: number,
  ): Promise<void>;
  /** Stop the WHIP stream on glass. */
  stopGlassStream(): Promise<void>;
  /** Returns true if a glass WHIP stream is currently active. */
  isGlassStreaming(): Promise<boolean>;

  /** Register a LiveKit RPC method handler from JS. Fires AGENT_RPC_METHOD_CALLED events when invoked. */
  registerRpcMethod(methodName: string): Promise<void>;
}

// ============================================================================
// API METHODS
// ============================================================================

export const Agent: AgentAPI = {
  /**
   * Start the voice agent
   * @param audio - Enable audio (mic and speaker)
   */
  start(audio: boolean = true): Promise<void> {
    return AgentModule.start(audio);
  },

  /**
   * Stop the voice agent
   */
  stop(): Promise<void> {
    return AgentModule.stop();
  },

  /**
   * Send text message to agent
   */
  sendText(text: string): Promise<void> {
    return AgentModule.sendText(text);
  },

  /**
   * Send file to agent
   */
  sendFile(path: string): Promise<void> {
    return AgentModule.sendFile(path);
  },

  /**
   * Send binary data to agent
   */
  sendData(data: Uint8Array, reliable: boolean = true): Promise<void> {
    return AgentModule.sendData(data, reliable);
  },

  /**
   * Enable/disable microphone
   */
  setMicEnabled(enabled: boolean): Promise<void> {
    return AgentModule.setMicEnabled(enabled);
  },

  /**
   * Enable/disable speaker
   */
  setSpeakerEnabled(enabled: boolean): Promise<void> {
    return AgentModule.setSpeakerEnabled(enabled);
  },

  /**
   * Toggle audio (mic and agent audio)
   */
  toggleAudio(): Promise<void> {
    return AgentModule.toggleAudio();
  },

  /**
   * Set audio enabled/disabled
   */
  setAudioEnabled(enabled: boolean): Promise<void> {
    return AgentModule.setAudioEnabled(enabled);
  },

  /**
   * Get current connection state
   */
  getConnectionState(): Promise<AgentState> {
    return AgentModule.getConnectionState();
  },

  /**
   * Get microphone enabled state
   */
  getMicEnabled(): Promise<boolean> {
    return AgentModule.getMicEnabled();
  },

  /**
   * Get speaker enabled state
   */
  getSpeakerEnabled(): Promise<boolean> {
    return AgentModule.getSpeakerEnabled();
  },

  /**
   * Get room ID
   */
  getRoomId(): Promise<string | null> {
    return AgentModule.getRoomId();
  },

  /**
   * Start an app session
   */
  startApp: (appId: string) => AgentModule.startApp(appId),

  reloadTools: (): Promise<void> => AgentModule.reloadTools(),

  performRpc(method: string, payload: string): Promise<string> {
    return AgentModule.performRpc(method, payload);
  },

  /**
   * Set authentication cookie
   */
  setCookie: (cookie: string) => AgentModule.setCookie(cookie),

  sendFiles(urls: string[], message: string): Promise<void> {
    return AgentModule.sendFiles(urls, message);
  },

  setAgentName(name: string | null): Promise<void> {
    return AgentModule.setAgentName(name ?? "");
  },

  startPhoneStream(fps = 25, bitrate = 3000): Promise<void> {
    return AgentModule.startPhoneStream(fps, bitrate);
  },

  stopPhoneStream(): Promise<void> {
    return AgentModule.stopPhoneStream();
  },

  flipPhoneCamera(): Promise<void> {
    return AgentModule.flipPhoneCamera();
  },

  isPhoneStreaming(): Promise<boolean> {
    return AgentModule.isPhoneStreaming();
  },

  startGlassStream(
    ssid: string,
    pwd: string,
    fps = 20,
    bitrate = 800,
  ): Promise<void> {
    return AgentModule.startGlassStream(ssid, pwd, fps, bitrate);
  },

  stopGlassStream(): Promise<void> {
    return AgentModule.stopGlassStream();
  },

  isGlassStreaming(): Promise<boolean> {
    return AgentModule.isGlassStreaming();
  },

  registerRpcMethod(methodName: string): Promise<void> {
    return AgentModule.registerRpcMethod(methodName);
  },
};

/**
 * Subscribe to agent events
 * @param eventName Event name from AgentEvents
 * @param handler Event handler function
 * @returns Subscription that can be removed
 */
export function subscribeToAgentEvents(
  eventName: string,
  handler: (data: any) => void,
): Subscription {
  return AgentModule.addListener(eventName, handler);
}

/**
 * Type-safe event subscription helpers
 */
export const AgentEventSubscriptions = {
  onStateChanged: (handler: (event: AgentStateEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.STATE_CHANGED, handler),

  onParticipantEvent: (handler: (event: AgentParticipantEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.PARTICIPANT_EVENT, handler),

  onAudioTrackEvent: (handler: (event: AgentAudioTrackEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.AUDIO_TRACK_EVENT, handler),

  onTextEvent: (handler: (event: AgentTextEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.TEXT_EVENT, handler),

  onDataEvent: (handler: (event: AgentDataEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.DATA_EVENT, handler),

  onError: (handler: (event: AgentErrorEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.ERROR, handler),

  onToolCall: (handler: (event: AgentToolCallEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.TOOL_CALL, handler),

  onToolResult: (handler: (event: AgentToolResultEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.TOOL_RESULT, handler),

  onActiveSpeakers: (handler: (event: AgentActiveSpeakersEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.ACTIVE_SPEAKERS, (raw: any) => {
      const speakers: AgentActiveSpeaker[] = JSON.parse(raw.speakers || "[]");
      handler({ speakers });
    }),

  onToolCalling: (handler: (event: AgentToolCallingEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.TOOL_CALLING, (raw: any) => {
      const parsed =
        typeof raw.data === "string"
          ? JSON.parse(raw.data || "{}")
          : (raw.data ?? {});
      handler(parsed as AgentToolCallingEvent);
    }),

  onToolExecuted: (handler: (event: AgentToolExecutedEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.TOOL_EXECUTED, (raw: any) => {
      const parsed =
        typeof raw.data === "string"
          ? JSON.parse(raw.data || "{}")
          : (raw.data ?? {});
      handler(parsed as AgentToolExecutedEvent);
    }),

  onRpcMethodCalled: (handler: (event: AgentRpcMethodCalledEvent) => void) =>
    subscribeToAgentEvents(AgentEvents.RPC_METHOD_CALLED, handler),
};

export default Agent;
