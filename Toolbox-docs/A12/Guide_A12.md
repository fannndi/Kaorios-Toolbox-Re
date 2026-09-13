# Kaorios Toolbox — Android 12 Patch Guide

This branch (`a12-support`) is based on commit `ef0a3a4`, the last revision that
still shipped the `Toolbox-patcher/` source tree and the legacy hook classes.
Its goal is to keep Android 12 (API 31) working.

---

## 1. Why Android 12 stopped working

The repository was restructured on 2026-04-19. In one batch of commits the whole
patcher was deleted and the documentation was rewritten around a new hook
design:

| Removed | Added |
| --- | --- |
| `Toolbox-patcher/` (shell patcher, hook smali, APK) | `Toolbox-docs/Guide_V2.0.3+.md` |
| `Module/`, `.github/workflows/` | `Template_V203/*` targeting `AndroidKeyStoreKeyPairGeneratorSpi` |

Two separate problems came out of that:

**Problem 1 — a different hook target.** V1.0.9 hooks
`android.security.KeyStore2.getKeyEntry()` and reads the key entry that the
keystore daemon returns. V2.0.3+ instead hooks
`android.security.keystore2.AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair()`
and synthesises a software key pair. That class *does* exist on Android 12
(verified against AOSP `android-12.0.0_r1`), so the class itself is not the
blocker — but the V2.0.3+ snippets are literal copies of a specific ROM's
compiled method (`.registers 16`, labels `:cond_24` / `:cond_30`), so they only
match the Android version they were extracted from.

**Problem 2 — a hardcoded D8 `--min-api 35`.** In `apk_ops.sh`:

```bash
local MIN_API=35   # "API 35 (Android 15) is used to ensure compatibility ..."
```

D8 uses `--min-api` to decide how much desugaring and API backporting to apply.
Patching an Android 12 framework while telling D8 the target is Android 15 means
the optimiser is allowed to emit constructs that API 31 is not required to
support. This is the most likely mechanical cause of the breakage, and it also
affects Android 13 and 14.

---

## 2. What changed in this branch

The patch logic was rewritten so it no longer depends on any register layout.

### 2.1 A register-aware engine

`scripts/lib/smali_engine.py` replaces four inline Python heredocs that matched
on hardcoded registers. The old code assumed:

```python
if 'const/4 v4, 0x0' in line:          # AndroidKeyStoreSpi
    ...  'aput-object v2, v3, v4' ...
```

The engine instead:

- locates a method by its **signature**, not by register numbers,
- reads the real `.registers` / `.locals` value,
- **grows the frame** when a snippet needs scratch locals it does not have,
- resolves `p0`, `p1`, … from the method descriptor,
- captures the actual `return-object` register the ROM uses,
- refuses to grow a frame when a parameter is addressed in `vN` form, because
  that would silently turn a parameter into a local,
- verifies that every emitted `invoke-*` still fits the 4-bit (35c) register
  encoding.

The concrete bug this fixes: `ApplicationPackageManager.hasSystemFeature(String, int)`
compiles to `.registers 3` on some ROMs. All three registers are the parameters,
so the old snippet writing to `v0` and `v1` was writing to `p0` and `p1`. The
engine grows the frame to `.registers 5` first.

### 2.2 Target SDK is an input

`scripts/core/version.sh` resolves the SDK level in this order:

1. `--sdk N` on the command line
2. `KAORIOS_TARGET_SDK` environment variable
3. `ro.build.version.sdk` from a `build.prop` near the jar or under `--rom-dir`
4. a heuristic on the decompiled tree (flagged as a lower bound)

`--min-api` for D8 is then derived from that value. It is never guessed.

### 2.3 The Android 12 hook set

