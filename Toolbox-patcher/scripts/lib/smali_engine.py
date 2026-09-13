#!/usr/bin/env python3
"""
smali_engine.py - Register-aware framework patcher for Kaorios Toolbox.

Why this file exists
--------------------
The previous patcher injected smali with hardcoded register numbers, e.g. it
looked for `const/4 v4, 0x0` followed by `aput-object v2, v3, v4`, and it wrote
its scratch values into `v0` / `v1` without ever touching `.registers`.

That only works when the target ROM happens to compile the method with exactly
the same register layout as the ROM the snippet was copied from. On any other
Android version the anchor is not found, or - worse - the injected code reuses
a register that already holds a parameter, which crashes system_server.

This engine removes every hardcoded register assumption:

  * it locates a method by signature instead of by register numbers,
  * it reads the real `.registers` / `.locals` count,
  * it grows the frame by the number of scratch locals a snippet needs,
  * it resolves parameter registers (`p0`, `p1`, ...) from the descriptor,
  * it captures the actual return register the ROM uses.

The result is a patch that applies to any Android version where the hook
classes exist, including Android 12.

Usage
-----
  python3 smali_engine.py inject --decompile-dir DIR --source DIR
  python3 smali_engine.py patch  --decompile-dir DIR [--sdk 31] [--dry-run]

Exit code is 0 only when every required patch is in place.
"""

from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import sys
from dataclasses import dataclass
from string import Template

# --------------------------------------------------------------------------
# Hook contract
# --------------------------------------------------------------------------
# These classes ship in Toolbox-patcher/kaorios_toolbox/utils/kaorios and are
# injected into the last smali bucket of framework.jar.
UTIL_PACKAGE = "com/android/internal/util/kaorios"
UTIL_PATH = "com/android/internal/util/kaorios"

PROPS_UTILS = f"L{UTIL_PACKAGE}/KaoriPropsUtils;"
KEYBOX_HOOKS = f"L{UTIL_PACKAGE}/KaoriKeyboxHooks;"
FEATURE_OVERRIDES = f"L{UTIL_PACKAGE}/KaoriFeatureOverrides;"

# Any method body already containing this token is considered patched.
MARKER = UTIL_PACKAGE

# --------------------------------------------------------------------------
# Anchors
# --------------------------------------------------------------------------
ANCHOR_AFTER_REGISTERS = "after-registers"
ANCHOR_BEFORE_FINAL_RETURN = "before-final-return"

# --------------------------------------------------------------------------
# Snippets
# --------------------------------------------------------------------------
# Placeholders understood by the engine:
#   $r0 .. $r3   scratch locals, allocated at the bottom of the local range
#   $p0 .. $p3   the i-th parameter register, resolved from the descriptor
#   $ret         the register used by the method's final return-object
#
# Scratch locals stay at v0.. so they remain inside the 4-bit range and the
# 35c form of `invoke-*` keeps working without needing /range.

SNIPPET_FEATURE_OVERRIDE = """\
:try_start_kaorios
invoke-static {}, Landroid/app/ActivityThread;->currentPackageName()Ljava/lang/String;
move-result-object $r0
iget-object $r1, p0, Landroid/app/ApplicationPackageManager;->mContext:Landroid/app/ContextImpl;
invoke-static {$r1, $p1, $r0}, ${FEATURE_OVERRIDES}->getOverride(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;
move-result-object $r0
:try_end_kaorios
.catchall {:try_start_kaorios .. :try_end_kaorios} :catchall_kaorios
goto :kaorios_done
:catchall_kaorios
const/4 $r0, 0x0
:kaorios_done
if-eqz $r0, :kaorios_skip
invoke-virtual {$r0}, Ljava/lang/Boolean;->booleanValue()Z
move-result $r1
return $r1
:kaorios_skip
"""

SNIPPET_INIT_CONTEXT = """\
invoke-static {$p1}, ${PROPS_UTILS}->KaoriProps(Landroid/content/Context;)V
"""

