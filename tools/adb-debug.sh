#!/usr/bin/env bash
#
# Drive the hook's debug mode over ADB.
#
# Verbose logging is the platform's own switch (Log.isLoggable), so it can be
# flipped without a rebuild. The hook then emits [farewell] key=value state dumps
# showing the resolved config and keybox state.
#
#   ./tools/adb-debug.sh              enable verbose and stream the hook's log
#   ./tools/adb-debug.sh --dump       stream only the [farewell] state dump lines
#   ./tools/adb-debug.sh --status     show whether verbose is currently on
#   ./tools/adb-debug.sh --off        switch verbose back off
#
# Requires adb on PATH and a connected device (adb devices).
set -euo pipefail

TAG="KeyStoreHooks"
PROP="log.tag.$TAG"

die() { echo "error: $*" >&2; exit 1; }
need_adb() { command -v adb >/dev/null 2>&1 || die "adb not found on PATH"; }

case "${1:-}" in
  --off)
    need_adb
    adb shell setprop "$PROP" '""' >/dev/null 2>&1 || adb shell "setprop $PROP \"\""
    echo "verbose off ($PROP cleared)"
    ;;
  --status)
    need_adb
    value="$(adb shell getprop "$PROP" 2>/dev/null | tr -d '\r')"
    if [ -n "$value" ]; then
      echo "verbose ON ($PROP=$value)"
    else
      echo "verbose off ($PROP unset)"
    fi
    ;;
  --dump)
    need_adb
    adb shell setprop "$PROP" DEBUG
    echo "verbose on; streaming [farewell] state dumps (ctrl-c to stop)..."
    adb logcat -s "$TAG" | grep --line-buffered -F '[farewell]'
    ;;
  "")
    need_adb
    adb shell setprop "$PROP" DEBUG
    echo "verbose on; streaming $TAG (ctrl-c to stop)..."
    adb logcat -s "$TAG"
    ;;
  *)
    echo "usage: $0 [--dump | --status | --off]" >&2
    exit 2
    ;;
esac
