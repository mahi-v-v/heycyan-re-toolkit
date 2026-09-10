package expo.modules.glasssdk.stream

import android.content.Context
import android.util.Log
import expo.modules.glasssdk.agent.AgentManager
import io.livekit.android.renderer.TextureViewRenderer

/**
 * LiveKit TextureViewRenderer wrapper that renders the glass camera LocalVideoTrack.
 *
 * When attached, registers with AgentManager so the current (or future) LocalVideoTrack
 * can render frames here. The EGL context is shared via Room.initVideoRenderer(), so
 * OES texture frames from SurfaceTextureHelper render correctly.
 *
 * Rotation is set to 270° in init to display the landscape glass feed as portrait.
 */
class GlassStreamView(context: Context) : TextureViewRenderer(context) {

    companion object {
        private const val TAG = "[Glass:StreamView]"
    }

    init {
        rotation = 270f
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        attach()
    }

    fun attach() {
        Log.d(TAG, "Attaching to AgentManager for preview")
        AgentManager.getInstance(context).addGlassPreviewRenderer(this)
    }

    fun detach() {
        Log.d(TAG, "Detaching from AgentManager")
        AgentManager.getInstance(context).removeGlassPreviewRenderer(this)
        try { release() } catch (e: Exception) { Log.w(TAG, "Release error: ${e.message}") }
    }
}
