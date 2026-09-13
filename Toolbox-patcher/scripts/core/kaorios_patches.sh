#!/usr/bin/env bash
# scripts/core/kaorios_patches.sh
#
# Thin orchestration layer over scripts/lib/smali_engine.py.
#
# This file used to carry four inline Python heredocs that matched on hardcoded
# register numbers (`const/4 v4, 0x0`, `aput-object v2, v3, v4`, `return-object
# v0`). Those patterns only exist on the exact ROM build the snippets were
# copied from, which is why the patch stopped working on other Android
# versions. All of that logic now lives in the engine, which resolves registers
# from the method's own frame instead of guessing.
#
# Everything here is plumbing: locate inputs, call the engine, report status.

: "${ENGINE_PATH:=${SCRIPT_DIR}/lib/smali_engine.py}"

kaorios_engine() {
    local py
    py="$(kaorios_python)" || {
        err "No python interpreter found (set KAORIOS_PYTHON)"
        return 1
    }
    "$py" "$(kaorios_native_path "$ENGINE_PATH")" "$@"
}

inject_kaorios_utility_classes() {
    # Only the legacy profile needs the hook classes injected: the modern
    # profile calls android.security.kaorios.KaoriosHook, which ships with the
    # V2.0.3+ release rather than with this repository.
    local decompile_dir="$1"
    local kaorios_source="${SCRIPT_DIR}/../kaorios_toolbox/utils/kaorios"

    if [ ! -d "$kaorios_source" ]; then
        err "Hook classes not found at $kaorios_source"
        return 1
    fi

    log "Injecting hook classes into framework.jar..."

    if ! kaorios_engine inject \
        --decompile-dir "$(kaorios_native_path "$decompile_dir")" \
        --source "$(kaorios_native_path "$kaorios_source")"; then
        err "Failed to inject hook classes"
        return 1
    fi

    return 0
}

# apply_kaorios_toolbox_patches <decompile_dir> <sdk> <json> <dry_run> <profile> <artifact>
apply_kaorios_toolbox_patches() {
    local decompile_dir="$1"
    local sdk="${2:-}"
    local json="${3:-0}"
    local dry_run="${4:-0}"
    local profile="${5:-legacy}"
    local artifact="${6:-framework}"

    log "========================================="
    log "Applying Kaorios Toolbox patches ($profile / $artifact)"
    log "========================================="

    if [ "$profile" = "legacy" ] && [ "$artifact" = "framework" ] && [ "$dry_run" != "1" ]; then
        inject_kaorios_utility_classes "$decompile_dir" || return 1
    elif [ "$profile" = "legacy" ] && [ "$artifact" = "framework" ]; then
        log "Dry run: skipping hook class injection"
    fi

    local args=(patch --decompile-dir "$(kaorios_native_path "$decompile_dir")" --profile "$profile" --artifact "$artifact")
    if [ -n "$sdk" ]; then
        args+=(--sdk "$sdk")
    fi
    if [ "$json" = "1" ]; then
        args+=(--json)
    fi
    if [ "$dry_run" = "1" ]; then
        args+=(--dry-run)
    fi

    if ! kaorios_engine "${args[@]}"; then
        err "One or more required hooks could not be applied"
        err "Check the per-patch status above — 'not-found' means the class or"
        err "method does not exist in this jar."
        return 1
    fi

    log "All hooks applied"
    return 0
}
