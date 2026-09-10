import React, { useCallback, useEffect, useState } from 'react';
import { ScrollView, Text } from 'react-native';
import Glass, {
  GlassEventSubscriptions,
  GlassStorageInfo,
} from '@/modules/sdk/src/native/Glass';
import { useConnected, useDeviceLog, Section, Btn, LogPanel, NotConnected, k } from '@/components/GlassKit';

export default function CaptureScreen() {
  const connected = useConnected();
  const { lines, clear } = useDeviceLog();
  const [busy, setBusy] = useState<string | null>(null);
  const [storage, setStorage] = useState<GlassStorageInfo | null>(null);
  const [recording, setRecording] = useState(false);
  const [audioRec, setAudioRec] = useState(false);
  const [lastAi, setLastAi] = useState<string | null>(null);
  const [sync, setSync] = useState<string>('');

  useEffect(() => {
    const s1 = GlassEventSubscriptions.onSyncStarted(() => setSync('Sync started…'));
    const s2 = GlassEventSubscriptions.onSyncProgress((e) =>
      setSync(`Syncing ${e.current}/${e.total} (${e.percent}%)`),
    );
    const s3 = GlassEventSubscriptions.onSyncCompleted((e) =>
      setSync(e.success ? `Sync complete: ${e.totalFiles} files` : `Sync failed: ${e.error ?? 'error'}`),
    );
    return () => {
      s1.remove();
      s2.remove();
      s3.remove();
    };
  }, []);

  const run = useCallback(async (tag: string, fn: () => Promise<unknown>) => {
    setBusy(tag);
    try {
      await fn();
    } catch (e) {
      console.warn(`[Capture] ${tag} failed`, e);
    } finally {
      setBusy(null);
    }
  }, []);

  const refreshStorage = useCallback(
    () => run('storage', async () => setStorage(await Glass.getStorageInfo())),
    [run],
  );

  useEffect(() => {
    if (connected) refreshStorage();
  }, [connected, refreshStorage]);

  if (!connected)
    return (
      <ScrollView style={k.container}>
        <NotConnected />
      </ScrollView>
    );

  return (
    <ScrollView style={k.container} contentContainerStyle={k.content}>
      <Section title="Photo">
        <Btn
          title="Take Photo"
          onPress={() => run('photo', async () => { await Glass.takePhoto(); refreshStorage(); })}
          busy={busy === 'photo'}
        />
        <Btn
          title="Take AI Image (vision)"
          color="#2a9d8f"
          onPress={() => run('ai', async () => { setLastAi(await Glass.takeAiImage()); refreshStorage(); })}
          busy={busy === 'ai'}
        />
        {lastAi && <Text style={k.hint}>Saved: {lastAi}</Text>}
      </Section>

      <Section title="Video">
        {!recording ? (
          <Btn
            title="Start Recording"
            color="#e76f51"
            onPress={() => run('vstart', async () => { await Glass.startVideo(); setRecording(true); })}
            busy={busy === 'vstart'}
          />
        ) : (
          <Btn
            title="Stop Recording"
            color="#d62828"
            onPress={() => run('vstop', async () => { await Glass.stopVideo(); setRecording(false); refreshStorage(); })}
            busy={busy === 'vstop'}
          />
        )}
      </Section>

      <Section title="Audio">
        {!audioRec ? (
          <Btn
            title="Start Audio"
            color="#e76f51"
            onPress={() => run('astart', async () => { await Glass.startAudio(); setAudioRec(true); })}
            busy={busy === 'astart'}
          />
        ) : (
          <Btn
            title="Stop Audio"
            color="#d62828"
            onPress={() => run('astop', async () => { await Glass.stopAudio(); setAudioRec(false); refreshStorage(); })}
            busy={busy === 'astop'}
          />
        )}
      </Section>

      <Section title="Storage">
        {storage ? (
          <Text style={k.statusText}>
            Photos: {storage.photoCount}   Videos: {storage.videoCount}   Audio: {storage.audioCount}
          </Text>
        ) : (
          <Text style={k.hint}>Tap refresh to query counts.</Text>
        )}
        <Btn title="Refresh Storage" onPress={refreshStorage} busy={busy === 'storage'} />
      </Section>

      <Section title="Media Sync (Wi-Fi Direct)">
        <Btn title="Sync Files to Phone" onPress={() => run('sync', () => Glass.syncFiles())} busy={busy === 'sync'} />
        {!!sync && <Text style={k.hint}>{sync}</Text>}
        <Btn title="Cancel Sync" color="#8d99ae" onPress={() => run('cancel', () => Glass.cancelSync())} />
      </Section>

      <LogPanel lines={lines} onClear={clear} />
    </ScrollView>
  );
}
