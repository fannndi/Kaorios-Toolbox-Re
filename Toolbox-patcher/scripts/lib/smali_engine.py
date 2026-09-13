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
a register that already holds a parameter, which crashes the process.

The published V2.0.3+ guide has the same problem and works around it by asking
a human to do the arithmetic:

    "Increase the current register count by 1
     Replace vX with the register number at registers - 2"

This engine does that automatically, for every hook, on any ROM.

How it works
------------
  * a method is located by signature, never by register numbers,
  * the real `.registers` / `.locals` count is read,
  * the frame is grown by exactly the number of scratch locals a snippet needs,
  * `p0`, `p1`, ... are resolved from the method descriptor,
  * the actual `return-object` register is captured from the ROM,
  * growth is refused when a parameter is addressed in `vN` form, because that
    would silently turn a parameter into a local,
  * every emitted `invoke-*` is checked to still fit the 4-bit (35c) encoding.

Profiles
--------
Two hook generations exist and they must not be mixed:

  legacy  Android 12 era. Hooks android.security.KeyStore2.getKeyEntry and
          reads the key entry the keystore daemon returns, using the
          KaoriPropsUtils / KaoriKeyboxHooks classes in
          com.android.internal.util.kaorios.

  modern  Android 13+ era. Hooks
          AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair and synthesises a
          software key pair, using android.security.kaorios.KaoriosHook.

Usage
-----
  python3 smali_engine.py inject --decompile-dir DIR --source DIR
  python3 smali_engine.py patch  --decompile-dir DIR --profile legacy --sdk 31
  python3 smali_engine.py list

Exit code is 0 only when every required hook for the selected profile is in
place.
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
# legacy classes, injected into framework.jar from
# Toolbox-patcher/kaorios_toolbox/utils/kaorios
LEGACY_PACKAGE = "com/android/internal/util/kaorios"
LEGACY_PATH = "com/android/internal/util/kaorios"

LEGACY_PROPS_UTILS = f"L{LEGACY_PACKAGE}/KaoriPropsUtils;"
LEGACY_KEYBOX_HOOKS = f"L{LEGACY_PACKAGE}/KaoriKeyboxHooks;"
LEGACY_FEATURE_OVERRIDES = f"L{LEGACY_PACKAGE}/KaoriFeatureOverrides;"

# modern class. Supplied by the V2.0.3+ release, not by this repository.
MODERN_HOOK = "Landroid/security/kaorios/KaoriosHook;"

PROFILE_LEGACY = "legacy"
PROFILE_MODERN = "modern"

ARTIFACT_FRAMEWORK = "framework"
ARTIFACT_SERVICES = "services"

# --------------------------------------------------------------------------
# Anchors
# --------------------------------------------------------------------------
ANCHOR_AFTER_REGISTERS = "after-registers"
ANCHOR_BEFORE_FINAL_RETURN = "before-final-return"
ANCHOR_BEFORE_INVOKE = "before-invoke"

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

# --- legacy ---------------------------------------------------------------

