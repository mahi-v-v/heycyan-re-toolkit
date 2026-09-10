import ExpoModulesCore
import LiveKitClient

class GlassStreamView: ExpoView, GlassStreamRendering {
    let videoView = VideoView()

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        videoView.layoutMode = .fill
        addSubview(videoView)
        AgentManager.shared.addGlassPreviewRenderer(self)
    }

    deinit {
        AgentManager.shared.removeGlassPreviewRenderer(self)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        videoView.frame = bounds
    }
}
