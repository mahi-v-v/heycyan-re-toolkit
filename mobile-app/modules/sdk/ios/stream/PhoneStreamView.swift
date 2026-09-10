import ExpoModulesCore
import LiveKitClient

class PhoneStreamView: ExpoView {
    let videoView = VideoView()

    required init(appContext: AppContext? = nil) {
        super.init(appContext: appContext)
        videoView.layoutMode = .fill
        addSubview(videoView)
        AgentManager.shared.addPhonePreviewRenderer(videoView)
    }

    deinit {
        AgentManager.shared.removePhonePreviewRenderer(videoView)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        videoView.frame = bounds
    }
}
