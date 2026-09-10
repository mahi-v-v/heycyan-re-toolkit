import { useCallback, useContext, useMemo, useRef, useSyncExternalStore } from "react";
import {
  GlassStoreContext,
  GlassActionsContext,
  type EventSubscribers,
  type GlassActions,
  type GlassState,
  type GlassStore,
} from "../context/GlassContext";

// ============================================================================
// INTERNAL: Get store from context (throws if outside provider)
// ============================================================================

function useGlassStore(): GlassStore {
  const store = useContext(GlassStoreContext);
  if (!store) {
    throw new Error("useGlassStore must be used within a GlassProvider");
  }
  return store;
}

// ============================================================================
// useGlassSelector — Primary hook for reading state
// ============================================================================

/**
 * Subscribe to specific state slices from Glass context.
 * Only re-renders when the selected value actually changes.
 *
 * Uses useSyncExternalStore — bypasses React Context re-rendering entirely.
 *
 * @example
 * // Single primitive value
 * const isConnected = useGlassSelector(s => s.connectionState === "CONNECTED");
 *
 * // Single field
 * const batteryLevel = useGlassSelector(s => s.batteryLevel);
 *
 * // Multiple fields (use shallowEqual)
 * const { storage, systemInfo } = useGlassSelector(
 *   s => ({ storage: s.storage, systemInfo: s.systemInfo }),
 *   shallowEqual
 * );
 */
export function useGlassSelector<T>(
  selector: (state: GlassState) => T,
  equalityFn: (a: T, b: T) => boolean = Object.is,
): T {
  const store = useGlassStore();
  const prevRef = useRef<{ value: T } | null>(null);
  const selectorRef = useRef(selector);
  const equalityFnRef = useRef(equalityFn);
  selectorRef.current = selector;
  equalityFnRef.current = equalityFn;

  const getSnapshot = useCallback((): T => {
    const nextValue = selectorRef.current(store.getState());
    if (
      prevRef.current !== null &&
      equalityFnRef.current(prevRef.current.value, nextValue)
    ) {
      return prevRef.current.value;
    }
    prevRef.current = { value: nextValue };
    return nextValue;
  }, [store]);

  return useSyncExternalStore(store.subscribe, getSnapshot);
}

// ============================================================================
// useGlassActions — Stable actions (no re-renders)
// ============================================================================

/**
 * Hook to access only Glass actions (stable — never causes re-renders).
 * Use this when a component only needs to call actions, not read state.
 *
 * @example
 * const { takePhoto, refreshStorageInfo } = useGlassActions();
 */
export function useGlassActions(): GlassActions & {
  subscribe: EventSubscribers;
} {
  const actionsCtx = useContext(GlassActionsContext);

  if (!actionsCtx) {
    throw new Error("useGlassActions must be used within a GlassProvider");
  }

  return useMemo(
    () => ({ ...actionsCtx.actions, subscribe: actionsCtx.subscribe }),
    [actionsCtx],
  );
}

// ============================================================================
// useGlass — Convenience hook (re-renders on ANY state change)
// ============================================================================

export interface UseGlassReturn extends GlassState, GlassActions {
  isConnected: boolean;
  isScanning: boolean;
  subscribe: EventSubscribers;
}

/**
 * Hook to access Glass context with flattened state and actions.
 * Must be used within a GlassProvider.
 *
 * WARNING: This hook re-renders on ANY state change. For better performance,
 * use useGlassSelector() to subscribe to specific state slices, and
 * useGlassActions() for actions.
 *
 * @example
 * // Prefer this:
 * const isConnected = useGlassSelector(s => s.connectionState === "CONNECTED");
 * const { connect } = useGlassActions();
 *
 * // Instead of this (re-renders on every state change):
 * const { isConnected, connect } = useGlass();
 */
export function useGlass(): UseGlassReturn {
  const store = useGlassStore();
  const actionsCtx = useContext(GlassActionsContext);

  if (!actionsCtx) {
    throw new Error("useGlass must be used within a GlassProvider");
  }

  const state = useSyncExternalStore(
    store.subscribe,
    () => store.getState(),
  );

  const { actions, subscribe } = actionsCtx;

  return useMemo(
    () => ({
      ...state,
      ...actions,
      isConnected: state.connectionState === "CONNECTED",
      isScanning: state.connectionState === "SCANNING",
      subscribe,
    }),
    [state, actions, subscribe],
  );
}

// ============================================================================
// Utility: shallowEqual
// ============================================================================

/**
 * Shallow equality check for use with useGlassSelector when selecting objects.
 *
 * @example
 * const { storage, systemInfo } = useGlassSelector(
 *   s => ({ storage: s.storage, systemInfo: s.systemInfo }),
 *   shallowEqual
 * );
 */
export function shallowEqual(a: any, b: any): boolean {
  if (Object.is(a, b)) return true;
  if (
    typeof a !== "object" ||
    typeof b !== "object" ||
    a === null ||
    b === null
  ) {
    return false;
  }
  const keysA = Object.keys(a);
  const keysB = Object.keys(b);
  if (keysA.length !== keysB.length) return false;
  for (const key of keysA) {
    if (!Object.is(a[key], b[key])) return false;
  }
  return true;
}