SNIPPET_KEYBOX_KEY_ENTRY = """\
invoke-static {$ret}, ${KEYBOX_HOOKS}->KaoriGetKeyEntry(Landroid/system/keystore2/KeyEntryResponse;)Landroid/system/keystore2/KeyEntryResponse;
move-result-object $ret
"""

SNIPPET_CERT_CHAIN_MARK = """\
invoke-static {}, ${PROPS_UTILS}->KaoriGetCertificateChain()V
"""

SNIPPET_CERT_CHAIN = """\
invoke-static {$ret}, ${KEYBOX_HOOKS}->KaoriGetCertificateChain([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
move-result-object $ret
"""


# --------------------------------------------------------------------------
# Patch table
# --------------------------------------------------------------------------
@dataclass
class Op:
    """A single insertion into a method body."""

    anchor: str
    body: str
    locals_needed: int = 0
    # Index of the `Context` parameter in this method's signature. It differs
    # between overloads, so the snippet's `$p1` alias is rewritten from here.
    param_index: int = 1


@dataclass
class MethodPatch:
    """A hook applied to one method of one framework class."""

    patch_id: str
    class_relpath: str
    method_desc: str
    ops: list[Op]
    is_static: bool = False
    exclude: tuple[str, ...] = ()
    required: bool = True
    note: str = ""


PATCHES: list[MethodPatch] = [
    MethodPatch(
        patch_id="instrumentation.newApplication(Class,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=SNIPPET_INIT_CONTEXT)],
        note="Initialises the Kaorios context before the Application is created.",
    ),
    MethodPatch(
        patch_id="instrumentation.newApplication(ClassLoader,String,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=SNIPPET_INIT_CONTEXT, param_index=2)],
        note="Same as above for the ClassLoader overload (Context is p2).",
    ),
    MethodPatch(
        patch_id="applicationpackagemanager.hasSystemFeature(String,int)",
        class_relpath="android/app/ApplicationPackageManager.smali",
        method_desc="hasSystemFeature(Ljava/lang/String;I)Z",
        ops=[Op(anchor=ANCHOR_AFTER_REGISTERS, body=SNIPPET_FEATURE_OVERRIDE, locals_needed=2)],
        exclude=("lambda", "$$ExternalSyntheticLambda"),
        note="Per-app feature overrides. Wrapped in try/catch so a failure falls "
        "back to stock behaviour instead of bootlooping.",
    ),
    MethodPatch(
        patch_id="keystore2.getKeyEntry",
        class_relpath="android/security/KeyStore2.smali",
        method_desc="getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=SNIPPET_KEYBOX_KEY_ENTRY)],
        exclude=("lambda", "$$ExternalSyntheticLambda"),
        note="Keybox attestation spoofing.",
    ),
    MethodPatch(
        patch_id="androidkeystorespi.engineGetCertificateChain",
        class_relpath="android/security/keystore2/AndroidKeyStoreSpi.smali",
        method_desc="engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
        ops=[
            Op(anchor=ANCHOR_AFTER_REGISTERS, body=SNIPPET_CERT_CHAIN_MARK),
            Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=SNIPPET_CERT_CHAIN),
        ],
        note="Marks the call site and swaps the returned chain for the spoofed one.",
    ),
]


# --------------------------------------------------------------------------
# smali helpers
# --------------------------------------------------------------------------
REGISTERS_RE = re.compile(r"^\s*\.(registers|locals)\s+(\d+)\s*$")
METHOD_RE = re.compile(r"^\s*\.method\b")
END_METHOD_RE = re.compile(r"^\s*\.end method\b")
RETURN_RE = re.compile(r"^\s*return(?:-object|-wide)?\s+(v\d+|p\d+)\s*$")
INVOKE_35C_RE = re.compile(r"^\s*invoke-(?:static|virtual|direct|super|interface)\s+\{([^}]*)\}")


