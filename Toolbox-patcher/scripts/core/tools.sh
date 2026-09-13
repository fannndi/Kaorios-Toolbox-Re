#!/usr/bin/env bash
# scripts/core/tools.sh
# Environment initialization and tool checks

init_env() {
    # Allow overriding from environment before calling init_env
    : "${TOOLS_DIR:=${PWD}/tools}"
    : "${WORK_DIR:=${PWD}}"
    : "${BACKUP_DIR:=${WORK_DIR}/backup}"
    : "${MAGISK_TEMPLATE_DIR:=magisk_module}" # template dir for create_magisk_module
    mkdir -p "$BACKUP_DIR"
}

kaorios_native_path() {
    # Translate an MSYS/Git-Bash path (e.g. /c/Users/x) into the native form a
    # Windows binary can open. Native tools (python, java, d8) cannot resolve
    # /c/... and silently look for it relative to the current drive root, which
    # shows up as errors like "C:\c\Users\x: No such file or directory".
    # No-op on Linux/macOS, where cygpath does not exist.
    local p="$1"
    if command -v cygpath >/dev/null 2>&1; then
        cygpath -w "$p" 2>/dev/null || printf '%s\n' "$p"
    else
        printf '%s\n' "$p"
    fi
}

kaorios_python() {
    # Resolve a python interpreter for the patch engine.
    # Honours KAORIOS_PYTHON, then python3, then python.
    if [ -n "${KAORIOS_PYTHON:-}" ]; then
        printf '%s\n' "$KAORIOS_PYTHON"
        return 0
    fi
    if command -v python3 >/dev/null 2>&1; then
        printf 'python3\n'
        return 0
    fi
    if command -v python >/dev/null 2>&1; then
        printf 'python\n'
        return 0
    fi
    return 1
}

ensure_tools() {
    # Checks for java, apktool.jar and 7z (optional)
    if ! command -v java >/dev/null 2>&1; then
        err "java not found in PATH"
        return 1
    fi

    if [ ! -f "${TOOLS_DIR}/apktool.jar" ]; then
        err "apktool.jar not found at ${TOOLS_DIR}/apktool.jar"
        return 1
    fi

    if ! kaorios_python >/dev/null 2>&1; then
        err "No python interpreter found (needed by the smali patch engine)."
        err "Install python3 or point KAORIOS_PYTHON at one."
        return 1
    fi

    if ! command -v 7z >/dev/null 2>&1; then
        warn "7z not found in PATH — create_magisk_module will try to use zip if available"
    fi

    # Check for d8 (needed for optimization)
    if [ -z "${D8_CMD:-}" ]; then
        if command -v d8 >/dev/null 2>&1; then
            export D8_CMD="d8"
        elif [ -d "$HOME/android-sdk/build-tools" ]; then
            # Find the latest build-tools version
            local latest_build_tool
            latest_build_tool=$(ls -1 "$HOME/android-sdk/build-tools" | sort -V | tail -n1)
            if [ -n "$latest_build_tool" ] && [ -x "$HOME/android-sdk/build-tools/$latest_build_tool/d8" ]; then
                export D8_CMD="$HOME/android-sdk/build-tools/$latest_build_tool/d8"
                log "Found d8 at $D8_CMD"
            fi
        elif [ -n "${ANDROID_HOME:-}" ] && [ -d "${ANDROID_HOME}/build-tools" ]; then
             # Try ANDROID_HOME if set
            local latest_build_tool
            latest_build_tool=$(ls -1 "${ANDROID_HOME}/build-tools" | sort -V | tail -n1)
            if [ -n "$latest_build_tool" ] && [ -x "${ANDROID_HOME}/build-tools/$latest_build_tool/d8" ]; then
                export D8_CMD="${ANDROID_HOME}/build-tools/$latest_build_tool/d8"
                log "Found d8 at $D8_CMD"
            fi
        fi
    fi
    
    if [ -z "${D8_CMD:-}" ]; then
        warn "d8 not found. JAR optimization will be skipped."
    fi

    return 0
}
