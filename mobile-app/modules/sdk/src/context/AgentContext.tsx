import AsyncStorage from "@react-native-async-storage/async-storage";
import * as Sentry from "@sentry/react-native";
import React, {
  createContext,
  ReactNode,
  useCallback,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Agent, {
  AgentEvents,
  AgentState,
  subscribeToAgentEvents,
  type AgentErrorEvent,
  type AgentParticipantEvent,
  type AgentTextEvent,
} from "../native/Agent";

// ============================================================================
// TYPE DEFINITIONS
// ============================================================================

export interface AgentParticipant {
  id: string;
  identity: string;
  name: string;
  isSpeaking: boolean;
  isAgent: boolean;
}

export interface AgentTranscript {
  id: string;
  text: string;
  isFinal: boolean;
  isUser: boolean;
  timestamp: number;
  segmentId?: string;
}

export interface AgentContextState {
  // Connection State
  connectionState: AgentState;
  roomId: string | null;

  // Audio State
  isMicEnabled: boolean;
  isSpeakerEnabled: boolean;
  isAudioEnabled: boolean;

  // Participants
  participants: AgentParticipant[];
  agentParticipant: AgentParticipant | null;
  isAgentJoined: boolean;

  // Transcripts
  transcripts: AgentTranscript[];

  // Error State
  error: string | null;
}

export interface AgentActions {
  // Connection
  start: (audio?: boolean) => Promise<void>;
  stop: () => Promise<void>;

  // Audio Control
  setMicEnabled: (enabled: boolean) => Promise<void>;
  setSpeakerEnabled: (enabled: boolean) => Promise<void>;
  toggleAudio: () => Promise<void>;
  setAudioEnabled: (enabled: boolean) => Promise<void>;

  // Messaging
  sendText: (text: string) => Promise<void>;
  sendFile: (path: string) => Promise<void>;
  sendFiles: (urls: string[], message: string) => Promise<void>;

  // App Control
  startApp: (appId: string) => Promise<void>;

  // State Management
  clearError: () => void;
  clearTranscripts: () => void;
}

export interface EventSubscribers {
  onStateChanged: (handler: (state: AgentState) => void) => () => void;
  onParticipantJoined: (
    handler: (participant: AgentParticipant) => void,
  ) => () => void;
  onParticipantLeft: (
    handler: (participant: AgentParticipant) => void,
  ) => () => void;
  onTranscript: (handler: (transcript: AgentTranscript) => void) => () => void;
  onError: (handler: (error: string) => void) => () => void;
}

export interface AgentContextValue {
  state: AgentContextState;
  actions: AgentActions;
  subscribe: EventSubscribers;
}

// ============================================================================
// EXTERNAL STORE (bypasses React Context re-rendering)
// ============================================================================

export interface AgentStore {
  getState: () => AgentContextState;
  setState: (
    partial:
      | Partial<AgentContextState>
      | ((prev: AgentContextState) => Partial<AgentContextState>),
  ) => void;
  subscribe: (listener: () => void) => () => void;
}

function createAgentStore(initial: AgentContextState): AgentStore {
  let state = initial;
  const listeners = new Set<() => void>();

  return {
    getState: () => state,
    setState: (partial) => {
      const next = typeof partial === "function" ? partial(state) : partial;
      const merged = { ...state, ...next };

      // Only notify if something actually changed
      let changed = false;
      for (const key of Object.keys(next) as (keyof AgentContextState)[]) {
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
// CONTEXT CREATION
// ============================================================================

const AgentStoreContext = createContext<AgentStore | null>(null);
const AgentActionsContext = createContext<{
  actions: AgentActions;
  subscribe: EventSubscribers;
} | null>(null);

// ============================================================================
// INITIAL STATE
// ============================================================================

const initialState: AgentContextState = {
  connectionState: AgentState.DISCONNECTED,
  roomId: null,
  isMicEnabled: true,
  isSpeakerEnabled: true,
  isAudioEnabled: true,
  participants: [],
  agentParticipant: null,
  isAgentJoined: false,
  transcripts: [],
  error: null,
};

// ============================================================================
// PROVIDER COMPONENT
// ============================================================================

export const AgentProvider: React.FC<{ children: ReactNode }> = ({
  children,
}) => {
  const storeRef = useRef(createAgentStore(initialState));
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
        console.log("[AgentContext] Fetching initial state...");

        const [connectionState, isMicEnabled, isSpeakerEnabled, roomId] =
          await Promise.allSettled([
            Agent.getConnectionState(),
            Agent.getMicEnabled(),
            Agent.getSpeakerEnabled(),
            Agent.getRoomId(),
          ]);

        store.setState({
          connectionState:
            connectionState.status === "fulfilled"
              ? connectionState.value
              : AgentState.DISCONNECTED,
          isMicEnabled:
            isMicEnabled.status === "fulfilled" ? isMicEnabled.value : true,
          isSpeakerEnabled:
            isSpeakerEnabled.status === "fulfilled"
              ? isSpeakerEnabled.value
              : true,
          roomId: roomId.status === "fulfilled" ? roomId.value : null,
        });

        console.log("[AgentContext] Initial state loaded");
        setIsInitialized(true);
      } catch (error) {
        console.error("[AgentContext] Error fetching initial state:", error);
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
      // State Changed
      subscribeToAgentEvents(AgentEvents.STATE_CHANGED, (event: any) => {
        console.log("[AgentContext] State changed:", event.state);
        Sentry.logger.info("Agent state changed", { state: event.state });
        store.setState({ connectionState: event.state });

        // Clear room ID when disconnected
        if (event.state === AgentState.DISCONNECTED) {
          store.setState({
            roomId: null,
            participants: [],
            agentParticipant: null,
            isAgentJoined: false,
          });
        }
      }),

      // Participant Events
      subscribeToAgentEvents(
        AgentEvents.PARTICIPANT_EVENT,
        (event: AgentParticipantEvent) => {
          const participant: AgentParticipant = {
            id: event.participantId,
            identity: event.identity,
            name: event.name,
            isSpeaking: event.isSpeaking,
            isAgent: event.isAgent || false,
          };

          if (event.type === "joined") {
            store.setState((prev) => {
              const exists = prev.participants.some(
                (p) => p.id === participant.id,
              );
              return {
                participants: exists
                  ? prev.participants.map((p) =>
                      p.id === participant.id ? participant : p,
                    )
                  : [...prev.participants, participant],
                agentParticipant: participant.isAgent
                  ? participant
                  : prev.agentParticipant,
                isAgentJoined: participant.isAgent ? true : prev.isAgentJoined,
              };
            });
            Sentry.logger.info("Participant joined", {
              id: participant.id,
              identity: participant.identity,
              name: participant.name,
              isAgent: participant.isAgent,
            });
          } else if (event.type === "left") {
            store.setState((prev) => ({
              participants: prev.participants.filter(
                (p) => p.id !== participant.id,
              ),
              agentParticipant:
                prev.agentParticipant?.id === participant.id
                  ? null
                  : prev.agentParticipant,
              isAgentJoined:
                prev.agentParticipant?.id === participant.id
                  ? false
                  : prev.isAgentJoined,
            }));
            Sentry.logger.info("Participant left", {
              id: participant.id,
              identity: participant.identity,
              name: participant.name,
              isAgent: participant.isAgent,
            });
          } else if (event.type === "speaking") {
            store.setState((prev) => ({
              participants: prev.participants.map((p) =>
                p.id === participant.id ? { ...p, isSpeaking: true } : p,
              ),
            }));
          }
        },
      ),

      // Audio Track Events
      subscribeToAgentEvents(AgentEvents.AUDIO_TRACK_EVENT, (event: any) => {
        if (event.type === "muted" && event.isLocal) {
          store.setState({ isMicEnabled: false });
        } else if (event.type === "unmuted" && event.isLocal) {
          store.setState({ isMicEnabled: true });
        } else if (event.type === "speaker_disabled") {
          store.setState({ isSpeakerEnabled: false });
        } else if (event.type === "speaker_enabled") {
          store.setState({ isSpeakerEnabled: true });
        }
      }),

      // Text Events (Transcripts)
      subscribeToAgentEvents(
        AgentEvents.TEXT_EVENT,
        (event: AgentTextEvent) => {
          const transcript: AgentTranscript = {
            id: event.id,
            text: event.text,
            isFinal: event.isFinal,
            isUser: event.isUser,
            timestamp: Date.now(),
            segmentId: event.segmentId,
          };

          store.setState((prev) => {
            // If transcript with same ID exists, update it
            const existingIndex = prev.transcripts.findIndex(
              (t) => t.id === transcript.id,
            );

            if (existingIndex >= 0) {
              const updated = [...prev.transcripts];
              updated[existingIndex] = transcript;
              return { transcripts: updated };
            }

            // Otherwise add new transcript
            return { transcripts: [...prev.transcripts, transcript] };
          });
        },
      ),

      // Error Events
      subscribeToAgentEvents(AgentEvents.ERROR, (event: AgentErrorEvent) => {
        console.error("[AgentContext] Error:", event.code, event.message);
        Sentry.logger.error("Agent error", { code: event.code, message: event.message });
        store.setState({ error: `${event.code}: ${event.message}` });
      }),
    ];

    subscriptionsRef.current = subs;
    return () => subs.forEach((sub) => sub.remove());
  }, []);

  // ============================================================================
  // ACTIONS
  // ============================================================================

  const start = useCallback(async (audio: boolean = true) => {
    try {
      store.setState({ error: null });

      const agentName = await AsyncStorage.getItem("@agent_name");
      await Agent.setAgentName(agentName);
      await Agent.start(audio);

      // Fetch room ID after starting
      const roomId = await Agent.getRoomId();
      store.setState({ roomId });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to start agent";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const stop = useCallback(async () => {
    try {
      await Agent.stop();
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to stop agent";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const setMicEnabled = useCallback(async (enabled: boolean) => {
    try {
      await Agent.setMicEnabled(enabled);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to set mic";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const setSpeakerEnabled = useCallback(async (enabled: boolean) => {
    try {
      await Agent.setSpeakerEnabled(enabled);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to set speaker";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const toggleAudio = useCallback(async () => {
    try {
      await Agent.toggleAudio();
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to toggle audio";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const setAudioEnabled = useCallback(async (enabled: boolean) => {
    try {
      await Agent.setAudioEnabled(enabled);
      store.setState({ isAudioEnabled: enabled });
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to set audio";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const sendText = useCallback(async (text: string) => {
    try {
      await Agent.sendText(text);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to send text";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const sendFile = useCallback(async (path: string) => {
    try {
      await Agent.sendFile(path);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to send file";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const sendFiles = useCallback(async (urls: string[], message: string) => {
    try {
      await Agent.sendFiles(urls, message);
    } catch (error) {
      const message_ =
        error instanceof Error ? error.message : "Failed to send files";
      store.setState({ error: message_ });
      throw error;
    }
  }, []);

  const startApp = useCallback(async (appId: string) => {
    try {
      await Agent.startApp(appId);
    } catch (error) {
      const message =
        error instanceof Error ? error.message : "Failed to start app";
      store.setState({ error: message });
      throw error;
    }
  }, []);

  const clearError = useCallback(() => {
    store.setState({ error: null });
  }, []);

  const clearTranscripts = useCallback(() => {
    store.setState({ transcripts: [] });
  }, []);

  const actions: AgentActions = useMemo(
    () => ({
      start,
      stop,
      setMicEnabled,
      setSpeakerEnabled,
      toggleAudio,
      setAudioEnabled,
      sendText,
      sendFile,
      sendFiles,
      startApp,
      clearError,
      clearTranscripts,
    }),
    [
      start,
      stop,
      setMicEnabled,
      setSpeakerEnabled,
      toggleAudio,
      setAudioEnabled,
      sendText,
      sendFile,
      sendFiles,
      startApp,
      clearError,
      clearTranscripts,
    ],
  );

  // ============================================================================
  // EVENT SUBSCRIBERS (External - for components)
  // ============================================================================

  const onStateChanged = useCallback((handler: (state: AgentState) => void) => {
    const sub = subscribeToAgentEvents(
      AgentEvents.STATE_CHANGED,
      (event: any) => {
        handler(event.state);
      },
    );
    return () => sub.remove();
  }, []);

  const onParticipantJoined = useCallback(
    (handler: (participant: AgentParticipant) => void) => {
      const sub = subscribeToAgentEvents(
        AgentEvents.PARTICIPANT_EVENT,
        (event: AgentParticipantEvent) => {
          if (event.type === "joined") {
            handler({
              id: event.participantId,
              identity: event.identity,
              name: event.name,
              isSpeaking: event.isSpeaking,
              isAgent: event.isAgent || false,
            });
          }
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onParticipantLeft = useCallback(
    (handler: (participant: AgentParticipant) => void) => {
      const sub = subscribeToAgentEvents(
        AgentEvents.PARTICIPANT_EVENT,
        (event: AgentParticipantEvent) => {
          if (event.type === "left") {
            handler({
              id: event.participantId,
              identity: event.identity,
              name: event.name,
              isSpeaking: event.isSpeaking,
              isAgent: event.isAgent || false,
            });
          }
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onTranscript = useCallback(
    (handler: (transcript: AgentTranscript) => void) => {
      const sub = subscribeToAgentEvents(
        AgentEvents.TEXT_EVENT,
        (event: AgentTextEvent) => {
          handler({
            id: event.id,
            text: event.text,
            isFinal: event.isFinal,
            isUser: event.isUser,
            timestamp: Date.now(),
            segmentId: event.segmentId,
          });
        },
      );
      return () => sub.remove();
    },
    [],
  );

  const onError = useCallback((handler: (error: string) => void) => {
    const sub = subscribeToAgentEvents(
      AgentEvents.ERROR,
      (event: AgentErrorEvent) => {
        handler(`${event.code}: ${event.message}`);
      },
    );
    return () => sub.remove();
  }, []);

  const subscribe: EventSubscribers = useMemo(
    () => ({
      onStateChanged,
      onParticipantJoined,
      onParticipantLeft,
      onTranscript,
      onError,
    }),
    [
      onStateChanged,
      onParticipantJoined,
      onParticipantLeft,
      onTranscript,
      onError,
    ],
  );

  // Stable actions+subscribe value
  const actionsValue = useMemo(
    () => ({ actions, subscribe }),
    [actions, subscribe],
  );

  return (
    <AgentActionsContext.Provider value={actionsValue}>
      <AgentStoreContext.Provider value={store}>
        {children}
      </AgentStoreContext.Provider>
    </AgentActionsContext.Provider>
  );
};

export { AgentActionsContext, AgentStoreContext };
