import { useContext, useSyncExternalStore } from "react";
import {
  AgentStoreContext,
  AgentActionsContext,
  type AgentContextState,
} from "../context/AgentContext";

/**
 * Hook to access agent state with optimal re-rendering
 * Uses useSyncExternalStore to minimize unnecessary re-renders
 *
 * @example
 * ```tsx
 * const connectionState = useAgent(state => state.connectionState);
 * const transcripts = useAgent(state => state.transcripts);
 * const { start, stop, sendText } = useAgentActions();
 * ```
 */
export function useAgent<T>(
  selector: (state: AgentContextState) => T
): T {
  const store = useContext(AgentStoreContext);

  if (!store) {
    throw new Error("useAgent must be used within AgentProvider");
  }

  return useSyncExternalStore(
    store.subscribe,
    () => selector(store.getState()),
    () => selector(store.getState())
  );
}

/**
 * Hook to access agent actions
 * Actions are stable and don't cause re-renders
 *
 * @example
 * ```tsx
 * const { start, stop, sendText, setMicEnabled } = useAgentActions();
 *
 * // Start agent
 * await start(true);
 *
 * // Send message
 * await sendText("Hello!");
 *
 * // Control mic
 * await setMicEnabled(false);
 * ```
 */
export function useAgentActions() {
  const context = useContext(AgentActionsContext);

  if (!context) {
    throw new Error("useAgentActions must be used within AgentProvider");
  }

  return context.actions;
}

/**
 * Hook to subscribe to agent events
 * Event handlers are called in addition to state updates
 *
 * @example
 * ```tsx
 * const { onTranscript, onError } = useAgentSubscribe();
 *
 * useEffect(() => {
 *   const unsubscribe = onTranscript((transcript) => {
 *     console.log('New transcript:', transcript.text);
 *   });
 *   return unsubscribe;
 * }, []);
 * ```
 */
export function useAgentSubscribe() {
  const context = useContext(AgentActionsContext);

  if (!context) {
    throw new Error("useAgentSubscribe must be used within AgentProvider");
  }

  return context.subscribe;
}

/**
 * Convenience hook that returns full agent state
 * Use sparingly as it will re-render on any state change
 *
 * @example
 * ```tsx
 * const agentState = useAgentState();
 *
 * return (
 *   <div>
 *     <p>Status: {agentState.connectionState}</p>
 *     <p>Participants: {agentState.participants.length}</p>
 *   </div>
 * );
 * ```
 */
export function useAgentState(): AgentContextState {
  return useAgent((state) => state);
}

/**
 * Hook for common agent state selectors
 * Optimized for minimal re-renders
 */
export function useAgentSelectors() {
  return {
    connectionState: useAgent((state) => state.connectionState),
    isConnected: useAgent(
      (state) => state.connectionState === "CONNECTED"
    ),
    roomId: useAgent((state) => state.roomId),
    isMicEnabled: useAgent((state) => state.isMicEnabled),
    isSpeakerEnabled: useAgent((state) => state.isSpeakerEnabled),
    isAudioEnabled: useAgent((state) => state.isAudioEnabled),
    participants: useAgent((state) => state.participants),
    agentParticipant: useAgent((state) => state.agentParticipant),
    transcripts: useAgent((state) => state.transcripts),
    error: useAgent((state) => state.error),
  };
}
