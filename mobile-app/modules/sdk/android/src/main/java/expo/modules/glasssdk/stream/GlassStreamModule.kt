package expo.modules.glasssdk.stream

import android.util.Log
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition

/**
 * Expo module that exposes GlassStreamView as a React Native view component.
 *
 * Usage from React Native:
 *   import { GlassStreamView } from '../modules/sdk';
 *   <GlassStreamView style={...} />
 *
 * The view automatically registers with AgentManager when attached to the window.
 * Frames are sourced from the LocalVideoTrack published by Agent (no direct TCP here).
 */
class GlassStreamModule : Module() {

    companion object {
        private const val TAG = "[Glass:StreamModule]"
        const val VIEW_NAME = "GlassStreamView"
    }

    override fun definition() = ModuleDefinition {
        Name("GlassStreamModule")

        View(GlassStreamView::class) {
            OnViewDestroys { view ->
                view.detach()
            }
        }
    }
}
