import { useEffect, useRef } from 'react';
import { useGlassActions } from './useGlass';
import type { EventSubscribers } from '../context/GlassContext';

type EventHandler<T = any> = (data: T) => void;

/**
 * Hook to subscribe to Glass events
 * Automatically handles cleanup on unmount
 *
 * @example
 * useGlassEvent('onDeviceFound', (device) => {
 *   console.log('Found device:', device);
 * });
 */
export function useGlassEvent<K extends keyof EventSubscribers>(
  eventName: K,
  handler: Parameters<EventSubscribers[K]>[0],
  deps: React.DependencyList = []
): void {
  const { subscribe } = useGlassActions();
  const handlerRef = useRef(handler);

  // Keep handler ref up to date
  useEffect(() => {
    handlerRef.current = handler;
  });

  useEffect(() => {
    const subscribeFn = subscribe[eventName];
    const unsubscribe = subscribeFn(((...args: any[]) => {
      handlerRef.current(...args);
    }) as any);

    return unsubscribe;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [eventName, subscribe, ...deps]);
}
