import { requireNativeViewManager } from "expo-modules-core";
import React from "react";
import { StyleProp, ViewStyle } from "react-native";

const NativePhoneStreamView = requireNativeViewManager("PhoneStreamModule");

interface PhoneStreamViewProps {
  style?: StyleProp<ViewStyle>;
}

export function PhoneStreamView({ style }: PhoneStreamViewProps) {
  return <NativePhoneStreamView style={style} />;
}
