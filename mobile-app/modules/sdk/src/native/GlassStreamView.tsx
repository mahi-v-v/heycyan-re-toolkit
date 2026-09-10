import { requireNativeViewManager } from "expo-modules-core";
import React from "react";
import { StyleProp, ViewStyle } from "react-native";

const NativeGlassStreamView = requireNativeViewManager("GlassStreamModule");

interface GlassStreamViewProps {
  style?: StyleProp<ViewStyle>;
}

/**
 * Renders the live glass camera feed sourced from the LocalVideoTrack
 * published by AgentManager. No TCP props needed — streaming is managed
 * automatically by the native SDK when the glass connects.
 *
 * Usage:
 *   <GlassStreamView style={{ flex: 1 }} />
 */
export function GlassStreamView({ style }: GlassStreamViewProps) {
  return <NativeGlassStreamView style={style} />;
}
