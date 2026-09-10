import { Stack } from 'expo-router';
import { StatusBar } from 'expo-status-bar';
import { useEffect } from 'react';
import { ensureGlassLogging } from '@/components/GlassKit';

export default function RootLayout() {
  useEffect(() => {
    // Start the single global glass-trace listener at app boot so every native
    // [CMD]/[NOTIFY]/[CAP]/… trace hits Metro + the shared on-screen log on every tab.
    ensureGlassLogging();
  }, []);
  return (
    <>
      <Stack>
        <Stack.Screen name="(tabs)" options={{ headerShown: false }} />
      </Stack>
      <StatusBar style="auto" />
    </>
  );
}
