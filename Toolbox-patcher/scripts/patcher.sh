#!/usr/bin/env bash
#
# patcher.sh - patch a ROM's framework.jar with the Kaorios Toolbox hooks.
#
# Quick start
#   ./scripts/patcher.sh --sdk 31 framework.jar          # Android 12
#   ./scripts/patcher.sh --sdk 31 --dry-run framework.jar
#
# Run from the Toolbox-patcher directory, or pass an absolute --jar path.
#
# The important behaviour change compared to the previous revision: the target
# SDK level is now an input, not a hardcoded 35. Everything downstream
# (D8 --min-api, and which hooks the engine expects to find) follows from it.

set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

# tools/ lives next to the scripts, not in the caller's cwd.
: "${TOOLS_DIR:=${PROJECT_DIR}/tools}"
: "${WORK_DIR:=$(pwd)}"
: "${BACKUP_DIR:=${WORK_DIR}/backup}"
export TOOLS_DIR WORK_DIR BACKUP_DIR

source "$SCRIPT_DIR/core/logging.sh"
source "$SCRIPT_DIR/core/tools.sh"
source "$SCRIPT_DIR/core/apk_ops.sh"
source "$SCRIPT_DIR/core/version.sh"
source "$SCRIPT_DIR/core/kaorios_patches.sh"

usage() {
    cat <<'EOF'
Patch a framework.jar with the Kaorios Toolbox hooks.

Usage:
  patcher.sh [options] [framework.jar]

Options:
  -j, --jar PATH      framework.jar to patch (default: ./framework.jar)
  -s, --sdk N         target Android SDK level, e.g. 31 for Android 12.
                      If omitted the script tries build.prop and then a
                      heuristic on the decompiled tree.
  -r, --rom-dir PATH  ROM root, used to find system/build.prop
      --dry-run       Report what would change without writing files
      --keep-work     Keep the decompiled tree (framework_decompile/)
      --no-d8         Skip the D8 DEX optimisation step
      --no-module     Skip building the Magisk module zip
      --json          Emit the patch report as JSON
  -h, --help          Show this help

Exit codes:
  0  every required hook was applied
  1  a required hook failed, or a tool is missing
  2  bad arguments
EOF
}

JAR_PATH=""
SDK=""
ROM_DIR=""
DRY_RUN=0
KEEP_WORK=0
RUN_D8=1
BUILD_MODULE=1
JSON=0

while [ $# -gt 0 ]; do
    case "$1" in
        -j|--jar)      JAR_PATH="${2:-}"; shift 2 ;;
        -s|--sdk)      SDK="${2:-}"; shift 2 ;;
        -r|--rom-dir)  ROM_DIR="${2:-}"; shift 2 ;;
        --dry-run)     DRY_RUN=1; shift ;;
        --keep-work)   KEEP_WORK=1; shift ;;
        --no-d8)       RUN_D8=0; shift ;;
        --no-module)   BUILD_MODULE=0; shift ;;
        --json)        JSON=1; shift ;;
        -h|--help)     usage; exit 0 ;;
        -*)            err "Unknown option: $1"; usage; exit 2 ;;
        *)             JAR_PATH="$1"; shift ;;
    esac
done

[ -n "$JAR_PATH" ] || JAR_PATH="$WORK_DIR/framework.jar"

# Validate arguments before touching the filesystem, so a typo in --sdk is
# reported as a typo rather than as a missing file.
if [ -n "$SDK" ] && ! printf '%s' "$SDK" | grep -Eq '^[0-9]+$'; then
    err "--sdk expects a number, got '$SDK'"
    exit 2
fi

if [ ! -f "$JAR_PATH" ]; then
    err "framework.jar not found: $JAR_PATH"
    exit 1
fi

ensure_tools || exit 1

log "Kaorios Toolbox patcher"
log "  jar      : $JAR_PATH"
log "  tools    : $TOOLS_DIR"
log "  work dir : $WORK_DIR"

# --------------------------------------------------------------------------
# 1. Decompile
# --------------------------------------------------------------------------
# Derive the paths instead of capturing them from the helper: apktool writes
# progress lines to stdout, so `$(decompile_jar ...)` would return the log text
# glued to the directory name.
BASE_NAME="$(basename "$JAR_PATH" .jar)"
DECOMPILE_DIR="$WORK_DIR/${BASE_NAME}_decompile"
PATCHED_JAR="${BASE_NAME}_patched.jar"

