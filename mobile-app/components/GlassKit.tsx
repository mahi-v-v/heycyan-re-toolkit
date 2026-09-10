import React, { useEffect, useReducer, useState } from 'react';
import {
  ActivityIndicator,
  ScrollView,
  StyleSheet,
  Text,
  TouchableOpacity,
  View,
} from 'react-native';
import Glass, {
  GlassEventSubscriptions,
  GlassConnectionState,
} from '@/modules/sdk/src/native/Glass';

/** True while a Cyan device is CONNECTED. Keeps itself in sync via state-change events. */
export function useConnected(): boolean {
  const [state, setState] = useState<GlassConnectionState>(GlassConnectionState.IDLE);
  useEffect(() => {
    Glass.getCurrentState().then(setState).catch(() => {});
    const sub = GlassEventSubscriptions.onStateChanged((e) => setState(e.newState));
    return () => sub.remove();
  }, []);
  return state === GlassConnectionState.CONNECTED;
}

export interface LogLine {
  ts: number;
  text: string;
}

/**
 * Shared GLOBAL device-trace log. A SINGLE onError listener (started once via ensureGlassLogging)
 * feeds one rolling buffer that every screen renders — so [CMD]/[NOTIFY]/[CAP]/[PROBE]/… traces
 * show on ALL tabs — and every trace is also mirrored to the Metro JS console as `[GLASS] …`.
 */
let _lines: LogLine[] = [];
const _subs = new Set<() => void>();
let _loggingStarted = false;

/** Start the single global trace listener. Idempotent — safe to call from anywhere. */
export function ensureGlassLogging(): void {
  if (_loggingStarted) return;
  _loggingStarted = true;
  GlassEventSubscriptions.onError((e: any) => {
    const text = typeof e?.error === 'string' ? e.error : JSON.stringify(e);
    console.log('[GLASS]', text);
    _lines = [..._lines, { ts: Date.now(), text }];
    if (_lines.length > 500) _lines = _lines.slice(_lines.length - 500);
    _subs.forEach((fn) => fn());
  });
}

export function useDeviceLog(): { lines: LogLine[]; clear: () => void } {
  ensureGlassLogging();
  const [, bump] = useReducer((x: number) => x + 1, 0);
  useEffect(() => {
    _subs.add(bump);
    return () => {
      _subs.delete(bump);
    };
  }, []);
  return {
    lines: _lines,
    clear: () => {
      _lines = [];
      _subs.forEach((fn) => fn());
    },
  };
}

export function NotConnected() {
  return (
    <View style={k.notice}>
      <Text style={k.noticeText}>
        No glasses connected. Open the Connect tab and pair a device first.
      </Text>
    </View>
  );
}

export function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <View style={k.section}>
      <Text style={k.sectionTitle}>{title}</Text>
      {children}
    </View>
  );
}

export function Btn({
  title,
  onPress,
  color = '#457b9d',
  disabled = false,
  busy = false,
}: {
  title: string;
  onPress: () => void;
  color?: string;
  disabled?: boolean;
  busy?: boolean;
}) {
  return (
    <TouchableOpacity
      style={[k.btn, { backgroundColor: disabled ? '#adb5bd' : color }]}
      onPress={onPress}
      disabled={disabled || busy}
      activeOpacity={0.7}>
      {busy ? <ActivityIndicator color="#fff" /> : <Text style={k.btnText}>{title}</Text>}
    </TouchableOpacity>
  );
}

export function LogPanel({
  lines,
  onClear,
  title = 'Device Log',
}: {
  lines: LogLine[];
  onClear: () => void;
  title?: string;
}) {
  return (
    <View style={k.section}>
      <View style={k.logHeader}>
        <Text style={k.sectionTitle}>{title}</Text>
        <TouchableOpacity onPress={onClear}>
          <Text style={k.clear}>Clear</Text>
        </TouchableOpacity>
      </View>
      <ScrollView style={k.log} contentContainerStyle={{ padding: 8 }}>
        {lines.length === 0 && (
          <Text style={k.logEmpty}>No messages yet — trigger an action above.</Text>
        )}
        {lines.map((l, i) => (
          <Text key={i} style={k.logLine} selectable>
            {new Date(l.ts).toLocaleTimeString()}  {l.text}
          </Text>
        ))}
      </ScrollView>
    </View>
  );
}

export const k = StyleSheet.create({
  container: { flex: 1, backgroundColor: '#f8f9fa' },
  content: { padding: 16 },
  section: {
    backgroundColor: '#fff',
    padding: 15,
    borderRadius: 8,
    marginBottom: 16,
    elevation: 2,
    shadowColor: '#000',
    shadowOffset: { width: 0, height: 1 },
    shadowOpacity: 0.1,
    shadowRadius: 3,
  },
  sectionTitle: { fontSize: 17, fontWeight: 'bold', marginBottom: 10, color: '#1d3557' },
  btn: {
    paddingVertical: 12,
    paddingHorizontal: 14,
    borderRadius: 8,
    marginBottom: 8,
    alignItems: 'center',
  },
  btnText: { color: '#fff', fontSize: 15, fontWeight: '600' },
  notice: { backgroundColor: '#fff3cd', padding: 14, borderRadius: 8, margin: 16 },
  noticeText: { color: '#856404', fontSize: 14 },
  logHeader: {
    flexDirection: 'row',
    justifyContent: 'space-between',
    alignItems: 'center',
    marginBottom: 6,
  },
  clear: { color: '#d62828', fontWeight: '600' },
  log: { maxHeight: 280, backgroundColor: '#0d1b2a', borderRadius: 6 },
  logEmpty: { color: '#8d99ae', fontStyle: 'italic' },
  logLine: { color: '#a8dadc', fontSize: 11, fontFamily: 'monospace', marginBottom: 3 },
  statusText: { fontSize: 15, color: '#2b2d42', marginBottom: 5 },
  hint: { fontSize: 12, color: '#8d99ae', marginBottom: 8 },
  input: {
    borderWidth: 1,
    borderColor: '#ced4da',
    borderRadius: 6,
    padding: 10,
    marginBottom: 8,
    fontSize: 15,
    color: '#2b2d42',
    backgroundColor: '#fff',
  },
});
