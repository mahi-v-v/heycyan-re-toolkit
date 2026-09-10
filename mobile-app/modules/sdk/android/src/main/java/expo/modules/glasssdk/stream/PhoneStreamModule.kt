package expo.modules.glasssdk.stream

import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

class PhoneStreamModule : Module() {
    override fun definition() = ModuleDefinition {
        Name("PhoneStreamModule")

        View(PhoneStreamView::class) {
            OnViewDestroys { view ->
                view.detach()
            }
        }
    }
}
