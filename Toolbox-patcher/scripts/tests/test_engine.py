#!/usr/bin/env python3
"""
test_engine.py - self-check for smali_engine.py

Builds a miniature framework.jar layout that deliberately uses register
numbers the *old* patcher could not handle, then asserts that the engine

  1. grows `.registers` when a method has no free locals,
  2. resolves parameter registers from the descriptor,
  3. finds the anchor without hardcoded v0/v1/v4,
  4. is idempotent.

Run with:  python3 scripts/tests/test_engine.py
"""

from __future__ import annotations

import os
import shutil
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ENGINE = os.path.abspath(os.path.join(HERE, "..", "lib", "smali_engine.py"))

# --------------------------------------------------------------------------
# Fixtures: note the register counts. hasSystemFeature has ZERO free locals,
# which is exactly the case the previous patcher corrupted.
# --------------------------------------------------------------------------
FIXTURES = {
    "smali/android/app/Instrumentation.smali": """\
.class public Landroid/app/Instrumentation;
.super Ljava/lang/Object;

.method public newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;
    .registers 4

    const/4 v0, 0x0

    return-object v0
.end method

.method public newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;
    .registers 5

    const/4 v0, 0x0

    return-object v0
.end method
""",
    "smali/android/app/ApplicationPackageManager.smali": """\
.class public Landroid/app/ApplicationPackageManager;
.super Ljava/lang/Object;

.field private mContext:Landroid/app/ContextImpl;

.method public hasSystemFeature(Ljava/lang/String;I)Z
    .registers 3

    const/4 p0, 0x0

    return p0
.end method
""",
    "smali/android/security/KeyStore2.smali": """\
.class public Landroid/security/KeyStore2;
.super Ljava/lang/Object;

.method public getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;
    .registers 3

    const/4 v0, 0x0

    return-object v0
.end method
""",
    "smali/android/security/keystore2/AndroidKeyStoreSpi.smali": """\
.class public Landroid/security/keystore2/AndroidKeyStoreSpi;
.super Ljava/security/KeyStoreSpi;

.method public engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
    .registers 11

    const/4 v0, 0x0

    new-array v3, v0, [Ljava/security/cert/Certificate;

    const/4 v4, 0x0

    aput-object v2, v3, v4

    return-object v3
.end method
""",
}


def build_tree(root: str) -> str:
    for rel, content in FIXTURES.items():
        path = os.path.join(root, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(content)
    return root


def read(root: str, rel: str) -> str:
    with open(os.path.join(root, *rel.split("/")), encoding="utf-8") as fh:
        return fh.read()


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run(
        [sys.executable, ENGINE, *args],
        capture_output=True,
        text=True,
    )


def main() -> int:
    root = tempfile.mkdtemp(prefix="kaorios_engine_test_")
    failures: list[str] = []

    try:
        build_tree(root)

        # A fake hook-class source dir.
        source = os.path.join(root, "_hooks")
        os.makedirs(source, exist_ok=True)
        with open(os.path.join(source, "KaoriPropsUtils.smali"), "w", encoding="utf-8") as fh:
            fh.write(".class public final Lcom/android/internal/util/kaorios/KaoriPropsUtils;\n")

        # -- inject --------------------------------------------------------
        res = run("inject", "--decompile-dir", root, "--source", source)
        if res.returncode != 0:
            failures.append("inject failed: %s%s" % (res.stdout, res.stderr))
        injected = os.path.join(
            root, "smali", "com", "android", "internal", "util", "kaorios", "KaoriPropsUtils.smali"
        )
        if not os.path.isfile(injected):
            failures.append("hook class was not injected")

        # -- patch ---------------------------------------------------------
        res = run("patch", "--decompile-dir", root, "--sdk", "31")
        if res.returncode != 0:
            failures.append("patch returned %d\n%s%s" % (res.returncode, res.stdout, res.stderr))
        print("--- first run ---")
        print(res.stdout.strip())

        apm = read(root, "smali/android/app/ApplicationPackageManager.smali")
        inst = read(root, "smali/android/app/Instrumentation.smali")
        ks2 = read(root, "smali/android/security/KeyStore2.smali")
        spi = read(root, "smali/android/security/keystore2/AndroidKeyStoreSpi.smali")

        checks = [
            ("frame grown to .registers 5", ".registers 5" in apm),
            ("feature override hook present", "KaoriFeatureOverrides;->getOverride" in apm),
            # After growth the frame is v0,v1 locals | v2=this, v3=String, v4=int.
            ("feature override args are (mContext, p1, pkg)", "invoke-static {v1, v3, v0}" in apm),
            ("try/catch guard present", ".catchall {:try_start_kaorios" in apm),
            ("Context init hook present", "KaoriProps(Landroid/content/Context;)V" in inst),
            ("first overload passes v2", "invoke-static {v2}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;" in inst),
            ("second overload passes v3", "invoke-static {v3}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;" in inst),
            ("keybox hook present", "KaoriGetKeyEntry" in ks2),
            ("cert chain marker present", "KaoriGetCertificateChain()V" in spi),
            ("cert chain swap present", "KaoriGetCertificateChain([Ljava/security/cert/Certificate;)" in spi),
            ("cert chain swap reuses v3", "move-result-object v3" in spi),
        ]
        for label, ok in checks:
            if not ok:
                failures.append("assertion failed: %s" % label)

        # -- idempotency ---------------------------------------------------
        res = run("patch", "--decompile-dir", root, "--sdk", "31")
        if "already" not in res.stdout:
            failures.append("second run was not detected as already patched:\n%s" % res.stdout)
        print("--- second run ---")
        print(res.stdout.strip())

        # -- bucket selection ----------------------------------------------
        # Extra classes must land in the highest smali bucket, otherwise a
        # large framework.jar can blow the 64K method limit of classes.dex.
        os.makedirs(os.path.join(root, "smali_classes2"), exist_ok=True)
        res = run("inject", "--decompile-dir", root, "--source", source)
        expected = os.path.join(
            root, "smali_classes2", "com", "android", "internal", "util", "kaorios",
            "KaoriPropsUtils.smali",
        )
        if not os.path.isfile(expected):
            failures.append("hook class was not placed in the highest smali bucket:\n%s" % res.stdout)
        else:
            print("--- bucket selection ---")
            print("injected into smali_classes2")
    finally:
        shutil.rmtree(root, ignore_errors=True)

    if failures:
        print("\nFAILED (%d)" % len(failures))
        for f in failures:
            print("  - %s" % f)
        return 1

    print("all engine checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
