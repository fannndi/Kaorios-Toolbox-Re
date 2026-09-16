#!/usr/bin/env python3
"""Resolve which property file actually wins each key, for a given ROM tree.

`rom_audit.py` answers "which property files exist and what do the jars contain".
This script answers the other half: **who wins**. Android's init loads property
files in a fixed order and processes `import` directives *inline*, so for any
duplicated key the last assignment in the expanded load order is the value the
device ends up with.

That matters because surya ROMs end both `/vendor/build.prop` and
`/vendor/odm/etc/build.prop` with

    import /vendor[/odm/etc]/build_${ro.boot.product.hardware.sku}.prop

and the imported SKU file re-declares the whole identity block. Rewriting the key
in the parent file therefore looks successful while the SKU file silently puts
the stock value back.

How init finds the files (verified against the stock surya `init` binary, which
contains the templates `/build.prop`, `/default.prop` and `/etc/build.prop`, plus
the string "Could not expand import: "):

  * for each partition in `ro.product.property_source_order`, init loads
    `<mount_point>/build.prop`, `<mount_point>/default.prop` and
    `<mount_point>/etc/build.prop`
  * on surya the odm partition is mounted at `/vendor/odm`, so the odm files are
    `/vendor/odm/etc/build.prop` — there is no top-level `/odm` in the dump
  * `import <path>` is expanded inline, with `${property}` substitution

Usage
-----
    # who currently wins the identity keys?
    python prop_resolve.py --rom /path/to/MIUI13/ROM --sku surya

    # prove a patch: apply the map to some files first, then resolve
    python prop_resolve.py --rom /path/to/MIUI13/ROM --sku surya \
        --patch vendor/build.prop=odm.json \
        --patch vendor/odm/etc/build.prop=odm.json

    # focus on specific keys
    python prop_resolve.py --rom /path/to/ROM --sku surya --keys ro.product.model

Only the standard library is required.
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys

# init loads these templates for every partition it knows about. Verified from
# the stock surya init binary, which contains the literal templates
# "/build.prop", "/default.prop" and "/etc/build.prop" (that is how a single
# template serves both /product/build.prop on Android 10 and
# /product/etc/build.prop on Android 11+).
PARTITION_ROOTS = [
    "vendor/odm",   # surya mounts odm inside vendor; there is no top-level /odm
    "vendor",
    "product",
    "system_ext",
    "system",
]
TEMPLATES = ["build.prop", "default.prop", "etc/build.prop"]

# Lowest-priority partition first, so the highest-priority one is applied last.
# Note: the *within-file* result is exact (inline `import` ordering), while the
# cross-partition order is a model of init's behaviour — the report lists every
# writer for each key so the competition is visible either way.
LOAD_ORDER = [
    "{}/{}".format(root, template)
    for root in reversed(PARTITION_ROOTS)
    for template in TEMPLATES
]

# Keys that decide the device identity, in the order a spoof cares about.
IDENTITY_KEYS = [
    "ro.product.model",
    "ro.product.brand",
    "ro.product.device",
    "ro.product.name",
    "ro.product.manufacturer",
    "ro.build.fingerprint",
    "ro.build.description",
    "ro.build.id",
    "ro.build.version.incremental",
    "ro.product.odm.model",
    "ro.product.vendor.model",
    "ro.product.product.model",
    "ro.product.system.model",
    "ro.odm.build.fingerprint",
    "ro.vendor.build.fingerprint",
    "ro.bootimage.build.fingerprint",
]

PROP_REF = re.compile(r"\$\{([^}]+)\}")
ASSIGN = re.compile(r"^\s*([A-Za-z0-9_.\-]+)\s*=\s*(.*)$")
IMPORT = re.compile(r"^\s*import\s+(\S+)\s*$")


def device_to_local(rom: str, device_path: str) -> str | None:
    """Map an absolute on-device path to a file inside the extraction.

    The system partition is dumped at <rom>/system/system/... while product,
    vendor and system_ext sit directly under <rom>.
    """
    relative = device_path.lstrip("/")
    for prefix in ("", "system/"):
        candidate = os.path.join(rom, prefix + relative)
        if os.path.exists(candidate):
            return candidate
    return None


def parse_lines(text: str) -> list[str]:
    return text.splitlines()


def apply_patch(path: str, props: dict[str, str]) -> tuple[int, int]:
    """Same semantics as the Kotlin PropPatcher: replace in place, else append."""
    if not os.path.exists(path):
        return 0, 0
    with open(path, "r", encoding="utf-8", errors="replace") as handle:
        lines = handle.read().split("\n")
    pending = dict(props)
    replaced = 0
    for index, raw in enumerate(lines):
        stripped = raw.strip()
        if not stripped or stripped.startswith("#") or "=" not in stripped:
            continue
        key = stripped.split("=", 1)[0].strip()
        if key in pending:
            lines[index] = "{}={}".format(key, pending.pop(key))
            replaced += 1
    appended = 0
    for key, value in pending.items():
        if lines and lines[-1].strip():
            lines.append("")
        lines.append("{}={}".format(key, value))
        appended += 1
    with open(path, "w", encoding="utf-8") as handle:
        handle.write("\n".join(lines))
    return replaced, appended


class Resolver:
    def __init__(self, rom: str, seed: dict[str, str]) -> None:
        self.rom = rom
        self.props: dict[str, str] = dict(seed)
        # key -> (value, "relative/file:line") for the last writer
        self.winners: dict[str, tuple[str, str]] = {}
        # key -> every assignment in load order, so the competition is visible
        self.writers: dict[str, list[tuple[str, str]]] = {}
        self.loaded: list[str] = []
        self.missing: list[str] = []

    def expand(self, value: str) -> str:
        def replace(match: re.Match[str]) -> str:
            return self.props.get(match.group(1), "")

        return PROP_REF.sub(replace, value)

    def load(self, relative: str, depth: int = 0) -> None:
        if depth > 8:
            return
        path = device_to_local(self.rom, "/" + relative)
        if path is None:
            self.missing.append(relative)
            return
        label = os.path.relpath(path, self.rom).replace(os.sep, "/")
        self.loaded.append(label)
        with open(path, "r", encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
        for number, raw in enumerate(lines, start=1):
            import_match = IMPORT.match(raw)
            if import_match:
                target = self.expand(import_match.group(1))
                self.load(target.lstrip("/"), depth + 1)
                continue
            if raw.lstrip().startswith("#"):
                continue
            assign = ASSIGN.match(raw)
            if not assign:
                continue
            key = assign.group(1)
            value = self.expand(assign.group(2).strip())
            source = "{}:{}".format(label, number)
            self.props[key] = value
            self.winners[key] = (value, source)
            self.writers.setdefault(key, []).append((value, source))


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter
    )
    parser.add_argument("--rom", required=True, help="ROM extraction root")
    parser.add_argument("--sku", default="", help="value of ro.boot.product.hardware.sku")
    parser.add_argument("--keys", default="", help="comma-separated keys (default: identity set)")
    parser.add_argument(
        "--patch",
        action="append",
        default=[],
        metavar="FILE=JSON",
        help="apply a JSON prop map to a ROM-relative file before resolving (repeatable)",
    )
    parser.add_argument(
        "--file", action="append", default=[], help="override the load order (repeatable)"
    )
    parser.add_argument(
        "--trace",
        default="",
        help="comma-separated keys to list every assignment for, in load order",
    )
    args = parser.parse_args()

    for entry in args.patch:
        if "=" not in entry:
            parser.error("--patch expects FILE=JSON, got {!r}".format(entry))
        relative, json_path = entry.split("=", 1)
        target = device_to_local(args.rom, "/" + relative.lstrip("/"))
        if target is None:
            parser.error("patch target not found in ROM: {}".format(relative))
        with open(json_path, "r", encoding="utf-8") as handle:
            props = {k: str(v) for k, v in json.load(handle).items()}
        replaced, appended = apply_patch(target, props)
        print("patched {}: {} replaced, {} appended".format(relative, replaced, appended))

    seed: dict[str, str] = {}
    if args.sku:
        seed["ro.boot.product.hardware.sku"] = args.sku

    resolver = Resolver(args.rom, seed)
    for relative in (args.file or LOAD_ORDER):
        resolver.load(relative)

    keys = [k.strip() for k in args.keys.split(",") if k.strip()] or IDENTITY_KEYS

    print()
    print("LOADED (in order)")
    for label in resolver.loaded:
        print("  {}".format(label))
    if resolver.missing:
        print("  (absent: {})".format(", ".join(resolver.missing)))

    print()
    width = max(len(k) for k in keys) + 2
    print("{}  {:<44}  {:<5} {}".format("key".ljust(width), "WINNING VALUE", "WRITERS", "SET BY"))
    print("-" * (width + 44 + 5 + 40))
    for key in keys:
        if key not in resolver.winners:
            print("{}  {:<44}  {:<5} {}".format(key.ljust(width), "(not set by any file)", "-", "-"))
            continue
        value, source = resolver.winners[key]
        count = len(resolver.writers.get(key, ()))
        shown = value if len(value) <= 42 else value[:39] + "..."
        print("{}  {:<44}  {:<5} {}".format(key.ljust(width), shown, count, source))

    if args.trace:
        for key in [k.strip() for k in args.trace.split(",") if k.strip()]:
            print()
            print("TRACE {}".format(key))
            entries = resolver.writers.get(key)
            if not entries:
                print("  (not set by any file)")
                continue
            for index, (value, source) in enumerate(entries):
                mark = "  <- WINNER" if index == len(entries) - 1 else ""
                print("  {:<46} {}{}".format(source, value, mark))

    print()
    print("WRITERS > 1 means the key is declared in several files; the last one in")
    print("the load order above wins. Within a single file an inline `import` is")
    print("processed where it appears, so the imported file's value is the last")
    print("write — which is why the per-SKU files must be patched too.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
