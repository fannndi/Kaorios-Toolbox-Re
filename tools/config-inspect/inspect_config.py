#!/usr/bin/env python3
"""Inspect a `sys_keystore_cfg` blob the way the hook reads it.

Useful for answering "did my per-app rules actually reach the framework?" without
guessing. It re-implements the exact lookups in `HookConfig.java`, so if a query
answers `null` here, the hook answers `null` too.

Read the blob straight off the device:

    adb shell settings get global sys_keystore_cfg
    # or, with root:
    su -c 'settings get global sys_keystore_cfg'

Then feed it back in:

    python inspect_config.py --blob 'k2:...'
    python inspect_config.py --json cfg.json --setting com.example.app:secure:android_id
    python inspect_config.py --blob 'k2:...' --installer com.example.app
    python inspect_config.py --blob 'k2:...' --feature android.hardware.keystore
    python inspect_config.py --blob 'k2:...' --remove secure:example_key

The transport is `k2:` + base64(utf8 XOR KEY), the same fixed key as
`app/.../Codec.kt` and `hook/.../HookCodec.java`.

Only the standard library is required.
"""
from __future__ import annotations

import argparse
import base64
import json
import sys

# Must stay in sync with Codec.kt / HookCodec.java.
KEY = bytes([
    0x4B, 0x53, 0x32, 0x7A, 0x11, 0x9C, 0x5E, 0x27,
    0xA3, 0x6D, 0x38, 0xF1, 0x72, 0x0B, 0xD4, 0x67,
])
PREFIX = "k2:"

NAMESPACES = ("global", "secure", "system")


def decode(raw: str) -> str:
    """Mirror HookCodec.decode: pass through anything that is not k2: prefixed."""
    if raw is None or not raw.startswith(PREFIX):
        return raw
    data = bytearray(base64.b64decode(raw[len(PREFIX):]))
    for index in range(len(data)):
        data[index] ^= KEY[index % len(KEY)]
    return data.decode("utf-8", "replace")


# --- HookConfig readers, re-implemented -------------------------------------


def flag(root: dict, name: str) -> bool:
    flags = root.get("flags")
    return bool(flags.get(name, False)) if isinstance(flags, dict) else False


def feature_state(root: dict, feature: str):
    features = root.get("features")
    if not isinstance(features, dict) or feature not in features:
        return None
    return bool(features[feature])


def installer_override(root: dict, package: str):
    installer = root.get("installer")
    if not isinstance(installer, dict):
        return None
    value = installer.get(package, "")
    return value or None


def prop_override(root: dict, package: str, key: str):
    props = root.get("props")
    if not isinstance(props, dict):
        return None
    value = None
    global_scope = props.get("*")
    if isinstance(global_scope, dict) and key in global_scope:
        value = global_scope[key]
    if package is not None:
        per_app = props.get(package)
        if isinstance(per_app, dict) and key in per_app:
            value = per_app[key]
    return value


def build_override(root: dict, package: str):
    build = root.get("build")
    if not isinstance(build, dict):
        return None
    merged: dict = {}
    found = False
    global_scope = build.get("*")
    if isinstance(global_scope, dict):
        merged.update(global_scope)
        found = True
    if package is not None:
        per_app = build.get(package)
        if isinstance(per_app, dict):
            merged.update(per_app)
            found = True
    return merged if found else None


def setting_value(root: dict, package: str, namespace: str, name: str):
    if package is None or namespace is None or name is None:
        return None
    settings = root.get("settings")
    apps = settings.get("apps") if isinstance(settings, dict) else None
    app = apps.get(package) if isinstance(apps, dict) else None
    table = app.get(namespace) if isinstance(app, dict) else None
    if not isinstance(table, dict) or name not in table:
        return None
    return table[name]


def should_remove(root: dict, namespace: str, name: str) -> bool:
    remove = root.get("remove")
    if not isinstance(remove, dict):
        return False
    names = remove.get(namespace)
    return isinstance(names, list) and name in names


# --- reporting ---------------------------------------------------------------


