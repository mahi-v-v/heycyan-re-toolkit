import ExpoModulesCore

public class GlassStreamModule: Module {
    public func definition() -> ModuleDefinition {
        Name("GlassStreamModule")
        View(GlassStreamView.self) {}
    }
}
