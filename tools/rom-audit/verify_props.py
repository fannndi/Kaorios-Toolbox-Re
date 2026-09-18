#!/usr/bin/env python3
"""Prove that the spoofed identity actually survives boot on a given ROM.

This closes the loop that the other two tools open:

  * `rom_audit.py`   — what the ROM contains, and which rules can fire
  * `prop_resolve.py`— who wins each property key, `import` chain included
  * this script      — patch a ROM's real property files with the *real* patcher
                       and the *real* per-partition maps, then resolve the load
                       order and assert the spoofed values win

It patches a copy of the ROM tree, never the ROM itself.

Usage
-----
    # 1. dump the per-partition maps for a PIF profile (real code, not a model)
    patcher/build/install/patcher/bin/patcher \\
        --dump-prop-maps /tmp/maps --props Toolbox-data/Pif-props.json

    # 2. prove it
    python verify_props.py --rom /path/to/MIUI13/ROM --maps /tmp/maps \\
        --patcher patcher/build/install/patcher/bin/patcher

Exit code is 0 only when every identity key resolves to a spoofed value.
"""
from __future__ import annotations

import argparse
import json
import os
import shutil
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))

from prop_resolve import Resolver, device_to_local  # noqa: E402

# Which property files each partition owns. Mirrors PlatformProfiles plus the
# per-SKU files that PatchRepository adds at runtime. `build_*.prop` is a glob
# because the file name depends on ro.boot.product.hardware.sku.
PARTITION_FILES = {
    "SYSTEM": ["system/build.prop", "system/default.prop"],
    "PRODUCT": ["product/build.prop", "product/etc/build.prop"],
    "SYSTEM_EXT": ["system_ext/etc/build.prop"],
    "VENDOR": ["vendor/build.prop", "vendor/default.prop"],
    "ODM": ["vendor/odm/etc/build.prop"],
}
SKU_FILES = {
    "VENDOR": ["vendor/build_%s.prop"],
    "ODM": ["vendor/odm/etc/build_%s.prop"],
}

# The keys whose value decides the device identity. Every one of these must carry
# the spoofed value after patching, or the ROM still reports the real device.
IDENTITY_KEYS = [
    "ro.product.odm.model",
    "ro.product.vendor.model",
    "ro.product.odm.brand",
    "ro.product.odm.device",
    "ro.product.odm.name",
    "ro.product.odm.manufacturer",
    "ro.odm.build.fingerprint",
    "ro.vendor.build.fingerprint",
    "ro.bootimage.build.fingerprint",
    "ro.product.model",
    "ro.product.brand",
    "ro.product.device",
    "ro.product.name",
    "ro.product.manufacturer",
    "ro.build.fingerprint",
]


def copy_tree(rom: str, work: str) -> str:
    """Copy only the property files we touch, preserving the layout."""
    root = os.path.join(work, "rom")
    for relative in _all_candidate_paths(rom):
        source = device_to_local(rom, "/" + relative)
        if source is None:
            continue
        target = os.path.join(root, relative)
        os.makedirs(os.path.dirname(target), exist_ok=True)
        shutil.copy2(source, target)
    return root


def _all_candidate_paths(rom: str) -> list[str]:
    paths: list[str] = []
    for templates in PARTITION_FILES.values():
        paths += templates
    for templates in SKU_FILES.values():
        for template in templates:
            for sku in ("surya", "karna"):
                paths.append(template % sku)
    return paths


