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
# Later ORT releases added POSIX telemetry. Any version change must receive a privacy review.
python3 - "$log_file" <<'PY'
import re
import sys
from pathlib import Path
text = Path(sys.argv[1]).read_text()
versions = re.findall(r'com\.microsoft\.onnxruntime:onnxruntime-android:([\d.]+)(?:\s+->\s+([\d.]+))?', text)
selected = {resolved or requested for requested, resolved in versions}
if selected != {'1.22.0'}:
    raise SystemExit(f'::error::Unexpected ONNX Runtime version(s): {selected}; re-audit telemetry before upgrading')
PY
model='app/src/main/assets/models/modnet_photographic_portrait_matting.onnx'
echo "07c308cf0fc7e6e8b2065a12ed7fc07e1de8febb7dc7839d7b7f15dd66584df9  $model" | sha256sum -c -
apk='app/build/outputs/apk/debug/app-debug.apk'
aapt="$ANDROID_HOME/build-tools/37.0.0/aapt"
"$aapt" list "$apk" | grep -Fx 'assets/models/modnet_photographic_portrait_matting.onnx'
"$aapt" dump permissions "$apk" | tee "$RUNNER_TEMP/wallpep-apk-permissions.txt"
"$aapt" dump xmltree "$apk" AndroidManifest.xml > "$RUNNER_TEMP/wallpep-apk-manifest.txt"
if grep -Eiq 'TelemetryInitializer|ai\.onnxruntime.*telemetry|com\.microsoft.*telemetry' "$RUNNER_TEMP/wallpep-apk-manifest.txt"; then
  echo '::error::Unexpected ONNX Runtime telemetry component in merged manifest.'
  exit 1
fi
# AndroidX Core's existing signature-only receiver permission was already in Phase 1.
if grep '^uses-permission:' "$RUNNER_TEMP/wallpep-apk-permissions.txt" | \
  grep -v "name='dev.wallpep.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"; then
  echo '::error::Unexpected merged APK permission.'
  exit 1
fi
