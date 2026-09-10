import React, { useCallback, useEffect, useState } from 'react';
import { Alert, ScrollView } from 'react-native';
import Glass, { GlassEventSubscriptions } from '@/modules/sdk/src/native/Glass';
import {
  useConnected,
  useDeviceLog,
  Section,
  Btn,
  LogPanel,
  NotConnected,
  k,
} from '@/components/GlassKit';

export default function ControlsScreen() {
  const connected = useConnected();
  const { lines, clear } = useDeviceLog();
  const [worn, setWorn] = useState<string>('unknown');

  useEffect(() => {
    const sub = GlassEventSubscriptions.onWearStatus((e) => setWorn(e.worn ? 'worn' : 'not worn'));
    return () => sub.remove();
  }, []);

  const cmd = useCallback((c: string, p?: Record<string, unknown>) => {
    Glass.sendCommand(c, p ?? {}).catch((e) => console.warn(`[Controls] ${c} failed`, e));
  }, []);

  const confirm = (title: string, msg: string, onYes: () => void) =>
    Alert.alert(title, msg, [
      { text: 'Cancel', style: 'cancel' },
      { text: 'Confirm', style: 'destructive', onPress: onYes },
    ]);

  if (!connected)
    return (
      <ScrollView style={k.container}>
        <NotConnected />
      </ScrollView>
    );

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <Section title="Wake Word (Hey Cyan)">
        <Btn title="Enable Wake Word" color="#2a9d8f" onPress={() => Glass.setWakeWord(true)} />
        <Btn title="Disable Wake Word" color="#d62828" onPress={() => Glass.setWakeWord(false)} />
        <Btn title="Query State" onPress={() => Glass.getWakeWord()} />
      </Section>

      <Section title="Volume">
        {[0, 4, 8, 12, 15].map((v) => (
          <Btn key={v} title={`Set Volume ${v}`} onPress={() => cmd('setVolume', { value: v })} />
        ))}
        <Btn title="Query Volume" color="#6c757d" onPress={() => cmd('getVolume')} />
      </Section>

      <Section title="Device Settings">
        <Btn title="Sync Clock (RTC)" onPress={() => cmd('syncTime')} />
        <Btn title="Beeps ON" color="#2a9d8f" onPress={() => cmd('setBeep', { enabled: true })} />
        <Btn title="Beeps OFF (discreet)" color="#457b9d" onPress={() => cmd('setBeep', { enabled: false })} />
      </Section>

      <Section title={`Wear Detection — ${worn}`}>
        <Btn title="Enable Wear Detect" color="#2a9d8f" onPress={() => cmd('setWear', { enabled: true })} />
        <Btn title="Disable Wear Detect" color="#d62828" onPress={() => cmd('setWear', { enabled: false })} />
        <Btn title="Query Wear Status" onPress={() => cmd('getWear')} />
      </Section>

      <Section title="Audio / Mic">
        <Btn title="Play Test Audio (speaker)" color="#2a9d8f" onPress={() => cmd('playGlassAudio', { status: 1 })} />
        <Btn title="Start AI Voice Capture" onPress={() => cmd('aiVoiceCapture', { enabled: true })} />
        <Btn title="Stop AI Voice Capture" color="#8d99ae" onPress={() => cmd('aiVoiceCapture', { enabled: false })} />
      </Section>

      <Section title="Danger Zone">
        <Btn
          title="Reboot Device"
          color="#e76f51"
          onPress={() => confirm('Reboot?', 'The glasses will restart and disconnect.', () => Glass.reboot())}
        />
        <Btn
          title="Factory Reset"
          color="#d62828"
          onPress={() => confirm('Factory Reset?', 'This may erase data and unpair the device.', () => Glass.factoryReset())}
        />
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}