def indent_of(line: str) -> str:
    return re.match(r"[ \t]*", line).group(0)


def count_params(desc: str, is_static: bool) -> int:
    """Register width of a method's parameter list, including `this`."""
    left = desc.index("(")
    right = desc.index(")", left)
    params = desc[left + 1 : right]

    width = 0
    i = 0
    while i < len(params):
        ch = params[i]
        if ch == "[":
            i += 1
        elif ch == "L":
            i = params.index(";", i) + 1
            width += 1
        elif ch in ("J", "D"):
            i += 1
            width += 2
        else:
            i += 1
            width += 1

    if not is_static:
        width += 1
    return width


def param_reg(index: int, total: int, nparams: int) -> str:
    """`pN` -> concrete `vN` for a frame holding `total` registers."""
    return "v%d" % (total - nparams + index)


def to_v(token: str, total: int, nparams: int) -> str:
    token = token.strip()
    if token.startswith("p"):
        return param_reg(int(token[1:]), total, nparams)
    return token


def render(body: str, total: int, nparams: int, ret: str | None = None) -> str:
    mapping = {
        "r0": "v0",
        "r1": "v1",
        "r2": "v2",
        "r3": "v3",
        "p0": param_reg(0, total, nparams),
        "p1": param_reg(1, total, nparams),
        "p2": param_reg(2, total, nparams),
        "p3": param_reg(3, total, nparams),
        "FEATURE_OVERRIDES": FEATURE_OVERRIDES,
        "PROPS_UTILS": PROPS_UTILS,
        "KEYBOX_HOOKS": KEYBOX_HOOKS,
        "ret": ret if ret is not None else "",
    }
    return Template(body).substitute(mapping)


def find_method(lines: list[str], desc: str, exclude: tuple[str, ...]) -> tuple[int, int] | None:
    """Return (start, end) line indexes of the matching method, inclusive."""
    for i, line in enumerate(lines):
        if not METHOD_RE.match(line) or desc not in line:
            continue
        if any(token in line for token in exclude):
            continue
        for j in range(i + 1, len(lines)):
            if END_METHOD_RE.match(lines[j]):
                return i, j
        return None
    return None


def last_smali_dir(root: str) -> str:
    """Highest-numbered smali bucket, the safest home for extra classes."""
    best_rank = -1
    best_name = None
    pattern = re.compile(r"^(smali|classes)(?:_classes(\d+))?$")

    for name in sorted(os.listdir(root)):
        if not os.path.isdir(os.path.join(root, name)):
            continue
        m = pattern.match(name)
        if not m:
            continue
        rank = int(m.group(2)) if m.group(2) else 1
        if rank > best_rank:
            best_rank = rank
            best_name = name

    if best_name is None:
        raise RuntimeError("no smali bucket found in %s" % root)
    return os.path.join(root, best_name)


def check_register_encoding(lines: list[str]) -> list[str]:
    """
    Verify that every non-range invoke only references registers 0..15.

    The 35c instruction format stores registers in 4 bits, so a snippet mixing
    a high parameter register into a plain `invoke-*` would not assemble.
    """
    problems = []
    for ln in lines:
        m = INVOKE_35C_RE.match(ln)
        if not m:
            continue
        for token in m.group(1).split(","):
            token = token.strip()
            if re.fullmatch(r"v\d+", token) and int(token[1:]) > 15:
                problems.append("%s uses %s (35c allows v0-v15)" % (ln.strip(), token))
    return problems


def params_written_as_v(lines: list[str], start: int, end: int, total: int, nparams: int) -> list[str]:
    """
    Detect parameter registers that the decompiler emitted in `vN` form.

    Growing `.registers` shifts every parameter up by the growth amount. That is
    safe for `pN` references, but a body that addresses a parameter as `vN`
    would silently start reading a local instead. Refuse to grow in that case
    rather than produce a bootlooping framework.
    """
    first_param = total - nparams
    hits = []
    pattern = re.compile(r"\bv(\d+)\b")
    for i in range(start, end):
        for num in pattern.findall(lines[i]):
            if first_param <= int(num) < total:
                hits.append("v%s" % num)
    return sorted(set(hits))


