package expo.modules.glasssdk.stream

import android.content.Context
import android.util.Log
import expo.modules.glasssdk.agent.AgentManager
import io.livekit.android.renderer.TextureViewRenderer

class PhoneStreamView(context: Context) : TextureViewRenderer(context) {

    companion object {
        private const val TAG = "[Phone:StreamView]"
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val mgr = AgentManager.getInstance(context)
        val room = mgr.getRoom()
        Log.d(TAG, "onAttachedToWindow room=$room")
        if (room != null) {
            room.initVideoRenderer(this)
            Log.d(TAG, "initVideoRenderer called")
        } else {
            Log.w(TAG, "room is NULL — cannot initVideoRenderer")
        }
        mgr.addPhonePreviewRenderer(this)
    }

    fun detach() {
        Log.d(TAG, "Detaching from AgentManager")
        AgentManager.getInstance(context).removePhonePreviewRenderer(this)
        try { release() } catch (e: Exception) { Log.w(TAG, "Release error: ${e.message}") }
    }
}
