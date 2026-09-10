import { requireOptionalNativeModule } from "expo-modules-core";

const ToolsModule = requireOptionalNativeModule("ToolsModule");

function ensureModule() {
  if (!ToolsModule) {
    throw new Error(
      "ToolsModule native module not found. Rebuild the dev client (npx expo prebuild + run).",
    );
  }
  return ToolsModule;
}

export interface ToolsAPI {
  executeDeviceTool(name: string, payload: string): Promise<string>;
  requestHealthPermissions(): Promise<boolean>;
  hasHealthPermissions(): Promise<boolean>;
}

export const Tools: ToolsAPI = {
  executeDeviceTool: (name: string, payload: string): Promise<string> =>
    ensureModule().executeDeviceTool(name, payload),

  requestHealthPermissions: (): Promise<boolean> =>
    ensureModule().requestHealthPermissions(),

  hasHealthPermissions: (): Promise<boolean> =>
    ensureModule().hasHealthPermissions(),
};

export const isToolsModuleAvailable = (): boolean => ToolsModule != null;

export default Tools;
