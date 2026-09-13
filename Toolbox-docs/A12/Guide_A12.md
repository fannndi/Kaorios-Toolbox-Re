# Kaorios Toolbox — Android 12 Patch Guide

This branch (`a12-support`) is based on commit `ef0a3a4`, the last revision that
still shipped the `Toolbox-patcher/` source tree and the legacy hook classes.
Its goal is to keep Android 12 (API 31) working, without giving up the newer
hook design for Android 13+.

---

## 1. Why Android 12 stopped working

The repository was restructured on 2026-04-19. In one batch of commits the whole
patcher was deleted and the documentation was rewritten around a new hook
design:

| Removed | Added |
| --- | --- |
| `Toolbox-patcher/` (shell patcher, hook smali, APK) | `Toolbox-docs/Guide_V2.0.3+.md` |
| `Module/`, `.github/workflows/` | `Template_V203/*` targeting `AndroidKeyStoreKeyPairGeneratorSpi` |

Two separate problems came out of that.

**Problem 1 — hardcoded register numbers.** `kaorios_patches.sh` matched its
anchors on the compiled form of one specific ROM:

```python
if 'const/4 v4, 0x0' in line:          # AndroidKeyStoreSpi
    ...  'aput-object v2, v3, v4' ...
```

It also wrote its scratch values into `v0` / `v1` without ever touching
`.registers`. On a ROM where `ApplicationPackageManager.hasSystemFeature(String, int)`
compiles to `.registers 3`, all three registers are parameters — so the injected
code was overwriting `p0` and `p1`.

The published V2.0.3+ guide has the same fragility and works around it by asking
a human to do the arithmetic:

> Increase the current register count by `1`. Replace `vX` with the register
> number at `registers - 2`.

**Problem 2 — a hardcoded D8 `--min-api 35`.** In `apk_ops.sh`:

```bash
local MIN_API=35   # "API 35 (Android 15) is used to ensure compatibility ..."
```

D8 uses `--min-api` to decide how much desugaring and API backporting to apply.
Patching an Android 12 framework while telling D8 the target is Android 15 means
the optimiser is allowed to emit constructs that API 31 is not required to
support. This affected Android 13 and 14 as well.

Note: `android.security.keystore2.AndroidKeyStoreKeyPairGeneratorSpi` **does
exist** on Android 12 (verified against AOSP `android-12.0.0_r1`), so a missing
class is never the reason a hook fails.

---

## 2. What changed in this branch

### 2.1 A register-aware engine

`scripts/lib/smali_engine.py` replaces four inline Python heredocs. For every
hook it:

- locates the method by **signature**, never by register numbers,
- reads the real `.registers` / `.locals` value,
- resolves `p0`, `p1`, … from the method descriptor,
- captures the actual `return-object` register the ROM uses,
- allocates the scratch registers a snippet needs,
- refuses to grow a frame when a parameter is addressed in `vN` form, because
  that would silently turn a parameter into a local,
- verifies every emitted `invoke-*` still fits the 4-bit (35c) encoding.

### 2.2 How scratch registers are chosen

This is the part the guide used to leave to a human. Two strategies exist, and
the engine picks automatically:

**Strategy A — grow the frame.** New locals are appended past the existing ones,
so the original code has never touched them. This is the safe option, but it
shifts every parameter up, and `invoke-static {p0}` is a 4-bit operand.

**Strategy B — reuse the highest existing local.** Used only when strategy A is
impossible. This is exactly the `registers - 2` arithmetic from the guide.

Worked example, `AndroidKeyStoreKeyPairGeneratorSpi.generateKeyPair()` with
`.registers 16` and one parameter (`this` at v15):

- Strategy A would grow to 17, moving `this` to **v16** — one past the v15 limit
  of the 35c encoding, so `invoke-static {p0}` would no longer assemble.
- The engine therefore falls back to strategy B and reports
  `reused v14 as scratch (growing would push a parameter past v15)`.

### 2.3 Two hook profiles

| Profile | Target | Hook class | Keystore strategy |
| --- | --- | --- | --- |
| `legacy` | Android 12 | `com.android.internal.util.kaorios.*` | read the key entry from `KeyStore2.getKeyEntry` |
| `modern` | Android 13+ | `android.security.kaorios.KaoriosHook` | synthesise a software key pair in `AndroidKeyStoreKeyPairGeneratorSpi` |

The profile is derived from the SDK level (`scripts/core/version.sh`) and can be
forced with `--profile`. Run `./scripts/patcher.sh --list-hooks` to print the
full table.

#### legacy hooks

| Class | Method | Anchor | Hook |
| --- | --- | --- | --- |
| `android.app.Instrumentation` | `newApplication(Class, Context)` | before final return | `KaoriPropsUtils->KaoriProps(Context)V` |
| `android.app.Instrumentation` | `newApplication(ClassLoader, String, Context)` | before final return | `KaoriPropsUtils->KaoriProps(Context)V` |
| `android.app.ApplicationPackageManager` | `hasSystemFeature(String, int)Z` | after `.registers` | `KaoriFeatureOverrides->getOverride(...)Boolean` |
| `android.security.KeyStore2` | `getKeyEntry(KeyDescriptor)` | before final return | `KaoriKeyboxHooks->KaoriGetKeyEntry(...)` |
| `android.security.keystore2.AndroidKeyStoreSpi` | `engineGetCertificateChain(String)` | after `.registers` **and** before final return | `KaoriPropsUtils->KaoriGetCertificateChain()V` + `KaoriKeyboxHooks->KaoriGetCertificateChain(...)` |

#### modern hooks