LEGACY_FEATURE_OVERRIDE = """\
:try_start_kaorios
invoke-static {}, Landroid/app/ActivityThread;->currentPackageName()Ljava/lang/String;
move-result-object $r0
iget-object $r1, p0, Landroid/app/ApplicationPackageManager;->mContext:Landroid/app/ContextImpl;
invoke-static {$r1, $p1, $r0}, ${LEGACY_FEATURE_OVERRIDES}->getOverride(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;
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

LEGACY_INIT_CONTEXT = """\
invoke-static {$ctx}, ${LEGACY_PROPS_UTILS}->KaoriProps(Landroid/content/Context;)V
"""

LEGACY_KEY_ENTRY = """\
invoke-static {$ret}, ${LEGACY_KEYBOX_HOOKS}->KaoriGetKeyEntry(Landroid/system/keystore2/KeyEntryResponse;)Landroid/system/keystore2/KeyEntryResponse;
move-result-object $ret
"""

LEGACY_CERT_CHAIN_MARK = """\
invoke-static {}, ${LEGACY_PROPS_UTILS}->KaoriGetCertificateChain()V
"""

LEGACY_CERT_CHAIN = """\
invoke-static {$ret}, ${LEGACY_KEYBOX_HOOKS}->KaoriGetCertificateChain([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
move-result-object $ret
"""

# --- modern ---------------------------------------------------------------

MODERN_INIT_CONTEXT = """\
invoke-static {$ctx}, ${MODERN_HOOK}->initContext(Landroid/content/Context;)V
"""

# The published guide leaves this unhooked from any error handling. Wrapping it
# in a catchall means a broken hook degrades to stock behaviour instead of
# crashing every app at startup.
MODERN_FEATURE_OVERRIDE = """\
:try_start_kaorios
invoke-static {$p1, $p2}, ${MODERN_HOOK}->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;
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

MODERN_SOFTWARE_KEYPAIR = """\
invoke-static {$p0}, ${MODERN_HOOK}->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;
move-result-object $r0
if-eqz $r0, :kaorios_skip
return-object $r0
:kaorios_skip
"""

MODERN_CERT_CHAIN = """\
invoke-static {$ret}, ${MODERN_HOOK}->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
move-result-object $ret
"""

MODERN_INIT_SYSTEM_SERVER = """\
invoke-static {}, ${MODERN_HOOK}->initSystemServer()V
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
    # For ANCHOR_BEFORE_INVOKE: the method reference to insert in front of.
    needle: str = ""


@dataclass
class MethodPatch:
    """A hook applied to one method of one framework class."""

    patch_id: str
    class_relpath: str
    method_desc: str
    ops: list[Op]
    profile: str
    marker: str
    artifact: str = ARTIFACT_FRAMEWORK
    is_static: bool = False
    exclude: tuple[str, ...] = ()
    required: bool = True
    note: str = ""


PATCHES: list[MethodPatch] = [
    # ----------------------------------------------------------------------
    # legacy (Android 12)
    # ----------------------------------------------------------------------
    MethodPatch(
        patch_id="instrumentation.newApplication(Class,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=LEGACY_INIT_CONTEXT)],
        profile=PROFILE_LEGACY,
        marker=LEGACY_PACKAGE,
        note="Initialises the Kaorios context before the Application is created.",
    ),
    MethodPatch(
        patch_id="instrumentation.newApplication(ClassLoader,String,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=LEGACY_INIT_CONTEXT, param_index=2)],
        profile=PROFILE_LEGACY,
        marker=LEGACY_PACKAGE,
        note="Same as above for the ClassLoader overload (Context is p2).",
    ),
    MethodPatch(
        patch_id="applicationpackagemanager.hasSystemFeature(String,int)",
        class_relpath="android/app/ApplicationPackageManager.smali",
        method_desc="hasSystemFeature(Ljava/lang/String;I)Z",
        ops=[Op(anchor=ANCHOR_AFTER_REGISTERS, body=LEGACY_FEATURE_OVERRIDE, locals_needed=2)],
        profile=PROFILE_LEGACY,
        marker=LEGACY_PACKAGE,
        exclude=("lambda", "$$ExternalSyntheticLambda"),
        note="Per-app feature overrides, guarded by a catchall.",
    ),
    MethodPatch(
        patch_id="keystore2.getKeyEntry",
        class_relpath="android/security/KeyStore2.smali",
        method_desc="getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=LEGACY_KEY_ENTRY)],
        profile=PROFILE_LEGACY,
        marker=LEGACY_PACKAGE,
        exclude=("lambda", "$$ExternalSyntheticLambda"),
        note="Keybox attestation spoofing.",
    ),
    MethodPatch(
        patch_id="androidkeystorespi.engineGetCertificateChain",
        class_relpath="android/security/keystore2/AndroidKeyStoreSpi.smali",
        method_desc="engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
        ops=[
            Op(anchor=ANCHOR_AFTER_REGISTERS, body=LEGACY_CERT_CHAIN_MARK),
            Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=LEGACY_CERT_CHAIN),
        ],
        profile=PROFILE_LEGACY,
        marker=LEGACY_PACKAGE,
        note="Marks the call site and swaps the returned chain for the spoofed one.",
    ),
    # ----------------------------------------------------------------------
    # modern (Android 13+)
    # ----------------------------------------------------------------------
    MethodPatch(
        patch_id="instrumentation.newApplication(Class,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=MODERN_INIT_CONTEXT)],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
    ),
    MethodPatch(
        patch_id="instrumentation.newApplication(ClassLoader,String,Context)",
        class_relpath="android/app/Instrumentation.smali",
        method_desc="newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=MODERN_INIT_CONTEXT, param_index=2)],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
    ),
    MethodPatch(
        patch_id="applicationpackagemanager.hasSystemFeature(String,int)",
        class_relpath="android/app/ApplicationPackageManager.smali",
        method_desc="hasSystemFeature(Ljava/lang/String;I)Z",
        ops=[Op(anchor=ANCHOR_AFTER_REGISTERS, body=MODERN_FEATURE_OVERRIDE, locals_needed=2)],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
        exclude=("lambda", "$$ExternalSyntheticLambda"),
    ),
    MethodPatch(
        patch_id="androidkeystorekeypairgeneratorspi.generateKeyPair",
        class_relpath="android/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi.smali",
        method_desc="generateKeyPair()Ljava/security/KeyPair;",
        ops=[Op(anchor=ANCHOR_AFTER_REGISTERS, body=MODERN_SOFTWARE_KEYPAIR, locals_needed=1)],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
        note="Replaces the hardcoded `+1 register, use registers-2` step in the guide.",
    ),
    MethodPatch(
        patch_id="androidkeystorespi.engineGetCertificateChain",
        class_relpath="android/security/keystore2/AndroidKeyStoreSpi.smali",
        method_desc="engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
        ops=[Op(anchor=ANCHOR_BEFORE_FINAL_RETURN, body=MODERN_CERT_CHAIN)],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
    ),
    MethodPatch(
        patch_id="systemserver.initSystemServer",
        class_relpath="com/android/server/SystemServer.smali",
        # Empty descriptor means "anywhere in the file": the guide only says to
        # insert before the startOtherServices() call site.
        method_desc="",
        ops=[
            Op(
                anchor=ANCHOR_BEFORE_INVOKE,
                body=MODERN_INIT_SYSTEM_SERVER,
                needle="Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V",
            )
        ],
        profile=PROFILE_MODERN,
        marker=MODERN_HOOK,
        artifact=ARTIFACT_SERVICES,
        note="Lives in services.jar, not framework.jar.",
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
    return "v%d" % param_reg_index(index, total, nparams)


def param_reg_index(index: int, total: int, nparams: int) -> int:
    """Numeric register index of parameter `index` in a frame of `total`."""
    return total - nparams + index


def to_v(token: str, total: int, nparams: int) -> str:
    token = token.strip()
    if token.startswith("p"):
        return param_reg(int(token[1:]), total, nparams)
    return token


def render(
    body: str,
    total: int,
    nparams: int,
    scratch_base: int,
    ctx_index: int = 1,
    ret: str | None = None,
) -> str:
    """
    Substitute the snippet placeholders.

    `scratch_base` is the index of the first scratch register. `$ctx` is the
    parameter index that holds the Context, which differs between overloads.
    """
    mapping = {
        "r0": "v%d" % (scratch_base + 0),
        "r1": "v%d" % (scratch_base + 1),
        "r2": "v%d" % (scratch_base + 2),
        "r3": "v%d" % (scratch_base + 3),
        "p0": param_reg(0, total, nparams),
        "p1": param_reg(1, total, nparams),
        "p2": param_reg(2, total, nparams),
        "p3": param_reg(3, total, nparams),
        "ctx": param_reg(ctx_index, total, nparams),
        "LEGACY_PROPS_UTILS": LEGACY_PROPS_UTILS,
        "LEGACY_KEYBOX_HOOKS": LEGACY_KEYBOX_HOOKS,
        "LEGACY_FEATURE_OVERRIDES": LEGACY_FEATURE_OVERRIDES,
        "MODERN_HOOK": MODERN_HOOK,
        "ret": ret if ret is not None else "",
    }
    return Template(body).substitute(mapping)


def referenced_params(body: str, ctx_index: int) -> set[int]:
    """Parameter indexes a snippet touches, for the v0-v15 feasibility check."""
    refs: set[int] = set()
    if "$ctx" in body:
        refs.add(ctx_index)
    for m in re.finditer(r"\$p(\d)", body):
        refs.add(int(m.group(1)))
    # Literal pN references (e.g. `{p0}` for `this` in an instance method).
    for m in re.finditer(r"(?<![$\w])p(\d)(?![\w])", body):
        refs.add(int(m.group(1)))
    return refs


def plan_frame(
    total: int, nparams: int, needed: int, param_refs: set[int]
) -> tuple[int, int] | None:
    """
    Decide how to obtain `needed` scratch registers.

    Returns (growth, scratch_base), or None when neither strategy works.

    Strategy A grows the frame, which gives scratch registers that the original
    code has never touched. That is the safe option, but it shifts every
    parameter up: `invoke-static {p0}` is a 4-bit (35c) operand, so a parameter
    that lands on v16+ would no longer assemble.

    Strategy B leaves the frame alone and reuses the highest existing locals.
    That is what the published V2.0.3+ guide does by hand ("use the register at
    registers - 2"). It is only used when strategy A is impossible.
    """
    if needed == 0:
        return 0, 0

    locals_now = total - nparams

    # A parameter that lands above v15 cannot be used as a 35c invoke operand,
    # so growth is only acceptable while every referenced parameter stays <= v15.
    params_fit = all(
        param_reg_index(i, total + needed, nparams) <= 15 for i in param_refs
    )

    if params_fit and locals_now + needed - 1 <= 15:
        return needed, locals_now

    if locals_now >= needed:
        return 0, locals_now - needed

    return None


def find_method(lines: list[str], desc: str, exclude: tuple[str, ...]) -> tuple[int, int] | None:
    """Return (start, end) line indexes of the matching method, inclusive."""
    if not desc:
        # Whole-file scope: used by hooks that are anchored on a call site
        # rather than on a specific method signature.
        return 0, len(lines) - 1

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


def check_register_encoding(lines: list[str], total: int = 0, nparams: int = 0) -> list[str]:
    """
    Verify that every non-range invoke only references registers 0..15.

    The 35c instruction format stores registers in 4 bits, so a snippet mixing
    a high register into a plain `invoke-*` would not assemble. Both `vN` and
    symbolic `pN` operands are resolved before the check.
    """
    problems = []
    for ln in lines:
        m = INVOKE_35C_RE.match(ln)
        if not m:
            continue
        for token in m.group(1).split(","):
            token = token.strip()
            if re.fullmatch(r"v\d+", token):
                index = int(token[1:])
            elif re.fullmatch(r"p\d+", token):
                index = param_reg_index(int(token[1:]), total, nparams)
            else:
                continue
            if index > 15:
                problems.append("%s uses %s (v%d, but 35c allows v0-v15)" % (ln.strip(), token, index))
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

    target = os.path.join(last_smali_dir(decompile_dir), LEGACY_PATH)
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


def apply_op(
    lines: list[str],
    start: int,
    end: int,
    op: Op,
    total: int,
    nparams: int,
    scratch_base: int,
) -> tuple[str, str]:
    """Apply one op to the already-located span."""
    if op.anchor == ANCHOR_AFTER_REGISTERS:
        body = render(op.body, total, nparams, scratch_base, op.param_index)
        for i in range(start, end):
            if REGISTERS_RE.match(lines[i]):
                indent = indent_of(lines[i])
                block = [indent + ln if ln.strip() else "" for ln in body.splitlines()]
                lines[i + 1 : i + 1] = block + [""]
                return "patched", "inserted %d lines after `%s`" % (len(block), lines[i].strip())
        return "failed", "register directive not found"

    if op.anchor == ANCHOR_BEFORE_FINAL_RETURN:
        found = None
        for i in range(end, start, -1):
            m = RETURN_RE.match(lines[i])
            if m:
                found = (i, m.group(1))
                break
        if found is None:
            return "failed", "no return instruction found"

        index, token = found
        reg = to_v(token, total, nparams)
        resolved = render(op.body, total, nparams, scratch_base, op.param_index, ret=reg)
        indent = indent_of(lines[index])
        block = [indent + ln if ln.strip() else "" for ln in resolved.splitlines()]
        lines[index:index] = block + [""]
        return "patched", "inserted %d lines before final `%s`" % (len(block), lines[index + len(block) + 1].strip())

    if op.anchor == ANCHOR_BEFORE_INVOKE:
        if not op.needle:
            return "failed", "before-invoke anchor needs a needle"
        for i in range(start, end + 1):
            if op.needle in lines[i]:
                resolved = render(op.body, total, nparams, scratch_base, op.param_index)
                indent = indent_of(lines[i])
                block = [indent + ln if ln.strip() else "" for ln in resolved.splitlines()]
                lines[i:i] = block + [""]
                return "patched", "inserted %d lines before `%s`" % (len(block), lines[i + len(block) + 1].strip())
        return "failed", "call site `%s` not found" % op.needle

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
    if patch.marker in "\n".join(lines[start : end + 1]):
        return Result(patch.patch_id, "already", "hook already present")

    needed = max((op.locals_needed for op in patch.ops), default=0)
    total, nparams, scratch_base = 0, 0, 0
    frame_note = ""

    if patch.method_desc:
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

        param_refs: set[int] = set()
        for op in patch.ops:
            param_refs |= referenced_params(op.body, op.param_index)

        plan = plan_frame(total, nparams, needed, param_refs)
        if plan is None:
            return Result(
                patch.patch_id,
                "failed",
                "no register available for %d scratch value(s): the frame has %d "
                "locals and parameters already occupy v%d+"
                % (needed, total - nparams, total - nparams),
            )
        growth, scratch_base = plan

        if growth:
            unsafe = params_written_as_v(lines, start, end, total, nparams)
            if unsafe:
                return Result(
                    patch.patch_id,
                    "failed",
                    "cannot grow frame: parameter register(s) %s are addressed in v-form; "
                    "re-decompile with a decompiler that emits pN for parameters"
                    % ", ".join(unsafe),
                )
            lines[directive_index] = "%s.%s %d" % (
                indent_of(lines[directive_index]),
                kind,
                declared + growth,
            )
            total += growth
            frame_note = "frame grown to %s %d" % (kind, declared + growth)
        elif needed:
            frame_note = "reused v%d as scratch (growing would push a parameter past v15)" % scratch_base
    elif needed:
        return Result(patch.patch_id, "failed", "cannot grow a frame for a whole-file patch")

    # Bottom-up: the final return sits below the register directive, so patching
    # it first keeps the directive index valid.
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
        status, detail = apply_op(lines, span[0], span[1], op, total, nparams, scratch_base)
        if status != "patched":
            return Result(patch.patch_id, "failed", detail)
        details.append(detail)

    problems = check_register_encoding(lines, total, nparams)
    if problems:
        return Result(patch.patch_id, "failed", "; ".join(problems))

    if not dry_run:
        with open(target_file, "w", encoding="utf-8", errors="surrogateescape", newline="\n") as fh:
            fh.write("\n".join(lines) + "\n")

    if frame_note:
        details.append(frame_note)

    prefix = "dry-run: " if dry_run else ""
    return Result(patch.patch_id, "patched", prefix + " | ".join(details))


def select_patches(profile: str, artifact: str) -> list[MethodPatch]:
    return [
        p for p in PATCHES if p.profile == profile and p.artifact == artifact
    ]


def run_patches(
    decompile_dir: str, dry_run: bool, profile: str, artifact: str
) -> tuple[list[Result], bool]:
    patches = select_patches(profile, artifact)
    if not patches:
        # Legitimate: not every profile touches every artifact. For example the
        # legacy profile has no services.jar hooks, so asking for them is a
        # no-op rather than a failure.
        return [], True

    results = [apply_patch(decompile_dir, patch, dry_run) for patch in patches]
    ok = all(
        result.status in ("patched", "already")
        for patch, result in zip(patches, results)
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
    p_patch.add_argument(
        "--profile",
        choices=[PROFILE_LEGACY, PROFILE_MODERN],
        default=PROFILE_LEGACY,
    )
    p_patch.add_argument(
        "--artifact",
        choices=[ARTIFACT_FRAMEWORK, ARTIFACT_SERVICES],
        default=ARTIFACT_FRAMEWORK,
    )
    p_patch.add_argument("--dry-run", action="store_true")
    p_patch.add_argument("--json", action="store_true")

    sub.add_parser("list", help="show the hooks each profile applies")

    p_hooks = sub.add_parser("hooks", help="exit 0 if a profile has hooks for an artifact")
    p_hooks.add_argument(
        "--profile",
        choices=[PROFILE_LEGACY, PROFILE_MODERN],
        default=PROFILE_LEGACY,
    )
    p_hooks.add_argument(
        "--artifact",
        choices=[ARTIFACT_FRAMEWORK, ARTIFACT_SERVICES],
        default=ARTIFACT_FRAMEWORK,
    )

    args = parser.parse_args(argv)

    if args.command == "hooks":
        return 0 if select_patches(args.profile, args.artifact) else 1

    if args.command == "inject":
        result = inject_utilities(args.decompile_dir, args.source)
        if args.json:
            print(json.dumps(result))
        else:
            print("[%s] %s" % (result["status"], result["detail"]))
        return 0 if result["status"] == "patched" else 1

    if args.command == "list":
        for profile in (PROFILE_LEGACY, PROFILE_MODERN):
            print("%s:" % profile)
            for patch in PATCHES:
                if patch.profile != profile:
                    continue
                print("  %-12s %s" % (patch.artifact, patch.patch_id))
                if patch.note:
                    print("               %s" % patch.note)
        return 0

    results, ok = run_patches(args.decompile_dir, args.dry_run, args.profile, args.artifact)

    if args.json:
        print(json.dumps([r.__dict__ for r in results], indent=2))
    else:
        if not results:
            print("[skipped  ] no hooks defined for profile=%s artifact=%s" % (args.profile, args.artifact))
            return 0 if ok else 1
        width = max(len(r.patch_id) for r in results)
        for r in results:
            print("[%-9s] %-*s  %s" % (r.status, width, r.patch_id, r.detail))

    print("[INFO] profile=%s artifact=%s%s" % (
        args.profile,
        args.artifact,
        "" if args.sdk is None else " sdk=%d" % args.sdk,
    ))

    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
