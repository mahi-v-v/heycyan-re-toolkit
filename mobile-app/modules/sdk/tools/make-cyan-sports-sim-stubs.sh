#!/bin/sh
#
# make-cyan-sports-sim-stubs.sh
#
# The cyan_sports (Moyoung CRPSmartGlasses) SDK ships DEVICE-ONLY xcframeworks
# (ios-arm64 only). The app target links every vendored framework unconditionally
# (`-framework CRPSmartGlasses` ... in Pods-Glass's OTHER_LDFLAGS), so a *simulator*
# build fails at link with "framework not found" — even though all consuming Swift
# is compiled out via `#if !targetEnvironment(simulator)` (it uses MockAdapter).
#
# This script adds a STUB `ios-arm64_x86_64-simulator` slice to each device-only
# framework: a tiny fat (arm64 + x86_64) link-only binary that satisfies the linker.
# It exports no symbols — nothing references it on the simulator. This mirrors the
# stub slice already shipped for cyan/QCSDK.xcframework (commit 29977e7).
#
# The stub's Mach-O type MUST match the device slice's: CocoaPods refuses an xcframework
# that mixes static and dynamic slices. Most cyan_sports frameworks are DYNAMIC dylibs
# (built as an empty dylib with the matching @rpath install name); MZEncryptSDK is a
# STATIC archive (like QCSDK), built as an empty fat static archive.
#
# ALL nine vendored frameworks are stubbed — including SwiftProtobuf. Every framework
# in the pod's vendored_frameworks is linked unconditionally (-framework ...) AND embedded
# unconditionally (Pods-Glass-frameworks.sh), so each one needs a simulator slice present.
# Crucially, CocoaPods' generated xcframeworks.sh has a bug: select_slice() leaves
# SELECT_SLICE_RETVAL untouched when no slice matches, so a device-only framework that
# follows a simulator-having one inherits the previous framework's selected slice and tries
# to rsync a nonexistent sim slice (this is what broke CRPSmartGlasses originally, and
# SwiftProtobuf when it was left device-only). Stubbing every framework avoids the bug.
#
# SwiftProtobuf stub is safe: the real SwiftProtobuf pod (static, via LiveKit) sits earlier
# in FRAMEWORK_SEARCH_PATHS, so the linker still pulls the real symbols; the stub only
# satisfies copy + embed. Its device slice has no Modules/Headers (stripped), so its stub is
# a bare dylib with no Swift module — no clash with the pod's SwiftProtobuf module.
#
# Idempotent: re-running regenerates the stub binary and leaves the plist untouched
# if the simulator slice is already registered. Run `cd ios && pod install` afterwards.

set -eu

# cyan_sports vendors dir, resolved relative to this script (tools/ -> ios/glass/vendors/cyan_sports)
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
VENDORS="$(cd "$SCRIPT_DIR/../ios/glass/vendors/cyan_sports" && pwd)"

FRAMEWORKS="CRPSmartGlasses JLAudioUnitKit JL_OTALib JL_HashPair JL_AdvParse JLLogHelper MZEncryptSDK SwiftProtobuf AFNetworking"

SIM_ID="ios-arm64_x86_64-simulator"
MIN_VERSION="16.0"
PB=/usr/libexec/PlistBuddy

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
: > "$TMP/empty.c"

for FW in $FRAMEWORKS; do
  XC="$VENDORS/$FW.xcframework"
  DEVICE="$XC/ios-arm64/$FW.framework"
  SIM="$XC/$SIM_ID/$FW.framework"

  if [ ! -d "$DEVICE" ]; then
    echo "warning: $FW: missing device slice ($DEVICE), skipping" >&2
    continue
  fi

  echo "==> $FW"
  rm -rf "$XC/$SIM_ID"
  mkdir -p "$SIM"

  # Build a link-only stub whose Mach-O type matches the device slice (CocoaPods rejects
  # an xcframework mixing static + dynamic slices).
  if file -b "$DEVICE/$FW" | grep -q "ar archive"; then
    # Static framework (e.g. MZEncryptSDK): empty fat static archive.
    for ARCH in arm64 x86_64; do
      xcrun -sdk iphonesimulator clang -c -arch "$ARCH" \
        -mios-simulator-version-min="$MIN_VERSION" -x c "$TMP/empty.c" -o "$TMP/$FW.$ARCH.o"
      xcrun ar -crs "$TMP/lib$FW.$ARCH.a" "$TMP/$FW.$ARCH.o"
    done
    xcrun lipo -create "$TMP/lib$FW.arm64.a" "$TMP/lib$FW.x86_64.a" -output "$SIM/$FW"
    echo "    static stub"
  else
    # Dynamic framework: empty fat dylib with the @rpath install name the SDK expects.
    xcrun -sdk iphonesimulator clang -dynamiclib -arch arm64 -arch x86_64 \
      -mios-simulator-version-min="$MIN_VERSION" \
      -install_name "@rpath/$FW.framework/$FW" \
      -x c "$TMP/empty.c" -o "$SIM/$FW"
    echo "    dynamic stub"
  fi

  # Inner framework Info.plist: copy verbatim from the device slice.
  cp "$DEVICE/Info.plist" "$SIM/Info.plist"

  # Minimal modulemap (only where the device slice has Modules). Stub-only: no umbrella
  # header is shipped, and nothing @imports it on the simulator, so `export *` is enough.
  # Never copy CRPSmartGlasses's .swiftmodule — that would force Xcode to recompile its
  # Swift interface (which references real symbols) for the simulator.
  if [ -f "$DEVICE/Modules/module.modulemap" ]; then
    mkdir -p "$SIM/Modules"
    printf 'framework module %s { export * }\n' "$FW" > "$SIM/Modules/module.modulemap"
  fi

  # Register the simulator slice in the xcframework's top-level Info.plist (idempotent).
  PLIST="$XC/Info.plist"
  if "$PB" -c "Print :AvailableLibraries" "$PLIST" 2>/dev/null | grep -q "$SIM_ID"; then
    echo "    slice already registered in Info.plist"
  else
    # Existing device entry is index 0; append the simulator entry at index 1.
    "$PB" -c "Add :AvailableLibraries:1 dict" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:BinaryPath string $FW.framework/$FW" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:LibraryIdentifier string $SIM_ID" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:LibraryPath string $FW.framework" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:SupportedArchitectures array" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:SupportedArchitectures:0 string arm64" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:SupportedArchitectures:1 string x86_64" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:SupportedPlatform string ios" "$PLIST"
    "$PB" -c "Add :AvailableLibraries:1:SupportedPlatformVariant string simulator" "$PLIST"
    echo "    registered $SIM_ID slice in Info.plist"
  fi
done

echo ""
echo "Done. Now run: cd ios && pod install"
