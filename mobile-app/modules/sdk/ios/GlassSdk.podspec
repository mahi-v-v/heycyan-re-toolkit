Pod::Spec.new do |s|
  s.name           = 'GlassSdk'
  s.version        = '1.0.0'
  s.summary        = 'GlassSdk SDK - Glass device control and LiveKit voice agent'
  s.description    = 'Native iOS modules for K900 smart glass control (GlassModule) and LiveKit voice agent (AgentModule)'
  s.author         = ''
  s.homepage       = 'https://docs.expo.dev/modules/'
  s.platforms      = { :ios => '16.0' }
  s.source         = { git: '' }
  s.static_framework = true

  s.dependency 'ExpoModulesCore'
  s.dependency 'LiveKitClient', '~> 2.15.1'

  # Vendored vendor SDK frameworks.
  # cyan_sports (Moyoung CRPSmartGlasses) ships as DYNAMIC device-only frameworks that link
  # SwiftProtobuf + AFNetworking dynamically. The app links pods statically, so we embed our own
  # dynamic SwiftProtobuf/AFNetworking (Modules stripped so they don't clash with the static
  # SwiftProtobuf from LiveKit at compile time — they exist only to satisfy CRP's @rpath at runtime).
  s.vendored_frameworks = [
    'glass/vendors/k900/XingYiGlassesSDK.xcframework',
    'glass/vendors/cyan/QCSDK.xcframework',
    'glass/vendors/cyan_sports/CRPSmartGlasses.xcframework',
    'glass/vendors/cyan_sports/JLAudioUnitKit.xcframework',
    'glass/vendors/cyan_sports/JL_OTALib.xcframework',
    'glass/vendors/cyan_sports/JL_HashPair.xcframework',
    'glass/vendors/cyan_sports/JL_AdvParse.xcframework',
    'glass/vendors/cyan_sports/JLLogHelper.xcframework',
    'glass/vendors/cyan_sports/MZEncryptSDK.xcframework',
    'glass/vendors/cyan_sports/SwiftProtobuf.xcframework',
    'glass/vendors/cyan_sports/AFNetworking.xcframework'
  ]
  s.resources = 'glass/vendors/k900/XYSDK.bundle'

  # Required system frameworks
  s.frameworks = 'CoreBluetooth', 'NetworkExtension', 'SystemConfiguration', 'HealthKit'

  # Swift/Objective-C compatibility
  s.pod_target_xcconfig = {
    'DEFINES_MODULE' => 'YES',
    'SWIFT_VERSION' => '5.9',
  }

  s.source_files = "**/*.{h,m,mm,swift,hpp,cpp}"
  s.exclude_files = [
    "glass/vendors/k900/XingYiGlassesSDK.xcframework/**/*",
    "glass/vendors/cyan/QCSDK.xcframework/**/*",
    "glass/vendors/cyan_sports/*.xcframework/**/*"
  ]
end
