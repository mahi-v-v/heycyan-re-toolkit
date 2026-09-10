import ExpoModulesCore

public class ToolsModule: Module {

    public func definition() -> ModuleDefinition {
        Name("ToolsModule")

        OnCreate {
            ToolManager.shared.initialize()
        }

        AsyncFunction("executeDeviceTool") { (name: String, payload: String, promise: Promise) in
            Task {
                var params: [String: Any] = [:]
                if let data = payload.data(using: .utf8),
                   let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                    params = json
                }
                let result = await ToolManager.shared.callTool(toolName: name, params: params)
                promise.resolve(result)
            }
        }

        AsyncFunction("requestHealthPermissions") { (promise: Promise) in
            Task {
                let granted = await ToolManager.shared.requestHealthPermissions()
                promise.resolve(granted)
            }
        }

        AsyncFunction("hasHealthPermissions") { () -> Bool in
            return ToolManager.shared.hasHealthPermissions()
        }
    }
}
