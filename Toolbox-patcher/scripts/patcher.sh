#!/usr/bin/env bash
#
# patcher.sh - patch a ROM's framework.jar (and optionally services.jar) with
# the Kaorios Toolbox hooks.
#
# Quick start
#   ./scripts/patcher.sh --sdk 31 framework.jar                      # Android 12
#   ./scripts/patcher.sh --sdk 33 --services-jar services.jar framework.jar
#   ./scripts/patcher.sh --sdk 31 --dry-run framework.jar
#
# Run from the Toolbox-patcher directory, or pass absolute paths.
#
# Two things differ from the previous revision. The target SDK level is an
# input rather than a hardcoded 35, and the hook profile is chosen from it:
#
#   legacy  Android 12 era, hooks android.security.KeyStore2.getKeyEntry
#   modern  Android 13+ era, hooks AndroidKeyStoreKeyPairGeneratorSpi
#
# The modern profile also needs com/android/server/SystemServer patched, which
# lives in services.jar — pass it with --services-jar.

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
Patch a framework.jar (and optionally services.jar) with the Kaorios hooks.

Usage:
  patcher.sh [options] [framework.jar]

Options:
  -j, --jar PATH          framework.jar to patch (default: ./framework.jar)
      --services-jar PATH also patch services.jar (required by the modern
                          profile, which hooks com.android.server.SystemServer)
  -s, --sdk N             target Android SDK level, e.g. 31 for Android 12.
                          If omitted the script tries build.prop and then a
                          heuristic on the decompiled tree.
  -p, --profile NAME      legacy | modern. Default: derived from the SDK level.
  -r, --rom-dir PATH      ROM root, used to find system/build.prop
      --dry-run           Report what would change without writing files
      --keep-work         Keep the decompiled trees
      --no-d8             Skip the D8 DEX optimisation step
      --no-module         Skip building the Magisk module zip
      --no-verify-hooks   Skip the static check that hook methods resolve
      --json              Emit the patch report as JSON
      --list-hooks        Print the hooks each profile applies, then exit
  -h, --help              Show this help

Exit codes:
  0  every required hook was applied
  1  a required hook failed, or a tool is missing
  2  bad arguments
EOF
}

JAR_PATH=""
SERVICES_JAR=""
SDK=""
PROFILE=""
ROM_DIR=""
DRY_RUN=0
KEEP_WORK=0
RUN_D8=1
BUILD_MODULE=1
VERIFY_HOOKS=1
JSON=0

while [ $# -gt 0 ]; do
    case "$1" in
        -j|--jar)         JAR_PATH="${2:-}"; shift 2 ;;
        --services-jar)   SERVICES_JAR="${2:-}"; shift 2 ;;
        -s|--sdk)         SDK="${2:-}"; shift 2 ;;
        -p|--profile)     PROFILE="${2:-}"; shift 2 ;;
        -r|--rom-dir)     ROM_DIR="${2:-}"; shift 2 ;;
        --dry-run)        DRY_RUN=1; shift ;;
        --keep-work)      KEEP_WORK=1; shift ;;
        --no-d8)          RUN_D8=0; shift ;;
        --no-module)      BUILD_MODULE=0; shift ;;
        --no-verify-hooks) VERIFY_HOOKS=0; shift ;;
        --json)           JSON=1; shift ;;
        --list-hooks)     kaorios_engine list; exit 0 ;;
        -h|--help)        usage; exit 0 ;;
        -*)               err "Unknown option: $1"; usage; exit 2 ;;
        *)                JAR_PATH="$1"; shift ;;
    esac
done

[ -n "$JAR_PATH" ] || JAR_PATH="$WORK_DIR/framework.jar"

# Validate arguments before touching the filesystem, so a typo in --sdk is
# reported as a typo rather than as a missing file.
if [ -n "$SDK" ] && ! printf '%s' "$SDK" | grep -Eq '^[0-9]+$'; then
    err "--sdk expects a number, got '$SDK'"
    exit 2
fi

if [ -n "$PROFILE" ] && [ "$PROFILE" != "legacy" ] && [ "$PROFILE" != "modern" ]; then
    err "--profile expects 'legacy' or 'modern', got '$PROFILE'"
    exit 2
fi

if [ ! -f "$JAR_PATH" ]; then
    err "framework.jar not found: $JAR_PATH"
    exit 1
fi

if [ -n "$SERVICES_JAR" ] && [ ! -f "$SERVICES_JAR" ]; then
    err "services.jar not found: $SERVICES_JAR"
    exit 1
fi

ensure_tools || exit 1

log "Kaorios Toolbox patcher"
log "  framework : $JAR_PATH"
[ -n "$SERVICES_JAR" ] && log "  services  : $SERVICES_JAR"
log "  tools     : $TOOLS_DIR"
log "  work dir  : $WORK_DIR"

# --------------------------------------------------------------------------
# Resolve the target SDK. This needs the decompiled tree for the heuristic, so
# the framework jar is decompiled first.
# --------------------------------------------------------------------------
FRAMEWORK_BASE="$(basename "$JAR_PATH" .jar)"
FRAMEWORK_DECOMPILE="$WORK_DIR/${FRAMEWORK_BASE}_decompile"

