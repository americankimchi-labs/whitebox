#!/bin/sh
# One command: verifies a recorded White Box chain, then a copy with one byte changed.
#   tools/tamper-demo/run.sh                      # the recorded live emulator run in docs/artifacts/live-run2-demo
#   tools/tamper-demo/run.sh path/to/chain-dir    # any directory with chain.log + seals.log
cd "$(dirname "$0")/../.." || exit 1
if [ $# -gt 0 ]; then exec ./gradlew -q :tamper-demo:run --args="$*"; else exec ./gradlew -q :tamper-demo:run; fi