# --------------------------------------------------------------------------
# Injection
# --------------------------------------------------------------------------
def inject_utilities(decompile_dir: str, source_dir: str) -> dict:
    if not os.path.isdir(source_dir):
        return {"status": "failed", "detail": "source dir not found: %s" % source_dir}

    target = os.path.join(last_smali_dir(decompile_dir), UTIL_PATH)
    os.makedirs(target, exist_ok=True)

    copied = 0
    for name in sorted(os.listdir(source_dir)):
        src = os.path.join(source_dir, name)
        if os.path.isfile(src) and name.endswith(".smali"):
            shutil.copy2(src, os.path.join(target, name))
            copied += 1

    if not copied:
        return {"status": "failed", "detail": "no .smali files in %s" % source_dir}

    return {
        "status": "patched",
        "detail": "injected %d hook classes into %s" % (copied, target),
        "count": copied,
        "target": target,
    }


# --------------------------------------------------------------------------
# Patching
# --------------------------------------------------------------------------
@dataclass
class Result:
    patch_id: str
    status: str
    detail: str


def apply_op(lines: list[str], start: int, end: int, op: Op, total: int, nparams: int,
             param_index: int = 1) -> tuple[str, str]:
    """Apply one op to the already-located method span."""
    if op.anchor == ANCHOR_AFTER_REGISTERS:
        body = render(op.body, total, nparams)
        for i in range(start, end):
            if REGISTERS_RE.match(lines[i]):
                indent = indent_of(lines[i])
                block = [indent + ln if ln.strip() else "" for ln in body.splitlines()]
                lines[i + 1 : i + 1] = block + [""]
                return "patched", "inserted %d lines after `%s`" % (len(block), lines[i].strip())
        return "failed", "register directive not found"

    if op.anchor == ANCHOR_BEFORE_FINAL_RETURN:
        found = None
        for i in range(end - 1, start, -1):
            m = RETURN_RE.match(lines[i])
            if m:
                found = (i, m.group(1))
                break
        if found is None:
            return "failed", "no return instruction found"

        index, token = found
        reg = to_v(token, total, nparams)
        # `$p1` inside the snippet is a convenience alias for the Context
        # parameter, whose position differs between overloads.
        body = op.body.replace("{$p1}", "{%s}" % param_reg(param_index, total, nparams))
        resolved = render(body, total, nparams, ret=reg)
        indent = indent_of(lines[index])
        block = [indent + ln if ln.strip() else "" for ln in resolved.splitlines()]
        lines[index:index] = block + [""]
        return "patched", "inserted %d lines before final `%s`" % (len(block), lines[index + len(block) + 1].strip())

    return "failed", "unknown anchor %s" % op.anchor