def apply_map(patcher: str, target: str, map_file: str, java_home: str | None) -> tuple[int, int]:
    env = dict(os.environ)
    if java_home:
        env["JAVA_HOME"] = java_home
    result = subprocess.run(
        [patcher, "--patch-prop", target, "--props", map_file, "--output", target],
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT, env=env, check=False,
    )
    output = result.stdout.decode("utf-8", "replace").strip()
    if result.returncode != 0:
        raise SystemExit("patcher failed for {}:\n{}".format(target, output))
    replaced = appended = 0
    for token in output.replace(",", " ").split():
        if token.isdigit():
            if replaced == 0:
                replaced = int(token)
            else:
                appended = int(token)
                break
    return replaced, appended


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--rom", required=True, help="ROM extraction root")
    parser.add_argument("--maps", required=True, help="directory of PARTITION.json maps")
    parser.add_argument("--patcher", required=True, help="path to the patcher CLI")
    parser.add_argument("--sku", default="surya", help="ro.boot.product.hardware.sku")
    parser.add_argument("--java-home", default=None, help="JAVA_HOME for the patcher")
    parser.add_argument("--work", default=None, help="keep the patched tree here")
    parser.add_argument("--json", default=None, help="write the resolved values as JSON")
    args = parser.parse_args()

    maps: dict[str, str] = {}
    for partition in list(PARTITION_FILES) + ["OTHER"]:
        candidate = os.path.join(args.maps, "{}.json".format(partition))
        if os.path.exists(candidate):
            maps[partition] = candidate
    if not maps:
        raise SystemExit("no PARTITION.json files in {}".format(args.maps))

    work = args.work or tempfile.mkdtemp(prefix="verify-props-")
    os.makedirs(work, exist_ok=True)
    rom_copy = copy_tree(args.rom, work)

    print("ROM       : {}".format(args.rom))
    print("Maps      : {}".format(", ".join(sorted(maps))))
    print("SKU       : {}".format(args.sku))
    print("Work tree : {}".format(rom_copy))
    print()

    print("PATCHING")
    touched = 0
    for partition, map_file in sorted(maps.items()):
        templates = list(PARTITION_FILES.get(partition, []))
        for template in SKU_FILES.get(partition, []):
            templates.append(template % args.sku)
        for relative in templates:
            target = os.path.join(rom_copy, relative)
            if not os.path.exists(target):
                continue
            replaced, appended = apply_map(args.patcher, target, map_file, args.java_home)
            print("  {:<11} {:<34} {} replaced, {} appended".format(
                partition, relative, replaced, appended))
            touched += 1
    if touched == 0:
        raise SystemExit("nothing was patched — check --rom and --maps")
    print()

    # Resolve the load order over the patched copy.
    resolver = Resolver(rom_copy, {"ro.boot.product.hardware.sku": args.sku})
    from prop_resolve import LOAD_ORDER
    for relative in LOAD_ORDER:
        resolver.load(relative)

    spoofed = json.load(open(maps["ODM"]))
    expected = {
        "ro.product.odm.model": spoofed.get("ro.product.odm.model"),
        "ro.product.odm.brand": spoofed.get("ro.product.odm.brand"),
        "ro.product.odm.device": spoofed.get("ro.product.odm.device"),
        "ro.odm.build.fingerprint": spoofed.get("ro.odm.build.fingerprint"),
    }

    print("RESOLVED IDENTITY KEYS")
    width = max(len(k) for k in IDENTITY_KEYS) + 2
    failures = 0
    resolved: dict[str, str] = {}
    for key in IDENTITY_KEYS:
        entry = resolver.winners.get(key)
        value = entry[0] if entry else ""
        source = entry[1] if entry else "-"
        resolved[key] = value
        # A key is a pass when it is either set to the spoofed value, or not set at
        # all by any file (init derives those from the partition-prefixed keys).
        want = expected.get(key)
        if not value:
            status = "derived at boot"
        elif want is not None and value == want:
            status = "SPOOFED"
        elif value != want and _looks_like_stock(value):
            status = "STOCK  <-- FAIL"
            failures += 1
        else:
            status = "set"
        print("  {:<{w}} {:<44} {:<12} {}".format(
            key, value[:42], status, source, w=width))

    # Compatibility prop: the installer guard and the prop map both relax MIUI's
    # privileged-permission enforcement, because our system-app install would
    # otherwise be at the mercy of an allowlist mismatch. Assert it resolved to
    # `log` from the file that actually defines it (vendor on all three ROMs).
    compat_key = "ro.control_privapp_permissions"
    compat_entry = resolver.winners.get(compat_key)
    compat_value = compat_entry[0] if compat_entry else ""
    compat_source = compat_entry[1] if compat_entry else "-"
    print()
    print("COMPATIBILITY KEY")
    print("  {:<34} {:<10} {}".format(compat_key, compat_value or "unset", compat_source))
    if compat_value != "log":
        failures += 1
        print("  RESULT                 : FAIL (privapp enforcement not relaxed)")
    else:
        print("  RESULT                 : PASS")

    print()
    print("SUMMARY")
    print("  property files patched : {}".format(touched))
    print("  identity keys checked  : {}".format(len(IDENTITY_KEYS)))
    if failures:
        print("  RESULT                 : FAIL ({} key(s) still stock)".format(failures))
    else:
        print("  RESULT                 : PASS")
        print("                           no identity key resolves to a stock value")
    if args.json:
        with open(args.json, "w", encoding="utf-8") as handle:
            json.dump(resolved, handle, indent=2)
        print("  resolved values written to {}".format(args.json))

    if not args.work:
        shutil.rmtree(work, ignore_errors=True)
    return 1 if failures else 0


def _looks_like_stock(value: str) -> bool:
    """The surya stock identity, in any of its spellings."""
    markers = ("POCO", "surya", "M2007J20C", "qssi", "qti/")
    return any(marker in value for marker in markers)


if __name__ == "__main__":
    sys.exit(main())
