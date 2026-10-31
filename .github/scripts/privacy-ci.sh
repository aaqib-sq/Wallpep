#!/usr/bin/env bash
# Check the resolved runtime graph, final merged APK permissions, and bundled model.
set -euo pipefail
log_file="$RUNNER_TEMP/wallpep-runtime-dependencies.txt"
./gradlew --no-daemon :app:dependencies --configuration debugRuntimeClasspath > "$log_file"
cat "$log_file"
if grep -Eiq 'tasks-vision|play-services|datatransport|com\.google\.mlkit|com\.google\.mediapipe|com\.google\.firebase' "$log_file"; then
  echo '::error::Forbidden segmentation, Play Services, transport, or analytics dependency found.'
  exit 1
fi
model='app/src/main/assets/models/modnet_photographic_portrait_matting.onnx'
echo "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9  $model" | sha256sum -c -
apk='app/build/outputs/apk/debug/app-debug.apk'
aapt="$ANDROID_HOME/build-tools/37.0.0/aapt"
"$aapt" list "$apk" | grep -Fx 'assets/models/modnet_photographic_portrait_matting.onnx'
"$aapt" dump permissions "$apk" | tee "$RUNNER_TEMP/wallpep-apk-permissions.txt"
# AndroidX Core's existing signature-only receiver permission was already in Phase 1.
if grep '^uses-permission:' "$RUNNER_TEMP/wallpep-apk-permissions.txt" | \
  grep -v "name='dev.wallpep.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"; then
  echo '::error::Unexpected merged APK permission.'
  exit 1
fi
