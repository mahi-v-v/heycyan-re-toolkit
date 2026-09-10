import LiveKitClient

/// Protocol adopted by GlassStreamView so Agent/AgentManager don't import the concrete view type.
protocol GlassStreamRendering: AnyObject {
    var videoView: VideoView { get }
}