| Class | Method | Anchor | Hook called |
| --- | --- | --- | --- |
| `android.app.Instrumentation` | `newApplication(Class, Context)` | before final return | `KaoriPropsUtils->KaoriProps(Context)V` |
| `android.app.Instrumentation` | `newApplication(ClassLoader, String, Context)` | before final return | `KaoriPropsUtils->KaoriProps(Context)V` |
| `android.app.ApplicationPackageManager` | `hasSystemFeature(String, int)Z` | after `.registers` | `KaoriFeatureOverrides->getOverride(Context, String, String)Boolean` |
| `android.security.KeyStore2` | `getKeyEntry(KeyDescriptor)` | before final return | `KaoriKeyboxHooks->KaoriGetKeyEntry(KeyEntryResponse)KeyEntryResponse` |
| `android.security.keystore2.AndroidKeyStoreSpi` | `engineGetCertificateChain(String)` | after `.registers` **and** before final return | `KaoriPropsUtils->KaoriGetCertificateChain()V` + `KaoriKeyboxHooks->KaoriGetCertificateChain([Certificate;)[Certificate;` |

The `hasSystemFeature` hook is wrapped in a `.catchall` block. If the override
lookup throws, the method falls through to stock behaviour instead of taking
`system_server` down with it.

---

## 3. Running the patcher

```bash
cd Toolbox-patcher

# Android 12
./scripts/patcher.sh --sdk 31 framework.jar

# inspect first, write nothing
./scripts/patcher.sh --sdk 31 --dry-run framework.jar

# keep the decompiled tree for inspection
./scripts/patcher.sh --sdk 31 --keep-work framework.jar
```

Options:

| Flag | Meaning |
| --- | --- |
| `-j, --jar PATH` | `framework.jar` to patch (default `./framework.jar`) |
| `-s, --sdk N` | target SDK level; `31` = Android 12 |
| `-r, --rom-dir PATH` | ROM root, used to locate `system/build.prop` |
| `--dry-run` | report what would change, write nothing |
| `--keep-work` | keep `framework_decompile/` |
| `--no-d8` | skip D8 optimisation |
| `--no-module` | skip building the Magisk module zip |
| `--json` | machine-readable patch report |

Output is one line per hook:

```
[patched  ] applicationpackagemanager.hasSystemFeature(String,int)      inserted 17 lines after `.registers 5`
[patched  ] keystore2.getKeyEntry                                       inserted 2 lines before final `return-object v0`
[not-found] androidkeystorespi.engineGetCertificateChain                android/security/keystore2/AndroidKeyStoreSpi.smali absent from this ROM
```

`not-found` is not always an error — a class that does not exist in the ROM
cannot be hooked. `failed` means the class exists but the anchor did not match,
and the run stops with a non-zero exit code.

### Requirements

- `java` on `PATH`
- `python3` on `PATH` (or `KAORIOS_PYTHON=/path/to/python`)
- `tools/apktool.jar` (already in this repository)
- `d8` for the optimisation step; if missing it is skipped with a warning

### Self-test

```bash
python3 scripts/tests/test_engine.py
```

Builds a synthetic framework tree that deliberately uses register counts the old
patcher could not handle, then asserts the engine patches it correctly and is
idempotent.

---

## 4. Verifying a patched build

After flashing, check in the app:

- **Framework sync** reports the values written to the device.
- **Play Integrity** returns a result and the value props show as green.
- `getprop persist.sys.kaorios` returns `kousei`.

If the device bootloops, the decompiled tree is the first thing to inspect —
re-run with `--keep-work` and confirm the reported anchors line up with the
actual method bodies.

---

## 5. Known limitations

- The engine was validated against a synthetic framework tree and a real
  `apktool` decompile, but **not** against a booting Android 12 device. Verify
  on your own ROM before publishing a release.
- The `modern` profile (SDK ≥ 33) needs the `android.security.kaorios.KaoriosHook`
  payload, which this branch does not ship. The patcher warns and continues; the
  resulting framework will not work unless you also supply that payload.
- SDK levels below 28 are rejected: the keystore2 class layout those ROMs use is
  not covered by these hooks.
