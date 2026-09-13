#!/usr/bin/env bash
# scripts/core/version.sh
# Target Android version detection.
#
# Why this exists
# ---------------
# The old patcher hardcoded `--min-api 35` in the D8 step. That value is the
# SDK level of Android 15, so every framework.jar was optimised as if it were
# going to run on Android 15. D8 uses --min-api to decide how much desugaring
# and API backporting to perform, so patching an Android 12 framework with
# --min-api 35 produces code that is not guaranteed to run on API 31.
#
# The fix is to derive --min-api from the SDK level of the ROM being patched.
# This module is responsible for finding that number.

# Detection precedence:
#   1. --sdk on the command line            (authoritative)
#   2. KAORIOS_TARGET_SDK environment var   (authoritative)
#   3. ro.build.version.sdk from a build.prop
#   4. signature heuristics on the decompiled tree (a guess, clearly flagged)
kaorios_read_build_prop() {
    # Print the SDK level found in a build.prop, or nothing.
    local prop_file="$1"

    [ -f "$prop_file" ] || return 1

    local sdk
    sdk=$(sed -n 's/^ro\.build\.version\.sdk=\([0-9]\{1,3\}\).*/\1/p' "$prop_file" | tail -n1)
    if [ -z "$sdk" ]; then
        sdk=$(sed -n 's/^ro\.system\.build\.version\.sdk=\([0-9]\{1,3\}\).*/\1/p' "$prop_file" | tail -n1)
    fi
    if [ -z "$sdk" ]; then
        sdk=$(sed -n 's/^ro\.vendor\.build\.version\.sdk=\([0-9]\{1,3\}\).*/\1/p' "$prop_file" | tail -n1)
    fi

    [ -n "$sdk" ] && printf '%s\n' "$sdk"
}

kaorios_find_build_prop() {
    # Look for a build.prop in the usual places around the jar.
    local jar_path="$1"
    local rom_dir="${2:-}"
    local jar_dir
    jar_dir="$(cd "$(dirname "$jar_path")" && pwd)"

    local candidate
    for candidate in \
        "$rom_dir/system/build.prop" \
        "$rom_dir/build.prop" \
        "$jar_dir/../build.prop" \
        "$jar_dir/build.prop" \
        "$jar_dir/../../../system/build.prop"
    do
        if [ -f "$candidate" ]; then
            printf '%s\n' "$candidate"
            return 0
        fi
    done
    return 1
}

kaorios_sdk_from_smali() {
    # Best-effort guess from which keystore classes the ROM ships.
    #
    #   android/security/keystore/...        -> the pre-keystore2 stack (<= API 30)
    #   android/security/keystore2/...       -> keystore2 became default in API 31
    #
    # This cannot distinguish 31 from 33/34, so callers must treat the result as
    # a lower bound and say so.
    local decompile_dir="$1"
    local found=""

    if find "$decompile_dir" -type f \
        -path "*/android/security/keystore2/AndroidKeyStoreSpi.smali" -print -quit 2>/dev/null | grep -q .; then
        found=31
    elif find "$decompile_dir" -type f \
        -path "*/android/security/keystore/AndroidKeyStoreSpi.smali" -print -quit 2>/dev/null | grep -q .; then
        found=30
    fi

    [ -n "$found" ] && printf '%s\n' "$found"
}

kaorios_detect_sdk() {
    # Echo the detected SDK level, or nothing when it cannot be determined.
    local jar_path="${1:-}"
    local decompile_dir="${2:-}"
    local rom_dir="${3:-}"

    if [ -n "${KAORIOS_TARGET_SDK:-}" ]; then
        printf '%s\n' "$KAORIOS_TARGET_SDK"
        return 0
    fi

    if [ -n "$jar_path" ]; then
        local prop
        prop="$(kaorios_find_build_prop "$jar_path" "$rom_dir" 2>/dev/null || true)"
        if [ -n "$prop" ]; then
            local sdk
            sdk="$(kaorios_read_build_prop "$prop" || true)"
            if [ -n "$sdk" ]; then
                warn "SDK $sdk read from $prop"
                printf '%s\n' "$sdk"
                return 0
            fi
        fi
    fi

    if [ -n "$decompile_dir" ] && [ -d "$decompile_dir" ]; then
        local sdk
        sdk="$(kaorios_sdk_from_smali "$decompile_dir" || true)"
        if [ -n "$sdk" ]; then
            warn "SDK guessed as >= $sdk from the keystore layout — pass --sdk to be exact"
            printf '%s\n' "$sdk"
            return 0
        fi
    fi

    return 1
}

kaorios_sdk_to_android() {
    case "$1" in
        28) echo "9" ;;
        29) echo "10" ;;
        30) echo "11" ;;
        31) echo "12" ;;
        32) echo "12L" ;;
        33) echo "13" ;;
        34) echo "14" ;;
        35) echo "15" ;;
        36) echo "16" ;;
        *)  echo "?" ;;
    esac
}

kaorios_profile_for_sdk() {
    # Which hook set the ROM needs.
    #
    #   legacy : android.security.KeyStore2.getKeyEntry + AndroidKeyStoreSpi,
    #            driven by the KaoriPropsUtils / KaoriKeyboxHooks classes that
    #            ship in this branch. This is the Android 12 path.
    #   modern : the android.security.kaorios.KaoriosHook design used from
    #            V2.0.3 onwards, which needs a different hook payload.
    local sdk="$1"

    if [ -z "$sdk" ]; then
        echo "unknown"
        return 0
    fi

    if [ "$sdk" -ge 33 ]; then
        echo "modern"
    elif [ "$sdk" -ge 28 ]; then
        echo "legacy"
    else
        echo "unsupported"
    fi
}

kaorios_min_api_for_sdk() {
    # D8 --min-api must never be higher than the ROM it will run on.
    local sdk="$1"
    if [ -z "$sdk" ]; then
        echo ""
        return 1
    fi
    echo "$sdk"
}
