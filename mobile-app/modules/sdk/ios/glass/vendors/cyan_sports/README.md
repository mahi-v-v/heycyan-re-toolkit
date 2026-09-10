# cyan_sports (Moyoung CRPSmartGlasses) — iOS framework setup

The cyan_sports iOS SDK is the Moyoung **CRPSmartGlasses** stack. Unlike the K900/Cyan SDKs
(which ship as **static** frameworks that drop straight into our statically-linked pods),
CRPSmartGlasses ships **dynamic** frameworks that link `SwiftProtobuf` + `AFNetworking`
**dynamically** (`@rpath`). Our app links pods statically (`ios.useFrameworks: "static"`), so those
dynamic dependencies don't otherwise exist as dylibs.

To make the dynamic SDK work inside the static-pod app **without** flipping the whole app to
dynamic linking, every CRP framework here is vendored as an `.xcframework` whose real code is the
**device (`ios-arm64`) slice** (the simulator uses `MockAdapter`, and the adapter source is wrapped
in `#if !targetEnvironment(simulator)`).

### Simulator stub slices

The app target links every vendored framework unconditionally (`-framework CRPSmartGlasses` … in
`Pods-Glass`'s `OTHER_LDFLAGS`) **and** embeds each one unconditionally (`Pods-Glass-frameworks.sh`),
so a **simulator** build would otherwise fail (link "framework not found" / embed "no such file")
even though all the CRP code is compiled out. **All nine** frameworks therefore carry a tiny
**stub `ios-arm64_x86_64-simulator` slice** — an empty, no-symbol binary that only satisfies the
copy/link/embed steps (mirrors the stub on `cyan/QCSDK.xcframework`). The stub's Mach-O type matches
its device slice (dynamic dylib for CRP/JL*/AFNetworking/SwiftProtobuf, static archive for
MZEncryptSDK), because CocoaPods refuses an xcframework that mixes static and dynamic slices.

There's a second reason **every** framework must be stubbed (not just the linked ones): CocoaPods'
generated `GlassSdk-xcframeworks.sh` has a bug — `select_slice()` doesn't reset its return value
when no slice matches, so a device-only framework that follows a simulator-having one inherits the
previous framework's slice and tries to rsync a nonexistent sim slice (status 23, build fails).
Giving every framework a sim slice sidesteps the bug. `SwiftProtobuf`'s stub is safe even though a
real `SwiftProtobuf` pod exists: the pod (static, via LiveKit) sits earlier in `FRAMEWORK_SEARCH_PATHS`,
so the linker still pulls the real symbols; the bare stub (no Modules/Headers, like the device slice)
only satisfies copy + embed and never shadows the pod's Swift module.

Regenerate the stubs anytime the device slices are refreshed:

```bash
sh modules/sdk/tools/make-cyan-sports-sim-stubs.sh   # then: cd ios && pod install
```

## What's vendored (ios-arm64 device slice + stub simulator slice; SwiftProtobuf device-only)

| xcframework | role | source |
|---|---|---|
| `CRPSmartGlasses` | the SDK (we `import CRPSmartGlasses`) | re-wrapped device slice of the vendor's xcframework |
| `JLAudioUnitKit`, `JL_OTALib`, `JL_HashPair`, `JL_AdvParse`, `JLLogHelper`, `MZEncryptSDK` | JL/Moyoung support libs CRP links | vendor `.framework`s wrapped via `-create-xcframework` |
| `SwiftProtobuf`, `AFNetworking` | CRP's dynamic deps, embedded so its `@rpath` resolves at runtime | built dynamic from the demo's Pods (SwiftProtobuf 1.29.0, AFNetworking 4.0.1) |

`SwiftProtobuf` and `AFNetworking` here have their `Modules/` + `Headers/` **stripped** — they exist
only to provide the runtime dylib for CRP's `@rpath`. Our Swift never imports them, and stripping
the module avoids a compile-time clash with the **static** SwiftProtobuf that LiveKit already pulls
in (two copies coexist; they never share objects). `openssl` is intentionally omitted — it's a static
lib and nothing in the CRP stack links it dynamically.

Registered in `GlassSdk.podspec` under `vendored_frameworks` (+ `exclude_files`).

## Rebuilding when the vendor ships a new SDK

Sources live in `demo/cyan_sports_ios/.../Release-iphoneos/`. Steps (the same ones used initially):

1. Wrap each device `.framework` as a device-only xcframework:
   `xcodebuild -create-xcframework -framework X.framework -output X.xcframework`
   For `CRPSmartGlasses` (already an xcframework), wrap only its `ios-arm64/CRPSmartGlasses.framework`.
2. Build dynamic `SwiftProtobuf` + `AFNetworking` from the demo's Pods at the versions CRP expects:
   `xcodebuild -project demo/.../Pods/Pods.xcodeproj -target SwiftProtobuf -sdk iphoneos -configuration Release SYMROOT=… CODE_SIGNING_ALLOWED=NO MACH_O_TYPE=mh_dylib build` (same for AFNetworking),
   then wrap each into an xcframework and **delete its `Modules/` and `Headers/`**.
3. **Bump CRPSmartGlasses's swiftinterface deployment target.** CRPSmartGlasses ships only a
   textual `.swiftinterface` (no binary swiftmodule), pinned to `-target arm64-apple-ios13.0`. Our
   newer Xcode recompiles that interface, and it imports SwiftProtobuf (the app's pod, min iOS
   15.1), which fails at target 13.0. Patch both interface files to iOS 16 (our app target):
   ```bash
   D=CRPSmartGlasses.xcframework/ios-arm64/CRPSmartGlasses.framework/Modules/CRPSmartGlasses.swiftmodule
   sed -i '' 's/-target arm64-apple-ios13\.0/-target arm64-apple-ios16.0/' \
     "$D"/arm64-apple-ios.swiftinterface "$D"/arm64-apple-ios.private.swiftinterface
   ```
4. Copy all xcframeworks here, then regenerate the simulator stub slices and reinstall:
   `sh modules/sdk/tools/make-cyan-sports-sim-stubs.sh && cd ios && pod install`.

Verify CRP's dynamic deps with `otool -L CRPSmartGlasses.xcframework/ios-arm64/CRPSmartGlasses.framework/CRPSmartGlasses`.