decompile_jar "$JAR_PATH" || {
    err "Failed to decompile $JAR_PATH"
    exit 1
}
if [ ! -d "$FRAMEWORK_DECOMPILE" ]; then
    err "Decompile reported success but $FRAMEWORK_DECOMPILE does not exist"
    exit 1
fi

if [ -z "$SDK" ]; then
    SDK="$(kaorios_detect_sdk "$JAR_PATH" "$FRAMEWORK_DECOMPILE" "$ROM_DIR" || true)"
fi

if [ -z "$SDK" ]; then
    err "Could not determine the target SDK level."
    err "Pass --sdk (31 = Android 12, 33 = Android 13, 34 = Android 14, 35 = Android 15)."
    exit 1
fi

if [ -z "$PROFILE" ]; then
    PROFILE="$(kaorios_profile_for_sdk "$SDK")"
fi

log "Target SDK  : $SDK (Android $(kaorios_sdk_to_android "$SDK"))"
log "Hook profile: $PROFILE"

if [ "$PROFILE" = "unsupported" ]; then
    err "SDK $SDK is below the supported range (28+)"
    exit 1
fi

MIN_API="$(kaorios_min_api_for_sdk "$SDK")"

# --------------------------------------------------------------------------
# Patch one jar end to end.
# --------------------------------------------------------------------------
PATCHED_JARS=()

patch_one_jar() {
    local jar_path="$1"
    local artifact="$2"
    local base decompile_dir patched_jar

    base="$(basename "$jar_path" .jar)"
    decompile_dir="$WORK_DIR/${base}_decompile"
    patched_jar="${base}_patched.jar"

    # The framework jar is already decompiled at this point.
    if [ ! -d "$decompile_dir" ]; then
        decompile_jar "$jar_path" || {
            err "Failed to decompile $jar_path"
            return 1
        }
        [ -d "$decompile_dir" ] || {
            err "Decompile reported success but $decompile_dir does not exist"
            return 1
        }
    fi

    apply_kaorios_toolbox_patches "$decompile_dir" "$SDK" "$JSON" "$DRY_RUN" "$PROFILE" "$artifact" "$VERIFY_HOOKS" || {
        err "Patching failed. The decompiled tree is at $decompile_dir"
        return 1
    }

    if [ "$DRY_RUN" -eq 1 ]; then
        return 0
    fi

    recompile_jar "$jar_path" || {
        err "Failed to recompile $jar_path"
        return 1
    }
    if [ ! -f "$WORK_DIR/$patched_jar" ]; then
        err "Recompile finished but $WORK_DIR/$patched_jar is missing"
        return 1
    fi

    if [ "$RUN_D8" -eq 1 ]; then
        log "Optimising $patched_jar with D8 (--min-api $MIN_API)"
        if ! d8_optimize_jar "$patched_jar" "$MIN_API"; then
            warn "D8 optimisation failed for $patched_jar — the unoptimised jar is still usable"
        fi
    else
        log "Skipping D8 optimisation (--no-d8)"
    fi

    PATCHED_JARS+=("$patched_jar")
    return 0
}

patch_one_jar "$JAR_PATH" "framework" || exit 1

# Whether the selected profile touches services.jar is decided by the engine's
# patch table, so this script does not duplicate that knowledge.
if [ -n "$SERVICES_JAR" ]; then
    if kaorios_engine hooks --profile "$PROFILE" --artifact services; then
        patch_one_jar "$SERVICES_JAR" "services" || exit 1
    else
        log "Profile '$PROFILE' has no services.jar hooks — skipping $SERVICES_JAR"
    fi
elif kaorios_engine hooks --profile "$PROFILE" --artifact services; then
    warn "The '$PROFILE' profile also hooks com.android.server.SystemServer, which"
    warn "lives in services.jar. Pass --services-jar to patch it."
fi

# --------------------------------------------------------------------------
# Module
# --------------------------------------------------------------------------
if [ "$DRY_RUN" -eq 1 ]; then
    log "Dry run complete — nothing was written"
else
    if [ "$BUILD_MODULE" -eq 1 ]; then
        # module.sh expects to run from the Toolbox-patcher root and reads
        # framework_patched.jar from there.
        (
            cd "$PROJECT_DIR" || exit 1
            if [ ! -f "${FRAMEWORK_BASE}_patched.jar" ]; then
                cp "$WORK_DIR/${FRAMEWORK_BASE}_patched.jar" . || exit 1
            fi
            source "$SCRIPT_DIR/core/module.sh"
            create_kaorios_module
        ) || warn "Module build failed"
    else
        log "Skipping module build (--no-module)"
    fi
fi

# --------------------------------------------------------------------------
# Clean up
# --------------------------------------------------------------------------
if [ "$KEEP_WORK" -eq 0 ]; then
    for jar in "${PATCHED_JARS[@]:-}"; do
        [ -n "$jar" ] || continue
        rm -rf "$WORK_DIR/$(basename "$jar" _patched.jar)_decompile"
    done
    rm -rf "$FRAMEWORK_DECOMPILE"
    log "Removed decompiled trees (use --keep-work to keep them)"
else
    log "Kept decompiled trees under $WORK_DIR"
fi

if [ "${#PATCHED_JARS[@]}" -gt 0 ]; then
    log "Done. Patched: ${PATCHED_JARS[*]}"
else
    log "Done."
fi
