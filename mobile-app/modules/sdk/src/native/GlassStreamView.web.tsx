import React from "react";
import { StyleProp, View, ViewStyle, Text } from "react-native";

interface GlassStreamViewProps {
  style?: StyleProp<ViewStyle>;
}

export function GlassStreamView({ style }: GlassStreamViewProps) {
  return (
    <View
      style={[
        style,
        {
          backgroundColor: "#1a1a1a",
          alignItems: "center",
          justifyContent: "center",
        },
      ]}
    >
      <Text style={{ color: "rgba(255,255,255,0.4)", fontSize: 14 }}>
        Glass Stream (Web Preview)
      </Text>
    </View>
  );
}