def apply_patch(decompile_dir: str, patch: MethodPatch, dry_run: bool) -> Result:
    wanted_suffix = os.sep + os.path.join(*patch.class_relpath.split("/"))
    candidates = []
    for root, _dirs, files in os.walk(decompile_dir):
        if os.sep + "unknown" in root + os.sep:
            continue
        for name in files:
            full = os.path.join(root, name)
            if full.endswith(wanted_suffix):
                candidates.append(full)

    if not candidates:
        return Result(patch.patch_id, "not-found", "%s absent from this ROM" % patch.class_relpath)

    target_file = sorted(candidates)[0]
    with open(target_file, "r", encoding="utf-8", errors="surrogateescape") as fh:
        lines = fh.read().splitlines()

    span = find_method(lines, patch.method_desc, patch.exclude)
    if span is None:
        return Result(patch.patch_id, "not-found", "method `%s` absent" % patch.method_desc)

    start, end = span
    if MARKER in "\n".join(lines[start:end]):
        return Result(patch.patch_id, "already", "hook already present")

    nparams = count_params(patch.method_desc, patch.is_static)

    directive_index = None
    kind, declared = "registers", 0
    for i in range(start, end):
        m = REGISTERS_RE.match(lines[i])
        if m:
            directive_index, kind, declared = i, m.group(1), int(m.group(2))
            break
    if directive_index is None:
        return Result(patch.patch_id, "failed", "no .registers/.locals directive")

    total = declared if kind == "registers" else declared + nparams
    locals_now = total - nparams

    needed = max((op.locals_needed for op in patch.ops), default=0)
    if locals_now < needed:
        growth = needed - locals_now
        unsafe = params_written_as_v(lines, start, end, total, nparams)
        if unsafe:
            return Result(
                patch.patch_id,
                "failed",
                "cannot grow frame: parameter register(s) %s are addressed in v-form; "
                "re-decompile with a decompiler that emits pN for parameters"
                % ", ".join(unsafe),
            )
        lines[directive_index] = "%s.%s %d" % (indent_of(lines[directive_index]), kind, declared + growth)
        total += growth

    # Bottom-up: the final return sits below the register directive, so
    # patching it first keeps the directive index valid.
    details = []
    ordered = sorted(
        patch.ops,
        key=lambda op: 0 if op.anchor == ANCHOR_AFTER_REGISTERS else 1,
        reverse=True,
    )
    for op in ordered:
        span = find_method(lines, patch.method_desc, patch.exclude)
        if span is None:
            return Result(patch.patch_id, "failed", "method lost after edit")
        status, detail = apply_op(lines, span[0], span[1], op, total, nparams, op.param_index)
        if status != "patched":
            return Result(patch.patch_id, "failed", detail)
        details.append(detail)

    problems = check_register_encoding(lines)
    if problems:
        return Result(patch.patch_id, "failed", "; ".join(problems))

    if not dry_run:
        with open(target_file, "w", encoding="utf-8", errors="surrogateescape", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")

    prefix = "dry-run: " if dry_run else ""
    return Result(patch.patch_id, "patched", prefix + " | ".join(details))


def run_patches(decompile_dir: str, dry_run: bool) -> tuple[list[Result], bool]:
    results = [apply_patch(decompile_dir, patch, dry_run) for patch in PATCHES]
    ok = all(
        result.status in ("patched", "already")
        for patch, result in zip(PATCHES, results)
        if patch.required
    )
    return results, ok


# --------------------------------------------------------------------------
# CLI
# --------------------------------------------------------------------------
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Kaorios Toolbox smali patch engine")
    sub = parser.add_subparsers(dest="command", required=True)

    p_inject = sub.add_parser("inject", help="copy hook classes into framework.jar")
    p_inject.add_argument("--decompile-dir", required=True)
    p_inject.add_argument("--source", required=True)
    p_inject.add_argument("--json", action="store_true")

    p_patch = sub.add_parser("patch", help="apply the framework hooks")
    p_patch.add_argument("--decompile-dir", required=True)
    p_patch.add_argument("--sdk", type=int, default=None)
    p_patch.add_argument("--dry-run", action="store_true")
    p_patch.add_argument("--json", action="store_true")

    args = parser.parse_args(argv)

    if args.command == "inject":
        result = inject_utilities(args.decompile_dir, args.source)
        if args.json:
            print(json.dumps(result))
        else:
            print("[%s] %s" % (result["status"], result["detail"]))
        return 0 if result["status"] == "patched" else 1

    results, ok = run_patches(args.decompile_dir, args.dry_run)

    if args.json:
        print(json.dumps([r.__dict__ for r in results], indent=2))
    else:
        width = max(len(r.patch_id) for r in results)
        for r in results:
            print("[%-9s] %-*s  %s" % (r.status, width, r.patch_id, r.detail))

    if args.sdk is not None:
        print("[INFO] target SDK: %d" % args.sdk)

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
