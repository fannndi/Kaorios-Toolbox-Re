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

inject_kaorios_utility_classes() {
    local decompile_dir="$1"
    local kaorios_source="${SCRIPT_DIR}/../kaorios_toolbox/utils/kaorios"

    if [ ! -d "$kaorios_source" ]; then
        err "Hook classes not found at $kaorios_source"
        return 1
    fi

    local py
    py="$(kaorios_python)" || {
        err "No python interpreter found (set KAORIOS_PYTHON)"
        return 1
    }

    log "Injecting hook classes into framework.jar..."

    if ! "$py" "$ENGINE_PATH" inject \
        --decompile-dir "$decompile_dir" \
        --source "$kaorios_source"; then
        err "Failed to inject hook classes"
        return 1
    fi

    return 0
}

apply_kaorios_toolbox_patches() {
    local decompile_dir="$1"
    local sdk="${2:-}"
    local json="${3:-0}"
    local dry_run="${4:-0}"

    log "========================================="
    log "Applying Kaorios Toolbox patches"
    log "========================================="

    local py
    py="$(kaorios_python)" || {
        err "No python interpreter found (set KAORIOS_PYTHON)"
        return 1
    }

    # In dry-run mode the hook classes are not copied either, so nothing on
    # disk is touched.
    if [ "$dry_run" != "1" ]; then
        inject_kaorios_utility_classes "$decompile_dir" || return 1
    else
        log "Dry run: skipping hook class injection"
    fi

    local args=(patch --decompile-dir "$decompile_dir")
    if [ -n "$sdk" ]; then
        args+=(--sdk "$sdk")
    fi
    if [ "$json" = "1" ]; then
        args+=(--json)
    fi
    if [ "$dry_run" = "1" ]; then
        args+=(--dry-run)
    fi

    if ! "$py" "$ENGINE_PATH" "${args[@]}"; then
        err "One or more required hooks could not be applied"
        err "Check the per-patch status above — 'not-found' means the class or"
        err "method does not exist in this framework.jar."
        return 1
    fi

    log "All hooks applied"
    return 0
}