decompile_jar "$JAR_PATH" || {
    err "Failed to decompile $JAR_PATH"
    exit 1
}

if [ ! -d "$DECOMPILE_DIR" ]; then
    err "Decompile reported success but $DECOMPILE_DIR does not exist"
    exit 1
fi

# --------------------------------------------------------------------------
# 2. Resolve the target SDK
# --------------------------------------------------------------------------
if [ -z "$SDK" ]; then
    SDK="$(kaorios_detect_sdk "$JAR_PATH" "$DECOMPILE_DIR" "$ROM_DIR" || true)"
fi

PROFILE="$(kaorios_profile_for_sdk "$SDK")"

if [ -z "$SDK" ]; then
    err "Could not determine the target SDK level."
    err "Pass --sdk (31 for Android 12, 33 for Android 13, 34 for Android 14, 35 for Android 15)."
    exit 1
fi

log "Target SDK : $SDK (Android $(kaorios_sdk_to_android "$SDK"))"
log "Hook profile: $PROFILE"

if [ "$PROFILE" = "unsupported" ]; then
    err "SDK $SDK is below the supported range (28+)"
    exit 1
fi

if [ "$PROFILE" = "modern" ]; then
    warn "SDK $SDK normally uses the android.security.kaorios.KaoriosHook design"
    warn "from V2.0.3+. This branch ships the legacy hook classes, so the patch"
    warn "will only work if you also swap in the matching hook payload."
fi

# --------------------------------------------------------------------------
# 3. Apply hooks
# --------------------------------------------------------------------------
PATCH_RC=0
apply_kaorios_toolbox_patches "$DECOMPILE_DIR" "$SDK" "$JSON" "$DRY_RUN" || PATCH_RC=$?

if [ "$PATCH_RC" -ne 0 ]; then
    err "Patching failed. The decompiled tree is at $DECOMPILE_DIR"
    exit 1
fi

if [ "$DRY_RUN" -eq 1 ]; then
    log "Dry run complete — nothing was written"
    if [ "$KEEP_WORK" -eq 0 ]; then
        rm -rf "$DECOMPILE_DIR"
    fi
    exit 0
fi

# --------------------------------------------------------------------------
# 4. Recompile
# --------------------------------------------------------------------------
recompile_jar "$JAR_PATH" || {
    err "Failed to recompile"
    exit 1
}

if [ ! -f "$WORK_DIR/$PATCHED_JAR" ]; then
    err "Recompile finished but $WORK_DIR/$PATCHED_JAR is missing"
    exit 1
fi

# --------------------------------------------------------------------------
# 5. D8 optimisation
# --------------------------------------------------------------------------
MIN_API="$(kaorios_min_api_for_sdk "$SDK")"

if [ "$RUN_D8" -eq 1 ]; then
    log "Optimising with D8 (--min-api $MIN_API)"
    if ! d8_optimize_jar "$PATCHED_JAR" "$MIN_API"; then
        warn "D8 optimisation failed or was skipped — the unoptimised jar is still usable"
    fi
else
    log "Skipping D8 optimisation (--no-d8)"
fi

# --------------------------------------------------------------------------
# 6. Module
# --------------------------------------------------------------------------
if [ "$BUILD_MODULE" -eq 1 ]; then
    # module.sh expects to run from the Toolbox-patcher root and reads
    # framework_patched.jar from there.
    (
        cd "$PROJECT_DIR" || exit 1
        if [ ! -f "$PATCHED_JAR" ]; then
            cp "$WORK_DIR/$PATCHED_JAR" . || exit 1
        fi
        source "$SCRIPT_DIR/core/module.sh"
        create_kaorios_module
    ) || warn "Module build failed"
else
    log "Skipping module build (--no-module)"
fi

# --------------------------------------------------------------------------
# 7. Clean up
# --------------------------------------------------------------------------
if [ "$KEEP_WORK" -eq 0 ]; then
    rm -rf "$DECOMPILE_DIR"
    log "Removed $DECOMPILE_DIR (use --keep-work to keep it)"
else
    log "Kept $DECOMPILE_DIR"
fi

log "Done. Patched jar: $WORK_DIR/$PATCHED_JAR"
