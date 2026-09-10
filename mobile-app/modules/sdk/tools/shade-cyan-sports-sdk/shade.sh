#!/usr/bin/env bash
#
# Repackage the cyan_sports / Moyoung CRP glasses SDK AAR so its protobuf runtime is shaded
# into a private package (com.moyoung.shaded.protobuf).
#
# WHY: the Moyoung SDK's generated proto classes extend the FULL protobuf runtime
# (com.google.protobuf.GeneratedMessage). The rest of the app (LiveKit, K900, androidx.datastore)
# uses protobuf-javalite, where the well-known types extend GeneratedMessageLite. Putting both
# full + lite protobuf on one classpath crashes LiveKit at runtime with a VerifyError
# (Timestamp expected GeneratedMessageLite). Shading the Moyoung SDK's protobuf into a private
# package lets both coexist: the app stays entirely on javalite, the Moyoung SDK carries its own.
#
# USAGE:
#   ./shade.sh /path/to/vendor_sdk.aar
#
# Output: writes the repackaged AAR to modules/sdk/android/libs/cyan_sports_sdk.aar
#
# Re-run this whenever the vendor ships a new SDK AAR.
set -euo pipefail

VENDOR_AAR="${1:?Usage: ./shade.sh /path/to/vendor_sdk.aar}"
HERE="$(cd "$(dirname "$0")" && pwd)"
LIBS="$(cd "$HERE/../../android/libs" && pwd)"
# Use the project's gradle wrapper (no system gradle needed)
GRADLEW="$(cd "$HERE/../../../../android" && pwd)/gradlew"
OUT_AAR="$LIBS/cyan_sports_sdk.aar"

echo "==> Extracting classes.jar from vendor AAR"
rm -f "$HERE/moyoung-classes.jar"
unzip -o "$VENDOR_AAR" classes.jar -d "$HERE" >/dev/null
mv "$HERE/classes.jar" "$HERE/moyoung-classes.jar"

echo "==> Running shadowJar (relocating com.google.protobuf -> com.moyoung.shaded.protobuf)"
"$GRADLEW" -p "$HERE" clean shadowJar

SHADED="$HERE/build/libs/moyoung-shaded.jar"
test -f "$SHADED" || { echo "ERROR: shaded jar not produced"; exit 1; }

echo "==> Repackaging AAR with shaded classes.jar"
cp "$VENDOR_AAR" "$OUT_AAR"
REPACK="$(mktemp -d)"
cp "$SHADED" "$REPACK/classes.jar"
( cd "$REPACK" && zip "$OUT_AAR" classes.jar >/dev/null )
rm -rf "$REPACK"

echo "==> Verifying (expect 0 com/google/protobuf, >0 com/moyoung/shaded/protobuf)"
TMP="$(mktemp -d)"
unzip -o "$OUT_AAR" classes.jar -d "$TMP" >/dev/null
echo "    com/google/protobuf:        $(unzip -l "$TMP/classes.jar" | grep -c 'com/google/protobuf/' || true)"
echo "    com/moyoung/shaded/protobuf: $(unzip -l "$TMP/classes.jar" | grep -c 'com/moyoung/shaded/protobuf/' || true)"
rm -rf "$TMP"

echo "==> Done: $OUT_AAR"
