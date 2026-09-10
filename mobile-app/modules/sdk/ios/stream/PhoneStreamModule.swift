import ExpoModulesCore

public class PhoneStreamModule: Module {
    public func definition() -> ModuleDefinition {
        Name("PhoneStreamModule")
        View(PhoneStreamView.self) {}
    }
}
