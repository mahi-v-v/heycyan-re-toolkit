import React, { useCallback, useState } from 'react';
import { ScrollView, Text, TextInput } from 'react-native';
import Glass from '@/modules/sdk/src/native/Glass';
import {
  useConnected,
  useDeviceLog,
  Section,
  Btn,
  LogPanel,
  NotConnected,
  k,
} from '@/components/GlassKit';

export default function DebugScreen() {
  const connected = useConnected();
  const { lines, clear } = useDeviceLog();
  const [raw, setRaw] = useState('2,1,11');

  const cmd = useCallback((c: string, p?: Record<string, unknown>) => {
    Glass.sendCommand(c, p ?? {}).catch((e) => console.warn(`[Debug] ${c} failed`, e));
  }, []);

  const sendRaw = useCallback(() => {
    const bytes = raw
      .split(',')
      .map((s) => parseInt(s.trim(), 10))
      .filter((n) => !isNaN(n) && n >= 0 && n <= 255);
    if (bytes.length) cmd('rawControl', { bytes });
  }, [raw, cmd]);

  if (!connected)
    return (
      <ScrollView style={k.container}>
        <NotConnected />
      </ScrollView>
    );

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <Section title="Capability Probe">
        <Text style={k.hint}>
          Ask the glasses what THIS unit supports. Note: the bundled SDK only decodes model /
          wear-check / volume / translation — the newer bits (liveReview / aov / shortcut) need an
          aar update. Result shows in the log as a [CAP] line.
        </Text>
        <Btn title="Query Capabilities" color="#2a9d8f" onPress={() => cmd('queryFeatureSupport')} />
        <Btn title="Query Current Work-Type" onPress={() => cmd('queryWorkType')} />
      </Section>

      <Section title="Unknown Work-Type Probe">
        <Text style={[k.hint, { color: '#d62828' }]}>
          ⚠️ Sends {'{2,1,N}'}. These are firmware-accepted modes of unknown effect — avoid values near
          10 (factory-reset) and 14 (restart).
        </Text>
        {[7, 18, 20, 33, 39].map((n) => (
          <Btn
            key={n}
            title={`Probe work-type ${n}`}
            color="#6c757d"
            onPress={() => cmd('rawControl', { bytes: [2, 1, n] })}
          />
        ))}
      </Section>

      <Section title="Raw Command">
        <Text style={k.hint}>Comma-separated bytes (decimal), e.g. 2,1,11</Text>
        <TextInput
          value={raw}
          onChangeText={setRaw}
          placeholder="2,1,11"
          keyboardType="numbers-and-punctuation"
          autoCapitalize="none"
          style={k.input}
        />
        <Btn title="Send Raw glassesControl" onPress={sendRaw} />
      </Section>

      <LogPanel lines={lines} onClear={clear} title="Device Log (live)" />
    </ScrollView>
  );
}
