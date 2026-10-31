#!/usr/bin/env bash
# Preserve the usual full job log, plus searchable check annotations for failures.
set -uo pipefail
log_file="$RUNNER_TEMP/wallpep-gradle.log"
./gradlew --no-daemon "$@" 2>&1 | tee "$log_file"
result=${PIPESTATUS[0]}
if (( result != 0 )); then
  python3 - "$log_file" <<'PY'
import sys
from pathlib import Path
lines = Path(sys.argv[1]).read_text(errors='replace').splitlines()
# Include compiler errors and the end of Gradle's failure summary, without flooding checks.
important = [line for line in lines if line.startswith('e: ') or ': error:' in line or 'What went wrong:' in line]
for line in list(dict.fromkeys(important + lines[-28:]))[-45:]:
    line = line.replace('%', '%25').replace('\r', '%0D').replace('\n', '%0A')
    if line.strip():
        print(f'::error title=Gradle diagnostic::{line}')
PY
fi
exit "$result"
