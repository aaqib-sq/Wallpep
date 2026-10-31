#!/usr/bin/env bash
# Install an exact Google command-line-tools archive and SDK packages into a clean SDK root.
set -uo pipefail
sdk="$RUNNER_TEMP/wallpep-android-sdk"
archive="$RUNNER_TEMP/android-command-line-tools.zip"
log_file="$RUNNER_TEMP/wallpep-sdk.log"
: > "$log_file"

run_step() {
  local label="$1"
  shift
  : > "$log_file"
  echo "--- $label ---" | tee -a "$log_file"
  "$@" 2>&1 | tee -a "$log_file"
  local result=${PIPESTATUS[0]}
  if (( result != 0 )); then
    echo "::error title=Android SDK setup::$label exited with status $result"
    python3 - "$log_file" "$label" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()[-25:]
for line in lines:
    line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    if line.strip():
        print(f'::error title=Android SDK setup::{line}')
PY
    exit "$result"
  fi
}

run_step 'Create SDK directory' mkdir -p "$sdk/cmdline-tools"
run_step 'Download Google command-line tools 13114758' curl -fsSL --retry 3 --connect-timeout 30 \
  https://dl.google.com/android/repository/commandlinetools-linux-13114758_latest.zip -o "$archive"
run_step 'Verify command-line tools SHA-256' bash -c \
  'echo "7ec965280a073311c339e571cd5de778b9975026cfcbe79f2b1cdcb1e15317ee  $1" | sha256sum -c -' _ "$archive"
run_step 'Unpack command-line tools' unzip -q "$archive" -d "$sdk/cmdline-tools"
run_step 'Place command-line tools' mv "$sdk/cmdline-tools/cmdline-tools" "$sdk/cmdline-tools/latest"
manager="$sdk/cmdline-tools/latest/bin/sdkmanager"
# A finite input avoids a broken-pipe status from `yes` while accepting SDK licenses.
run_step 'Accept Android SDK licenses' bash -c \
  'printf "y\n%.0s" {1..100} | "$1" --sdk_root="$2" --licenses' _ "$manager" "$sdk"
run_step 'Install pinned platform and build tools' "$manager" --sdk_root="$sdk" \
  --verbose 'platforms;android-37.0' 'build-tools;37.0.0'
{
  echo "ANDROID_HOME=$sdk"
  echo "ANDROID_SDK_ROOT=$sdk"
} >> "$GITHUB_ENV"
echo "$sdk/cmdline-tools/latest/bin" >> "$GITHUB_PATH"