def describe(root: dict) -> None:
    print("SECTIONS")
    print("  flags      {}".format(
        ", ".join("{}={}".format(k, v) for k, v in root.get("flags", {}).items())
        or "(none)"))
    for section in ("build", "props"):
        entries = root.get(section)
        if isinstance(entries, dict):
            global_keys = len(entries.get("*", {}) or {})
            apps = [k for k in entries if k != "*"]
            print("  {:<10} global {} key(s), {} per-app block(s)".format(
                section, global_keys, len(apps)))
        else:
            print("  {:<10} (none)".format(section))

    installer = root.get("installer")
    print("  installer  {} rule(s)".format(len(installer) if isinstance(installer, dict) else 0))
    if isinstance(installer, dict):
        for package, value in installer.items():
            print("               {} -> {}".format(package, value))

    settings = root.get("settings")
    apps = settings.get("apps") if isinstance(settings, dict) else None
    if isinstance(apps, dict):
        total = sum(len(table) for app in apps.values()
                    for table in app.values() if isinstance(table, dict))
        print("  settings   {} app(s), {} value(s)".format(len(apps), total))
        for package, tables in apps.items():
            for namespace, table in tables.items():
                for name, value in table.items():
                    print("               {}/{}/{} = {!r}".format(package, namespace, name, value))
    else:
        print("  settings   (none)")

    remove = root.get("remove")
    if isinstance(remove, dict) and remove:
        print("  remove     {} namespace(s)".format(len(remove)))
        for namespace, names in remove.items():
            for name in names:
                print("               {}/{}".format(namespace, name))
    else:
        print("  remove     (none)")

    features = root.get("features")
    if isinstance(features, dict) and features:
        print("  features   {} rule(s)".format(len(features)))
        for name, value in features.items():
            print("               {} = {}".format(name, value))
    else:
        print("  features   (none)")


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    source = parser.add_mutually_exclusive_group(required=True)
    source.add_argument("--blob", help="the raw settings value, e.g. 'k2:...'")
    source.add_argument("--json", help="a plain (already decoded) JSON file")
    parser.add_argument("--installer", help="package doing the asking")
    parser.add_argument("--setting", help="PACKAGE:NAMESPACE:KEY")
    parser.add_argument("--remove", help="NAMESPACE:KEY")
    parser.add_argument("--feature", help="feature string")
    parser.add_argument("--prop", help="PACKAGE:KEY (use * as the package for the global scope)")
    parser.add_argument("--build", help="PACKAGE (use * for the global scope)")
    args = parser.parse_args()

    if args.json:
        with open(args.json, "r", encoding="utf-8") as handle:
            text = handle.read()
    else:
        text = decode(args.blob)

    try:
        root = json.loads(text)
    except Exception as error:  # noqa: BLE001 - report and exit cleanly
        print("Could not parse config JSON: {}".format(error))
        print("First 120 chars: {!r}".format(text[:120]))
        return 1

    if not isinstance(root, dict):
        print("Config root is not an object")
        return 1

    describe(root)

    queries = 0
    if args.installer:
        queries += 1
        print("\ninstallerOverride({!r}) -> {!r}".format(
            args.installer, installer_override(root, args.installer)))
    if args.setting:
        parts = args.setting.split(":")
        if len(parts) != 3:
            parser.error("--setting expects PACKAGE:NAMESPACE:KEY")
        queries += 1
        print("\nsettingValue({!r}, {!r}, {!r}) -> {!r}".format(
            parts[0], parts[1], parts[2], setting_value(root, *parts)))
    if args.remove:
        parts = args.remove.split(":")
        if len(parts) != 2:
            parser.error("--remove expects NAMESPACE:KEY")
        queries += 1
        print("\nshouldRemove({!r}, {!r}) -> {}".format(
            parts[0], parts[1], should_remove(root, *parts)))
    if args.feature:
        queries += 1
        print("\nfeatureState({!r}) -> {!r}  (None = fall through to stock)".format(
            args.feature, feature_state(root, args.feature)))
    if args.prop:
        package, _, key = args.prop.partition(":")
        if not key:
            parser.error("--prop expects PACKAGE:KEY")
        queries += 1
        print("\npropOverride({!r}, {!r}) -> {!r}".format(
            package, key, prop_override(root, package, key)))
    if args.build:
        queries += 1
        merged = build_override(root, args.build)
        print("\nbuildOverride({!r}) -> {} key(s)".format(
            args.build, len(merged) if merged else 0))
        if merged:
            for key in sorted(merged):
                print("    {} = {!r}".format(key, merged[key]))

    if queries:
        print()
    return 0


if __name__ == "__main__":
    sys.exit(main())
