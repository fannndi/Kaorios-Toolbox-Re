#!/usr/bin/env python3
"""Audit a stock ROM extraction against the Farewell patcher's rule targets.

The patcher matches classes and methods by exact signature. A signature that is
absent from the target ROM means the rule silently does nothing, and a rule that
is gated to the wrong API range means a feature looks supported but is not. This
script answers both questions for a given ROM extraction, so porting to a new
build is a matter of minutes instead of guesswork.

Usage
-----
    python rom_audit.py --rom MIUI13=/path/to/MIUI13/ROM
    python rom_audit.py --rom MIUI12=/r12 --rom MIUI13=/r13 --dexdump /path/dexdump

Layout
------
`<rom>` is the directory that contains the partition folders (`system`, `product`,
`vendor`, `system_ext`). Both extraction styles are supported:

    <rom>/system/system/framework/framework.jar   (super-image / system-as-root)
    <rom>/system/framework/framework.jar

Only the standard library is required; `dexdump` comes from Android SDK
build-tools.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import shutil
import subprocess
import sys
import tempfile
import zipfile
from collections import defaultdict

DEFAULT_DEXDUMP_CANDIDATES = [
    os.path.expanduser("~/AppData/Local/Android/Sdk/build-tools/36.0.0/dexdump.exe"),
    os.path.expanduser("~/AppData/Local/Android/Sdk/build-tools/35.0.0/dexdump.exe"),
    os.path.expanduser("~/Library/Android/sdk/build-tools/36.0.0/dexdump"),
    "/usr/lib/android-sdk/build-tools/debian/dexdump",
    "dexdump",
]

# Standard property files and where each ROM generation keeps them.
PROP_PATHS = [
    "system/build.prop",
    "system/default.prop",
    "product/build.prop",
    "product/etc/build.prop",
    "system_ext/etc/build.prop",
    "vendor/build.prop",
    "vendor/default.prop",
    "vendor/odm/etc/build.prop",
    "vendor/build_surya.prop",
    "vendor/build_karna.prop",
    "vendor/odm/etc/build_surya.prop",
    "vendor/odm/etc/build_karna.prop",
]

# (jar, api-gate, class, method-signature or None, rule label)
# api-gate is the apiRange the rule declares; "any" means unbounded.
TARGETS = [
    ("framework", "any", "Landroid/app/Instrumentation;",
     "newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;",
     "InstrumentationInit"),
    ("framework", "any", "Landroid/app/Instrumentation;",
     "newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;",
     "InstrumentationInit(3-arg)"),
    ("framework", "any", "Landroid/app/ApplicationPackageManager;",
     "hasSystemFeature(Ljava/lang/String;I)Z", "HasSystemFeature"),
    ("framework", "31+", "Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;",
     "generateKeyPair()Ljava/security/KeyPair;", "GenerateSoftwareKeyPair(k2)"),
    ("framework", "0-30", "Landroid/security/keystore/AndroidKeyStoreKeyPairGeneratorSpi;",
     "generateKeyPair()Ljava/security/KeyPair;", "GenerateSoftwareKeyPair(legacy)"),
    ("framework", "31+", "Landroid/security/keystore2/AndroidKeyStoreSpi;",
     "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
     "CertificateChain(k2)"),
    ("framework", "0-30", "Landroid/security/keystore/AndroidKeyStoreSpi;",
     "engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;",
     "CertificateChain(legacy)"),
    ("framework", "any", "Landroid/security/keystore2/AndroidKeyStoreSpi;",
     "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;",
     "CertificateAlias(k2)"),
    ("framework", "any", "Landroid/security/keystore/AndroidKeyStoreSpi;",
     "engineGetCertificate(Ljava/lang/String;)Ljava/security/cert/Certificate;",
     "CertificateAlias(legacy)"),
    ("framework", "any", "Landroid/provider/Settings$NameValueCache;",
     "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
     "HideDevStatus / SettingsNameValueCache"),
    ("framework", "any", "Landroid/os/SystemProperties;",
     "get(Ljava/lang/String;)Ljava/lang/String;", "SystemProperties.get/1"),
    ("framework", "any", "Landroid/os/SystemProperties;",
     "get(Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;", "SystemProperties.get/2"),
    ("framework", "any", "Landroid/os/SystemProperties;",
     "getInt(Ljava/lang/String;I)I", "SystemPropertiesPrimitive.getInt"),
    ("framework", "any", "Landroid/os/SystemProperties;",
     "getLong(Ljava/lang/String;J)J", "SystemPropertiesPrimitive.getLong"),
    ("framework", "any", "Landroid/os/SystemProperties;",
     "getBoolean(Ljava/lang/String;Z)Z", "SystemPropertiesPrimitive.getBoolean"),
    ("framework", "any", "Landroid/os/Build;", None, "BuildFieldClassRule (Build)"),
    ("framework", "any", "Landroid/os/Build$VERSION;", None, "BuildFieldClassRule (VERSION)"),
    ("framework", "any", "Landroid/content/pm/SigningDetails;", None,
     "SigningDetails(modern, A11+ class name)"),
    ("framework", "any", "Landroid/content/pm/PackageParser$SigningDetails;",
     "checkCapability(Landroid/content/pm/PackageParser$SigningDetails;I)Z",
     "SigningDetails(legacy)"),
    ("framework", "any", "Landroid/content/pm/PackageParser$SigningDetails;",
     "hasAncestorOrSelf(Landroid/content/pm/PackageParser$SigningDetails;)Z",
     "SigningDetails(hasAncestorOrSelf)"),
    ("framework", "any", "Landroid/util/apk/ApkSignatureSchemeV2Verifier;", None,
     "MessageDigestForce(V2)"),
    ("framework", "any", "Landroid/util/apk/ApkSignatureSchemeV3Verifier;", None,
     "MessageDigestForce(V3)"),
    ("framework", "any", "Landroid/util/apk/ApkSigningBlockUtils;", None,
     "MessageDigestForce(blockutils)"),
    ("framework", "31+", "Landroid/util/apk/ApkSignatureVerifier;",
     "getMinimumSignatureSchemeVersionForTargetSdk(I)I", "MinimumSignatureScheme"),
    ("framework", "any", "Landroid/util/jar/StrictJarVerifier;",
     "verifyMessageDigest([B[B)Z", "StrictJarVerifier"),
    ("services", "any", "Lcom/android/server/SystemServer;",
     "name:startOtherServices", "SystemServerInit(invoke site)"),
    ("services", "33+", "Lcom/android/server/pm/AppsFilterBase;", None, "AppsFilter(A13+)"),
    ("services", "33+", "Lcom/android/server/pm/AppsFilterImpl;", None, "AppsFilterImpl(A13+)"),
    ("services", "30-32", "Lcom/android/server/pm/AppsFilter;", None, "LegacyAppsFilter(A11/12)"),
    ("services", "33+", "Lcom/android/server/pm/ComputerEngine;",
     "getInstallerPackageName(Ljava/lang/String;I)Ljava/lang/String;", "InstallerSource(A13+)"),
    ("services", "any", "Lcom/android/server/pm/PackageManagerService;",
     "getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;", "PackageManagerInstaller"),
    ("services", "any", "Lcom/android/server/pm/PackageManagerService;",
     "filterAppAccess(Ljava/lang/String;II)Z", "FilterAppAccess(String,int,int)"),
    ("services", "any", "Lcom/android/server/pm/PackageManagerService;",
     "filterAppAccessLPr(Lcom/android/server/pm/PackageSetting;II)Z",
     "FilterAppAccess(PackageSetting,int,int)"),
    ("services", "31+", "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;",
     "isScreenCaptureAllowed(IZ)Z", "DevicePolicySecure(A11+)"),
    ("services", "0-30", "Lcom/android/server/devicepolicy/DevicePolicyCacheImpl;",
     "getScreenCaptureDisabled(I)Z", "LegacyScreenCapture(A10)"),
    ("services", "any", "Lcom/android/server/wm/WindowState;",
     "isSecureLocked()Z", "WindowSecure(WindowState)"),
    ("services", "any", "Lcom/android/server/wm/WindowStateAnimator;",
     "setSecureLocked(Z)V", "WindowSecure(WindowStateAnimator)"),
    ("services", "0-30", "Lcom/android/server/wm/WindowManagerService;",
     "isSecureLocked(Lcom/android/server/wm/WindowState;)Z", "LegacyWindowManagerSecure(A10)"),
    ("services", "33+", "Lcom/android/server/wm/WindowManagerService;",
     "name:notAllowCaptureDisplay", "WindowManagerCapture(invoke site)"),
    ("services", "any", "Lcom/android/providers/settings/SettingsProvider;",
     "getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;",
     "SettingsProvider (expected absent: lives in the APK)"),
]

CLASS_RE = re.compile(r"^  Class descriptor  : '(.*)'\s*$")
SECTION_RE = re.compile(r"^  ([A-Za-z][A-Za-z0-9 #_\-]*?) *- *$")
NAME_RE = re.compile(r"^ +name +: '(.*)'\s*$")
TYPE_RE = re.compile(r"^ +type +: '(.*)'\s*$")
METHOD_SECTIONS = {"Direct methods", "Virtual methods"}


def find_dexdump(explicit: str | None) -> str:
    if explicit:
        return explicit
    for candidate in DEFAULT_DEXDUMP_CANDIDATES:
        if os.path.sep in candidate or candidate == "dexdump":
            resolved = shutil.which(candidate) if candidate == "dexdump" else candidate
            if resolved and os.path.exists(resolved):
                return resolved
    raise SystemExit("dexdump not found; pass --dexdump <path>")


def parse_dexdump(text: str) -> dict[str, set[str]]:
    classes: dict[str, set[str]] = defaultdict(set)
    cls = None
    section = None
    pending = None
    for raw in text.splitlines():
        match = CLASS_RE.match(raw)
        if match:
            cls = match.group(1)
            classes.setdefault(cls, set())
            section = None
            pending = None
            continue
        match = SECTION_RE.match(raw)
        if match:
            section = match.group(1).strip()
            pending = None
            continue
        if section not in METHOD_SECTIONS or cls is None:
            continue
        match = NAME_RE.match(raw)
        if match:
            pending = match.group(1)
            continue
        match = TYPE_RE.match(raw)
        if match and pending is not None:
            classes[cls].add(pending + match.group(1))
            pending = None
    return classes


def dex_signatures(path: str, dexdump: str, workdir: str) -> dict[str, set[str]]:
    classes: dict[str, set[str]] = defaultdict(set)
    with zipfile.ZipFile(path) as archive:
        names = sorted(n for n in archive.namelist() if n.endswith(".dex"))
        for name in names:
            local = os.path.join(workdir, os.path.basename(name))
            with open(local, "wb") as handle:
                handle.write(archive.read(name))
            result = subprocess.run([dexdump, local], stdout=subprocess.PIPE,
                                    stderr=subprocess.DEVNULL, check=False)
            for cls, methods in parse_dexdump(result.stdout.decode("utf-8", "replace")).items():
                classes[cls] |= methods
            os.remove(local)
    return classes


def find_file(rom: str, relative: str) -> str | None:
    """Locate a partition-relative path inside a ROM extraction.

    Two extraction styles are common:
        <rom>/system/system/framework/framework.jar   (super / system-as-root)
        <rom>/system/framework/framework.jar
    while product/, vendor/, system_ext/ normally sit directly under <rom>.
    """
    for prefix in ("system/system/", "system/", ""):
        candidate = os.path.join(rom, prefix + relative)
        if os.path.exists(candidate):
            return candidate
    return None


def audit_rom(name: str, rom: str, dexdump: str, workdir: str) -> dict:
    result: dict = {"name": name, "root": rom, "sources": {}, "props": {}, "targets": []}

    for key, relative in (
        ("framework", "framework/framework.jar"),
        ("services", "framework/services.jar"),
        ("settingsprovider", "priv-app/SettingsProvider/SettingsProvider.apk"),
    ):
        path = find_file(rom, relative)
        result["sources"][key] = path
        if path:
            result.setdefault("dex", {})[key] = dex_signatures(path, dexdump, workdir)

    for relative in PROP_PATHS:
        path = find_file(rom, relative)
        result["props"][relative] = os.path.getsize(path) if path else None

    for jar, gate, cls, sig, label in TARGETS:
        dex = result.get("dex", {}).get(jar)
        if dex is None:
            status = "no-jar"
        elif cls not in dex:
            status = "no-class"
        elif sig is None:
            status = "ok"
        elif sig.startswith("name:"):
            # Rules that match on the method name alone (e.g. invoke-site rules).
            wanted = sig[len("name:"):]
            status = "ok" if any(entry.startswith(wanted) for entry in dex[cls]) else "no-method"
        elif sig in dex[cls]:
            status = "ok"
        else:
            status = "no-method"
        result["targets"].append(
            {"jar": jar, "gate": gate, "class": cls, "method": sig,
             "rule": label, "status": status}
        )
    result.pop("dex", None)
    return result


def print_report(results: list[dict]) -> None:
    names = [r["name"] for r in results]
    width = max(len(t[4]) for t in TARGETS) + 2

    print("=" * (width + 12 * len(names)))
    print("PROPERTY FILE LAYOUT")
    print("=" * (width + 12 * len(names)))
    header = "file".ljust(width) + "".join(n[:10].rjust(12) for n in names)
    print(header)
    for relative in PROP_PATHS:
        row = relative.ljust(width)
        for r in results:
            size = r["props"].get(relative)
            row += ("present" if size else "-").rjust(12)
        print(row)

    print()
    print("=" * (width + 12 * len(names)))
    print("RULE TARGETS  (ok = signature present, no-class/no-method = rule cannot fire)")
    print("=" * (width + 12 * len(names)))
    print(header)
    for index, target in enumerate(TARGETS):
        row = target[4].ljust(width)
        for r in results:
            entry = r["targets"][index]
            status = entry["status"]
            if status in ("no-class", "no-method") and target[1] != "any":
                # Absent outside the rule's own API gate is expected, not a bug.
                status = "n/a"
            row += status.rjust(12)
        print(row)
    print()
    print("n/a = absent, but the rule is gated to another API level (expected).")
    print("NOTE: this table compares FULL method signatures. Some rules match on")
    print("      name + return type only (e.g. StrictJarVerifierRule), so a")
    print("      'no-method' here can still be a rule that fires. Verify against")
    print("      the rule's own applyMethod() before acting on it.")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rom", action="append", required=True,
                        metavar="NAME=PATH", help="ROM name and extraction root")
    parser.add_argument("--dexdump", default=None, help="path to dexdump")
    parser.add_argument("--json", default=None, help="also write raw results as JSON")
    args = parser.parse_args()

    dexdump = find_dexdump(args.dexdump)
    workdir = tempfile.mkdtemp(prefix="rom-audit-")
    try:
        results = []
        for entry in args.rom:
            if "=" not in entry:
                parser.error("--rom expects NAME=PATH, got {!r}".format(entry))
            name, path = entry.split("=", 1)
            results.append(audit_rom(name, path, dexdump, workdir))
        print_report(results)
        if args.json:
            with open(args.json, "w", encoding="utf-8") as handle:
                json.dump(results, handle, indent=2)
            print("\nJSON written to {}".format(args.json))
    finally:
        shutil.rmtree(workdir, ignore_errors=True)
    return 0


if __name__ == "__main__":
    sys.exit(main())