| Class | Method | Anchor | Hook |
| --- | --- | --- | --- |
| `android.app.Instrumentation` | both `newApplication` overloads | before final return | `KaoriosHook->initContext(Context)V` |
| `android.app.ApplicationPackageManager` | `hasSystemFeature(String, int)Z` | after `.registers` | `KaoriosHook->hasSystemFeature(String, int)Boolean` |
| `android.security.keystore2.AndroidKeyStoreKeyPairGeneratorSpi` | `generateKeyPair()` | after `.registers` | `KaoriosHook->initGenerateSoftwareKeyPair(Object)KeyPair` |
| `android.security.keystore2.AndroidKeyStoreSpi` | `engineGetCertificateChain(String)` | before final return | `KaoriosHook->CertificateChainIfNeeded(...)` |
| `com.android.server.SystemServer` | *in `services.jar`* | before the `startOtherServices(...)` call | `KaoriosHook->initSystemServer()V` |

The `hasSystemFeature` hook in **both** profiles is wrapped in a `.catchall`
block. This is a deliberate divergence from the published guides: if the override
lookup throws, the method falls through to stock behaviour instead of crashing
every app at startup.

### 2.4 Hook contract verification

Before anything is written, the patcher checks that every hook method its
snippets call is actually declared in the tree:

```
[ok       ] keystore2.getKeyEntry                       resolved 1 hook reference(s)
[missing  ] instrumentation.newApplication(Class,Context)
            unresolved: Lcom/android/internal/util/kaorios/KaoriPropsUtils;->KaoriProps(Landroid/content/Context;)V
```

The references are extracted from the snippet text itself, so the check cannot
drift away from what the patcher emits. This matters because the hook payload is
not pinned to this repository: `scripts/update_kaorios.sh` pulls the latest
release, and if a future release renames a class or changes a signature, the
patched framework would assemble cleanly and then die at boot with
`NoSuchMethodError`. Failing early with the missing signature named is far
cheaper to debug.

The check is skipped during `--dry-run` (the hook classes are not injected then,
so they cannot resolve yet) and can be disabled with `--no-verify-hooks`.

---

## 3. Running the patcher

```bash
cd Toolbox-patcher

# Android 12
./scripts/patcher.sh --sdk 31 framework.jar

# inspect first, write nothing
./scripts/patcher.sh --sdk 31 --dry-run framework.jar

# Android 13+, which also needs the SystemServer hook in services.jar
./scripts/patcher.sh --sdk 33 --services-jar services.jar framework.jar

# keep the decompiled trees for inspection
./scripts/patcher.sh --sdk 31 --keep-work framework.jar

# print the hook table
./scripts/patcher.sh --list-hooks
```

| Flag | Meaning |
| --- | --- |
| `-j, --jar PATH` | `framework.jar` to patch (default `./framework.jar`) |
| `--services-jar PATH` | also patch `services.jar` (needed by the modern profile) |
| `-s, --sdk N` | target SDK level; `31` = Android 12 |
| `-p, --profile NAME` | `legacy` or `modern`; default derived from the SDK |
| `-r, --rom-dir PATH` | ROM root, used to locate `system/build.prop` |
| `--dry-run` | report what would change, write nothing |
| `--keep-work` | keep the decompiled trees |
| `--no-d8` | skip D8 optimisation |
| `--no-module` | skip building the Magisk module zip |
| `--no-verify-hooks` | skip the hook contract check |
| `--json` | machine-readable patch report |

Output is one line per hook:

```
[patched  ] applicationpackagemanager.hasSystemFeature(String,int)  inserted 17 lines after `.registers 5` | frame grown to registers 5
[patched  ] keystore2.getKeyEntry                                   inserted 2 lines before final `return-object v0`
[not-found] androidkeystorespi.engineGetCertificateChain            android/security/keystore2/AndroidKeyStoreSpi.smali absent from this ROM
```

`not-found` is not always an error — a class that does not exist in the jar
cannot be hooked. `failed` means the class exists but the anchor did not match,
and the run stops with a non-zero exit code.

### Requirements

- `java` on `PATH`
- `python3` on `PATH` (or `KAORIOS_PYTHON=/path/to/python`)
- `tools/apktool.jar` (already in this repository)
- `d8` for the optimisation step; if missing it is skipped with a warning

On Git Bash the scripts translate paths with `cygpath` before handing them to
native tools, because `java` and `python` cannot resolve `/c/...` paths.

### Self-test

```bash
python3 scripts/tests/test_engine.py
```

Builds miniature framework trees using register layouts the old patcher could
not handle, and asserts the engine patches them correctly for both profiles,
including the `generateKeyPair()` case where growing the frame would break the
invoke encoding. It also checks that the hook contract verification rejects a
renamed hook method.

To check the real payload in this repository against the patch table:

```bash
mkdir -p /tmp/vcheck/smali/com/android/internal/util/kaorios
cp kaorios_toolbox/utils/kaorios/*.smali /tmp/vcheck/smali/com/android/internal/util/kaorios/
python3 scripts/lib/smali_engine.py verify --decompile-dir /tmp/vcheck --profile legacy
```

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

- The engine is verified against synthetic framework trees and a real `apktool`
  decompile, but **not** against a booting Android 12 device. Verify on your own
  ROM before publishing a release.
- The modern profile needs the `android.security.kaorios.KaoriosHook` payload,
  which ships with the V2.0.3+ release and is **not** in this repository. The
  patcher injects only the legacy classes, so for `--profile modern` you must
  supply that payload yourself.
- SDK levels below 28 are rejected: the keystore2 class layout those ROMs use is
  not covered by these hooks.
- `--min-api` is taken to be the target SDK level. If a ROM reports an unusual
  `ro.build.version.sdk`, pass `--sdk` explicitly.
