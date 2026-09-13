#!/usr/bin/env python3
"""
test_engine.py - self-check for smali_engine.py

Builds miniature framework trees that deliberately use register layouts the
*old* patcher could not handle, then asserts the engine gets them right.

Covered:
  legacy profile   frame growth when a method has no free locals, parameter
                   resolution from the descriptor, and anchor matching without
                   hardcoded v0/v1/v4.
  modern profile   the generateKeyPair() case where growing the frame would
                   push `this` to v16 and break the 4-bit invoke encoding, so
                   the engine must reuse an existing local instead.
  services artifact the whole-file `before-invoke` anchor used by SystemServer.
  general          idempotency, and injection into the highest smali bucket.

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

HOOK_STUBS = {
    "KaoriPropsUtils.smali": """\
.class public final Lcom/android/internal/util/kaorios/KaoriPropsUtils;
.super Ljava/lang/Object;

.method public static KaoriProps(Landroid/content/Context;)V
    .registers 1

    return-void
.end method

.method public static KaoriGetCertificateChain()V
    .registers 0

    return-void
.end method
""",
    "KaoriKeyboxHooks.smali": """\
.class public Lcom/android/internal/util/kaorios/KaoriKeyboxHooks;
.super Ljava/lang/Object;

.method public static KaoriGetKeyEntry(Landroid/system/keystore2/KeyEntryResponse;)Landroid/system/keystore2/KeyEntryResponse;
    .registers 1

    return-object p0
.end method

