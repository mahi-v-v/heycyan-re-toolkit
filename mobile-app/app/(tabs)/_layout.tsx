import { Tabs } from 'expo-router';
import React from 'react';

export default function TabLayout() {
  return (
    <Tabs
      screenOptions={{
        headerShown: true,
        headerTitleAlign: 'center',
        tabBarActiveTintColor: '#1d3557',
        tabBarInactiveTintColor: '#8d99ae',
      }}>
      <Tabs.Screen
        name="index"
        options={{
          title: 'Connection',
          tabBarLabel: 'Connect',
        }}
      />
      <Tabs.Screen
        name="capture"
        options={{
          title: 'Capture',
          tabBarLabel: 'Capture',
        }}
      />
      <Tabs.Screen
        name="controls"
        options={{
          title: 'Device Controls',
          tabBarLabel: 'Controls',
        }}
      />
      <Tabs.Screen
        name="ota"
        options={{
          title: 'OTA Update',
          tabBarLabel: 'OTA',
        }}
      />
      <Tabs.Screen
        name="debug"
        options={{
          title: 'Diagnostics',
          tabBarLabel: 'Debug',
        }}
      />
    </Tabs>
  );
}
