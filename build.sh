#!/bin/sh
# Builds and signs Oh My CPA. Output: build/oh-my-cpa-<version>.apk
# Proposed parametrised version for the public repo: no machine paths, no password.
set -e
VERSION_NAME=${VERSION_NAME:-0.4.4}
VERSION_CODE=${VERSION_CODE:-10}

: "${ANDROID_HOME:=${ANDROID_SDK_ROOT:-$HOME/Android/Sdk}}"
BUILD_TOOLS=${BUILD_TOOLS:-$(ls -d "$ANDROID_HOME"/build-tools/* 2>/dev/null | sort -V | tail -1)}
ANDROID_JAR=${ANDROID_JAR:-$ANDROID_HOME/platforms/android-34/android.jar}
AAPT2=${AAPT2:-$BUILD_TOOLS/aapt2}
ZIPALIGN=${ZIPALIGN:-$BUILD_TOOLS/zipalign}
D8_JAR=${D8_JAR:-$BUILD_TOOLS/lib/d8.jar}
APKSIGNER_JAR=${APKSIGNER_JAR:-$BUILD_TOOLS/lib/apksigner.jar}
JAVA_BIN=${JAVA_HOME:+$JAVA_HOME/bin/}

# Signing: bring your own keystore. Nothing secret lives in this repo.
: "${OMC_KEYSTORE:?set OMC_KEYSTORE to your .p12/.jks keystore}"
: "${OMC_KS_PASS:?set OMC_KS_PASS to the keystore password}"
OMC_KEY_ALIAS=${OMC_KEY_ALIAS:-omc}
OMC_KS_TYPE=${OMC_KS_TYPE:-PKCS12}
export OMC_KS_PASS OMC_KEY_PASS="${OMC_KEY_PASS:-$OMC_KS_PASS}"

cd "$(dirname "$0")"
rm -rf build && mkdir -p build/gen build/classes build/dex
"$AAPT2" compile --dir res -o build/res.zip
"$AAPT2" link -I "$ANDROID_JAR" --manifest AndroidManifest.xml -A assets --java build/gen -o build/base.apk build/res.zip \
  --min-sdk-version 29 --target-sdk-version 28 --version-code "$VERSION_CODE" --version-name "$VERSION_NAME" --auto-add-overlay
"${JAVA_BIN}javac" -Xlint:-options -nowarn -source 11 -target 11 -encoding UTF-8 -classpath "$ANDROID_JAR" -d build/classes $(find src build/gen -name "*.java")
"${JAVA_BIN}java" -cp "$D8_JAR" com.android.tools.r8.D8 --release --min-api 29 --lib "$ANDROID_JAR" --output build/dex $(find build/classes -name "*.class")
cp build/base.apk build/unsigned.apk
(cd build/dex && zip -q -X ../unsigned.apk classes.dex)
"$ZIPALIGN" -f -p 4 build/unsigned.apk build/aligned.apk
"${JAVA_BIN}java" -jar "$APKSIGNER_JAR" sign --ks "$OMC_KEYSTORE" --ks-type "$OMC_KS_TYPE" \
  --ks-pass env:OMC_KS_PASS --key-pass env:OMC_KEY_PASS --ks-key-alias "$OMC_KEY_ALIAS" --min-sdk-version 1 \
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled true \
  --out "build/oh-my-cpa-$VERSION_NAME.apk" build/aligned.apk
"${JAVA_BIN}java" -jar "$APKSIGNER_JAR" verify --min-sdk-version 29 "build/oh-my-cpa-$VERSION_NAME.apk"
echo "BUILT build/oh-my-cpa-$VERSION_NAME.apk"