.method public static KaoriGetCertificateChain([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
    .registers 1

    return-object p0
.end method
""",
    "KaoriFeatureOverrides.smali": """\
.class public final Lcom/android/internal/util/kaorios/KaoriFeatureOverrides;
.super Ljava/lang/Object;

.method public static getOverride(Landroid/content/Context;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/Boolean;
    .registers 4

    const/4 v0, 0x0

    return-object v0
.end method
""",
}

# Same shape, but KaoriProps was renamed: the patcher would still assemble and
# then die at boot with NoSuchMethodError. verify must catch this.
BROKEN_HOOK_STUBS = dict(HOOK_STUBS)
BROKEN_HOOK_STUBS["KaoriPropsUtils.smali"] = HOOK_STUBS["KaoriPropsUtils.smali"].replace(
    "KaoriProps(Landroid/content/Context;)V", "KaoriInitContext(Landroid/content/Context;)V"
)

# --------------------------------------------------------------------------
# Shared fixtures
# --------------------------------------------------------------------------
INSTRUMENTATION = """\
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
"""

# `.registers 3` and three parameters means there are ZERO free locals. The old
# patcher wrote to v0/v1 here, which are p0 and p1.
APPLICATION_PACKAGE_MANAGER = """\
.class public Landroid/app/ApplicationPackageManager;
.super Ljava/lang/Object;

.field private mContext:Landroid/app/ContextImpl;

.method public hasSystemFeature(Ljava/lang/String;I)Z
    .registers 3

    const/4 p0, 0x0

    return p0
.end method
"""

ANDROID_KEY_STORE_SPI = """\
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
"""

KEYSTORE2 = """\
.class public Landroid/security/KeyStore2;
.super Ljava/lang/Object;

.method public getKeyEntry(Landroid/system/keystore2/KeyDescriptor;)Landroid/system/keystore2/KeyEntryResponse;
    .registers 3

    const/4 v0, 0x0

    return-object v0
.end method
"""

LEGACY_FIXTURES = {
    "smali/android/app/Instrumentation.smali": INSTRUMENTATION,
    "smali/android/app/ApplicationPackageManager.smali": APPLICATION_PACKAGE_MANAGER,
    "smali/android/security/KeyStore2.smali": KEYSTORE2,
    "smali/android/security/keystore2/AndroidKeyStoreSpi.smali": ANDROID_KEY_STORE_SPI,
}

# 16 registers with a single parameter: `this` lives in v15. Growing the frame
# would move it to v16, which does not fit a 35c invoke operand.
KEY_PAIR_GENERATOR_SPI = """\
.class public abstract Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;
.super Ljava/security/KeyPairGeneratorSpi;

.method public generateKeyPair()Ljava/security/KeyPair;
    .registers 16

    const/4 v0, 0x0

    return-object v9
.end method
"""

SYSTEM_SERVER = """\
.class public final Lcom/android/server/SystemServer;
.super Ljava/lang/Object;

.method private run()V
    .registers 2

    invoke-direct {p0, p1}, Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V

    return-void
.end method
"""

MODERN_FIXTURES = {
    "smali/android/app/Instrumentation.smali": INSTRUMENTATION,
    "smali/android/app/ApplicationPackageManager.smali": APPLICATION_PACKAGE_MANAGER,
    "smali/android/security/keystore2/AndroidKeyStoreSpi.smali": ANDROID_KEY_STORE_SPI,
    "smali/android/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi.smali": KEY_PAIR_GENERATOR_SPI,
}

SERVICES_FIXTURES = {
    "smali/com/android/server/SystemServer.smali": SYSTEM_SERVER,
}


def build_tree(fixtures: dict[str, str], stubs: dict[str, str] | None = None) -> str:
    root = tempfile.mkdtemp(prefix="kaorios_engine_test_")
    for rel, content in fixtures.items():
        path = os.path.join(root, *rel.split("/"))
        os.makedirs(os.path.dirname(path), exist_ok=True)
        with open(path, "w", encoding="utf-8", newline="\n") as fh:
            fh.write(content)

    source = os.path.join(root, "_hooks")
    os.makedirs(source, exist_ok=True)
    for name, content in (stubs if stubs is not None else HOOK_STUBS).items():
        with open(os.path.join(source, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(content)
    return root


def read(root: str, rel: str) -> str:
    with open(os.path.join(root, *rel.split("/")), encoding="utf-8") as fh:
        return fh.read()


def run(*args: str) -> subprocess.CompletedProcess:
    return subprocess.run([sys.executable, ENGINE, *args], capture_output=True, text=True)


class Checker:
    def __init__(self) -> None:
        self.failures: list[str] = []

    def check(self, label: str, ok: bool) -> None:
        if not ok:
            self.failures.append(label)

    def equals(self, label: str, actual: object, expected: object) -> None:
        if actual != expected:
            self.failures.append("%s: expected %r, got %r" % (label, expected, actual))


def test_legacy(c: Checker) -> None:
    root = build_tree(LEGACY_FIXTURES)
    try:
        source = os.path.join(root, "_hooks")

        res = run("inject", "--decompile-dir", root, "--source", source)
        c.equals("legacy inject exit code", res.returncode, 0)

        res = run("verify", "--decompile-dir", root, "--profile", "legacy")
        c.equals("legacy verify exit code", res.returncode, 0)

        res = run("patch", "--decompile-dir", root, "--profile", "legacy", "--sdk", "31")
        print("--- legacy, first run ---")
        print(res.stdout.strip())
        c.equals("legacy patch exit code", res.returncode, 0)

        apm = read(root, "smali/android/app/ApplicationPackageManager.smali")
        inst = read(root, "smali/android/app/Instrumentation.smali")
        ks2 = read(root, "smali/android/security/KeyStore2.smali")
        spi = read(root, "smali/android/security/keystore2/AndroidKeyStoreSpi.smali")

        c.check("legacy: frame grown to .registers 5", ".registers 5" in apm)
        c.check("legacy: override hook present", "KaoriFeatureOverrides;->getOverride" in apm)
        # v0,v1 are the new scratch locals; v2=this, v3=String, v4=int.
        c.check("legacy: override args are (mContext, p1, pkg)", "invoke-static {v1, v3, v0}" in apm)
        c.check("legacy: catchall guard present", ".catchall {:try_start_kaorios" in apm)
        c.check("legacy: Context hook present", "KaoriProps(Landroid/content/Context;)V" in inst)
        c.check("legacy: first overload passes v2", "invoke-static {v2}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;" in inst)
        c.check("legacy: second overload passes v3", "invoke-static {v3}, Lcom/android/internal/util/kaorios/KaoriPropsUtils;" in inst)
        c.check("legacy: keybox hook present", "KaoriGetKeyEntry" in ks2)
        c.check("legacy: cert chain marker present", "KaoriGetCertificateChain()V" in spi)
        c.check("legacy: cert chain swap present", "KaoriGetCertificateChain([Ljava/security/cert/Certificate;)" in spi)
        c.check("legacy: cert chain swap reuses v3", "move-result-object v3" in spi)

        res = run("patch", "--decompile-dir", root, "--profile", "legacy", "--sdk", "31")
        c.check("legacy: idempotent", "already" in res.stdout)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_modern(c: Checker) -> None:
    root = build_tree(MODERN_FIXTURES)
    try:
        res = run("patch", "--decompile-dir", root, "--profile", "modern", "--sdk", "33")
        print("--- modern, first run ---")
        print(res.stdout.strip())
        c.equals("modern patch exit code", res.returncode, 0)

        kpg = read(root, "smali/android/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi.smali")
        inst = read(root, "smali/android/app/Instrumentation.smali")
        spi = read(root, "smali/android/security/keystore2/AndroidKeyStoreSpi.smali")

        c.check("modern: software keypair hook present", "initGenerateSoftwareKeyPair" in kpg)
        # The frame must NOT grow: `this` would move from v15 to v16.
        c.check("modern: frame left at .registers 16", ".registers 16" in kpg)
        c.check("modern: scratch reused v14", "move-result-object v14" in kpg)
        c.check("modern: this still v15", "invoke-static {v15}, Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair" in kpg)
        c.check("modern: reuse reported", "reused v14" in res.stdout)
        c.check("modern: initContext present", "KaoriosHook;->initContext" in inst)
        c.check("modern: cert chain hook present", "CertificateChainIfNeeded" in spi)
        c.check("modern: no legacy class leaked", "KaoriKeyboxHooks" not in spi)

        res = run("patch", "--decompile-dir", root, "--profile", "modern", "--sdk", "33")
        c.check("modern: idempotent", "already" in res.stdout)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_services(c: Checker) -> None:
    root = build_tree(SERVICES_FIXTURES)
    try:
        res = run("patch", "--decompile-dir", root, "--profile", "modern", "--artifact", "services")
        print("--- services artifact ---")
        print(res.stdout.strip())
        c.equals("services patch exit code", res.returncode, 0)

        srv = read(root, "smali/com/android/server/SystemServer.smali")
        c.check("services: initSystemServer present", "initSystemServer()V" in srv)

        body = srv.splitlines()
        hook_at = next((i for i, l in enumerate(body) if "initSystemServer()V" in l), -1)
        call_at = next((i for i, l in enumerate(body) if "startOtherServices" in l), -1)
        c.check("services: hook inserted before the call site", 0 <= hook_at < call_at)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_bucket_selection(c: Checker) -> None:
    root = build_tree(LEGACY_FIXTURES)
    try:
        # Extra classes must land in the highest smali bucket, otherwise a large
        # framework.jar can blow the 64K method limit of classes.dex.
        os.makedirs(os.path.join(root, "smali_classes2"), exist_ok=True)
        source = os.path.join(root, "_hooks")
        res = run("inject", "--decompile-dir", root, "--source", source)
        expected = os.path.join(
            root, "smali_classes2", "com", "android", "internal", "util", "kaorios",
            "KaoriPropsUtils.smali",
        )
        print("--- bucket selection ---")
        print("injected into smali_classes2" if os.path.isfile(expected) else res.stdout.strip())
        c.check("bucket: injected into the highest smali bucket", os.path.isfile(expected))
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_verify_contract(c: Checker) -> None:
    # A matching contract passes.
    root = build_tree(LEGACY_FIXTURES)
    try:
        source = os.path.join(root, "_hooks")
        run("inject", "--decompile-dir", root, "--source", source)
        res = run("verify", "--decompile-dir", root, "--profile", "legacy")
        print("--- verify, matching contract ---")
        print(res.stdout.strip())
        c.equals("verify: matching contract passes", res.returncode, 0)
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # A renamed hook method must be reported, otherwise the patched framework
    # assembles cleanly and then dies at boot with NoSuchMethodError.
    root = build_tree(LEGACY_FIXTURES, stubs=BROKEN_HOOK_STUBS)
    try:
        source = os.path.join(root, "_hooks")
        run("inject", "--decompile-dir", root, "--source", source)
        res = run("verify", "--decompile-dir", root, "--profile", "legacy")
        print("--- verify, renamed hook method ---")
        print(res.stdout.strip())
        c.equals("verify: renamed hook method fails", res.returncode, 1)
        c.check(
            "verify: names the missing method",
            "KaoriProps(Landroid/content/Context;)V" in res.stdout,
        )
    finally:
        shutil.rmtree(root, ignore_errors=True)

    # The modern payload is not in this repository, so verify must say so
    # rather than silently pass.
    root = build_tree(MODERN_FIXTURES)
    try:
        res = run("verify", "--decompile-dir", root, "--profile", "modern")
        c.equals("verify: absent modern payload fails", res.returncode, 1)
        c.check("verify: mentions the modern hook", "KaoriosHook" in res.stdout)
    finally:
        shutil.rmtree(root, ignore_errors=True)


def test_profile_listing(c: Checker) -> None:
    res = run("list")
    c.equals("list exit code", res.returncode, 0)
    c.check("list mentions legacy", "legacy:" in res.stdout)
    c.check("list mentions modern", "modern:" in res.stdout)
    c.check("list mentions services artifact", "services" in res.stdout)


def main() -> int:
    c = Checker()
    test_legacy(c)
    test_modern(c)
    test_services(c)
    test_bucket_selection(c)
    test_verify_contract(c)
    test_profile_listing(c)

    if c.failures:
        print("\nFAILED (%d)" % len(c.failures))
        for f in c.failures:
            print("  - %s" % f)
        return 1

    print("\nall engine checks passed")
    return 0


if __name__ == "__main__":
    sys.exit(main())
