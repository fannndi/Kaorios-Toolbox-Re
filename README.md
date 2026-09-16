# Farewell Toolbox

**Farewell Toolbox** patches `framework.jar` / `services.jar` **on-device** with the Farewell hook and produces a flashable recovery zip. The hook lives inside the boot classpath, so it already runs inside every app and `system_server` process — **no Zygisk, no module, no companion process** (root is only used for shell helpers like `su -c`).

It combines three pillars:

1. **Framework patching** — a dexlib2-based engine injects hook entry points into `framework.jar` / `services.jar` using signature-based rules gated per ROM profile.
2. **Play Integrity / certification** — keybox spoof with software-generated attestation chains, PIF fingerprint spoofing, per-app property/setting overrides, up to a `MEETS_STRONG_INTEGRITY` verdict.
3. **Native property layer** — `farewelld`, a dependency-free C++17 helper that writes spoofed values directly into the Android property areas so *native* readers (`__system_property_get`, e.g. DroidGuard) see them too.

> ⚠️ This tool modifies the boot classpath and spoofs device identity for Play certification — that violates the Google Play ToS and can cost you your account or the device's certification. Use only on devices you are prepared to re-flash, and always keep the stock restore zip.

## 🗺️ Repository map

| Path | Module / purpose | Language |
|---|---|---|
| `app/` | Farewell Toolbox APK (Jetpack Compose) — patch status, zip build & export, PIF setup, keybox verify, data sync | Kotlin |
| `patcher/` | dexlib2 patch engine, flashable zip builder, prop patcher, keybox verifier, CLI (`PatchCliKt`) | Kotlin (JVM) |
| `hook/` | `android.security.keystore2.*` hook library, compiled to `hook.dex` (R8-obfuscated by default) | Java 11 |
| `hook/entry-template/` | `KeyStoreHooks.template.java` — facade template; class/method names generated per build | — |
| `native/farewelld/` | C++17 property-area daemon (links libc/liblog only, static libc++) | C++ |
| `native/rom/` | Optional ROM-integration payload (`/system/bin/farewelld`, init rc, SELinux CIL, privapp permissions, `props.conf`) | — |
| `native/tests/` | `installer-test.sh` — end-to-end installer test in a disposable Linux/WSL env | Shell |
| `Toolbox-data/` | Remote app data (PIF fingerprint, keybox, device models, per-app props, blacklist) | JSON/TXT |
| `Toolbox-languages/` | Translations (`values*`, merged into the app as an extra `res.srcDirs`) | XML |
| `Toolbox-docs/` | Patch guides (EN/VI), feature docs, reference smali (`Template/Template_V2060`) | MD |
| `Toolbox-Update/` | `update_pif.sh` — daily PIF fingerprint auto-update script | Shell |
| `.github/workflows/` | `update_pif` (daily cron), `update_keybox`, `notification` | YAML |
| `app/src/main/assets/zip/` | Shell `update-binary` skeleton (TWRP-shell installer pattern by osm0sis@xda) | Shell |

## 🧩 How the pieces fit together

### Build-time pipeline

```
generateHookIdentity ──► build/hook-identity.txt (per-build random class + 21 method names)
        │                        │
        ▼                        ▼
 hook/entry-template      generated patcher HookIdentity.kt
 (Java facade, randomized names)
        │
        ▼
 :hook:makeHookDex ──► build/hook/hook.dex   (R8: -repackageclasses 'o' + keep facade)
        │                                       │
        ▼                                       ▼
 app/src/main/assets/hook.dex          :patcher (dexlib2) injects hook calls
        │                              into framework/services
        ▼
 :app:assembleDebug ──► APK ──► (on device) flashable zip:
                                Farewell-Patch-<profile>-<stamp>.zip
                                Farewell-Stock-<profile>-<stamp>.zip
```

- **Per-build randomized hook identity.** `generateHookIdentity` produces `KeyStoreCompat<random-hex>` plus random method names for **all 21 hook entry points**; the generated Java facade and the patcher constants come from that single source, so `Class.forName` scanning or hardcoded string matching fails. Rotate with `./gradlew -PrenewHookIdentity :app:assembleDebug` (or delete `build/hook-identity.txt`); otherwise the identity persists across builds.
- **R8-obfuscated hook dex.** The hook is compiled by R8 (`-repackageclasses 'o'`, helpers renamed) while the entry class + members are kept — helper class names (`HookState`, `KeyboxEngine`, `AttestationBuilder`, …) no longer exist as strings in the flashed artifacts. `-PhookObfuscate=false` falls back to plain D8 for debugging.
- **Obfuscated configuration transport.** `sys_keystore_cfg` and `sys_keybox_cfg` in `Settings.Global` are stored as `k2:` base64+XOR blobs, so `settings list global` never shows readable PIF/keybox payloads.

### Runtime flow

1. The patched jars boot: the hook initializes inside every process (`INIT_CONTEXT`) and in `system_server` (`INIT_SYSTEM_SERVER`, including `Build` field unfinalization so GMS/Play checks in system processes see the same identity).
2. The app (or the zip's `props.conf` + `farewelld`) writes the spoof config into `Settings.Global`; the hook reads it through the obfuscated transport.
3. `farewelld` (stock mode: streamed to `/data/local/tmp/pfix` via root) applies the native property layer by writing directly into the property areas (resetprop technique, layout verified against bionic `android-10.0.0_r47` / `android-12.1.0_r27`). Status: `getprop sys.pfix_status`.

## 📱 Supported ROM profiles

**Scope is surya only** — POCO X3 (`M2007J20CG` / `M2007J20CT` / `M2007J20CI`).

| Profile id | Device | ROM | Android |
|---|---|---|---|
| `surya-miui12` | POCO X3 (surya) | MIUI 12 | 10 (SDK 29) |
| `surya-miui13` | POCO X3 (surya) | MIUI 13 | 12 (SDK 31) |
| `surya-miui14` | POCO X3 (surya) | MIUI 14 | 12 (SDK 31) |

The app detects device codename, MIUI version and Android API, then picks the profile automatically. Rules are gated by `apiRange`, so the legacy (keystore v1, `AppsFilter`, `DevicePolicyCacheImpl.getScreenCaptureDisabled`) and modern (keystore2, `WindowState.isSecureLocked`) patch sets stay separate and maintainable. Rules that could only fire on Android 13+ have been removed — no surya ROM is A13+.

### ROM audit notes (surya MIUI 12 / 13 / 14)

- Patch targets live in `/system/framework/framework.jar` and `/system/framework/services.jar`. `miui-framework.jar` (boot classpath), `miui-services.jar` and `miuix.jar` contain no copies of the patched classes, so they stay untouched. (MIUI 12 ships none of those three jars at all.)
- `SettingsProvider.apk` is **never modified**. Settings spoof/removal/probe hooks `Settings$NameValueCache.getStringForUser` client-side, which exists in all three ROMs and covers both app and `system_server` reads.
- Installer spoof uses `PackageManagerService.getInstallerPackageName(String)`, which is where all three ROMs declare it.
- App-list hiding uses `AppsFilter` (MIUI 13/14) and `PackageManagerService.filterAppAccess*` (MIUI 12).
- Expected absences on MIUI 12 (Android 10): `ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk` and `AppsFilter.shouldFilterApplication` (the app-filter path there is `PackageManagerService.filterAppAccessLPr`).
- Target jars use DEX 039 on all three ROMs; the A17-only 040 normalization never triggers.
- The installer deletes exactly the boot artifacts that exist (`boot-framework.*` under `framework/arm[64]`, `framework/oat/arm64/services.*`) plus dalvik caches, so ART re-verifies and recompiles the patched jars on first boot. `miui-services` / `boot-miui-framework` artifacts are left alone because those jars are not patched.

Full findings, including the per-SKU property trap that made the identity spoof a no-op before it was fixed: [ROM Audit: Surya](Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md).

## 🛡️ Play Integrity / Play Store certification

`Apply Play Integrity setup` writes a single `sys_keystore_cfg` JSON into `Settings.Global` (via root):

- `build`: PIF Build fields applied to `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf` (and common attestation checkers: `vvb2060.keyattestation`, `qwq233.keyattestation`, `reveny.nativecheck`, `nullptr.nativetest`, …) from the synced `Pif-props.json`.
- `props`: `SystemProperties.get` overrides (`ro.build.fingerprint`, `ro.product.*`, `ro.build.version.security_patch`).
- `flags`: hidden developer status, hidden app list, FLAG_SECURE and keybox spoof.

**Keybox + attestation.** Keybox XML is imported from the device into `sys_keybox_cfg`. `KeyboxEngine` implements the software keypair path: on a hardware-attestation request (`KeyGenParameterSpec` with an attestation challenge) it generates a P-256 keypair and a fresh X.509 leaf containing a KeyDescription extension (verified boot state, locked bootloader, OS/patch levels, attestation application id) signed by the keybox private key, then returns `[newLeaf, keybox chain...]` for `engineGetCertificate` / `engineGetCertificateChain` through an alias cache.

The leaf is deliberately realistic: `ATTESTATION_ID_BRAND/DEVICE/PRODUCT/MANUFACTURER/MODEL`, a real APK signing-certificate digest in `attestationApplicationId`, a derived non-zero `verifiedBootHash`, `RootOfTrust` in the official DER order (`verifiedBootKey`, `deviceLocked`, `verifiedBootState`, `verifiedBootHash`), `attestationVersion` 3 on Android 10–11 / 4 on Android 12+.

**STRONG verdict math (from Google's docs):**

- **Android 13+**: `MEETS_STRONG_INTEGRITY` = `MEETS_DEVICE_INTEGRITY` + OS/vendor security patches from the last 12 months. The attester stamps `osPatchLevel`/`vendorPatchLevel`/`bootPatchLevel` from the PIF patch (never moving the device patch backwards); the app warns when a PIF patch is older than 12 months or older than the device patch.
- **Android 12 and lower**: STRONG only needs hardware-backed proof of boot integrity — no patch recency requirement. That is exactly the MIUI 12/13/14 situation, so a valid keybox + software-generated TEE-level attestation is sufficient.
- `MEETS_BASIC_INTEGRITY` on Android 13+ only requires a Google-provided attestation root — our chain terminates at the Google root bundle.
- The app's **STRONG readiness check** combines these rules with the keybox verification (chain, revocation, boot state, lock state) and reports what still blocks a STRONG verdict.

**Rootless model, known limit:** native property reads (`__system_property_get` inside DroidGuard) cannot be intercepted from framework Java — that layer is covered by `farewelld`. Pure-Java reads (`SystemProperties.get*`, `Build` fields) are covered by the hook.

From AlwaysStrong's strategy we adopted prop unification (`ro.product.*`, `ro.build.product`, `ro.build.description`, `ro.build.tags/type`, OEM leak scrub), security-patch sync across all `ro.*.security_patch` variants + attestation patch levels, the target-package list, and the `Refresh + clear Play Store` action (re-sync PIF, re-apply, force-stop DroidGuard, clear Play Store — no reboot needed).

## 🔒 Offline keybox self-check (Google public endpoints)

The app/CLI can verify a keybox against Google's own published data instead of blindly trusting it:

- `https://android.googleapis.com/attestation/root` — Google attestation root certificates (RSA + the new EC "Key Attestation CA1").
- `https://android.googleapis.com/attestation/status` — Google's revocation list (~1700 revoked keybox serials with `KEY_COMPROMISE` / `SOFTWARE_FLAW` reasons).

`KeyboxVerifier` (pure JVM, in `patcher/integrity/`) rebuilds the certificate chain, verifies every signature, checks it terminates at a Google root, looks up the leaf serial in the revocation list, and parses the attestation extension (`attestationVersion`, security level, verified boot state, device lock, os/vendor/boot patch levels, attested device IDs, attested application id). The app exposes it as **Verify keybox (Google lists)** and reports `valid` / `revoked` / `invalid` with per-field details.

`Toolbox-data/Pif-props.json` ships a current CANARY Pixel fingerprint, auto-updated daily by CI (`Toolbox-Update/scripts/update_pif.sh`), and is bundled in the APK as a fallback when data sync is unavailable.

## 🏗️ Modules in detail

### `app/` — Farewell Toolbox APK

- namespace `io.farewell.toolbox`; `minSdk 29`, `targetSdk 36`, `compileSdk 37`; Compose (BOM, material3), coroutines, lifecycle viewmodel-compose.
- `core/`:
  - `PatchRepository` — device detection + jar patching (`JarPatcher` from `:patcher`) + flashable zip assembly.
  - `PlayIntegritySetup` — builds/writes the `sys_keystore_cfg` blob and per-flag toggles.
  - `IntegrityCheck` — keybox verification against Google lists (reuses `:patcher`'s `KeyboxVerifier`/`IntegrityData`).
  - `DataSync` — downloads `Toolbox-data/*` (Pif-props, device-model, app-props, blacklist, quotes, date) from the configured base URL; the APK bundles a fallback copy.
  - `PifAutoFetch` — scrapes `developer.android.com/about/versions` to build a fresh PIF fingerprint when sync data is stale.
  - `AutoRefresh` — `JobScheduler` job (every 6 h) that re-applies PIF config / refreshes the native helper.
  - `NativeService` / `NativeBootReceiver` / `RootShell` — stream `farewelld` + `props.conf` to `/data/local/tmp/pfix` over `su -c` and run it (`--once`) at boot, manually, or after a refresh.
  - `DeviceDetector`, `Codec` — profile detection and `k2:` blob encoding.
- `ui/` — Compose screens: `HomeScreen`, `PatchScreen`, `SettingsScreen`, `MainViewModel`.
- Resources merge from `Toolbox-languages/` (`res.srcDirs` in `app/build.gradle.kts`).

### `patcher/` — the patch engine (pure JVM)

- `DexPatchEngine` — walks the dex with dexlib2 and applies the active rule set; rules are `ClassRule`/`MethodRule` implementations filtered by `enabledFor(kind)` and `apiRange`.
- `rules/`:
  - `FrameworkRules`, `ServiceRules` — keystore/keystore2 entry points, `Settings$NameValueCache`, feature/FLAG_SECURE hooks, WMS capture.
  - `PackageManagerRules` — installer spoof (`getInstallerPackageName` / `ComputerEngine`), app-list hiding (`AppsFilter*` / `filterAppAccess*`).
  - `CorePatchRules` — signature-verification weakening (`SigningDetails.checkCapability`, `MessageDigest.isEqual`, minimum signature scheme, `StrictJarVerifier.verifyDigest`; rule names `corepatch.*`).
  - `KeyboxRules`, `PropRules`, `LegacyRules` — keybox entry points, prop override plumbing, Android-10 legacy paths.
  - `PatchRule.kt` — rule interfaces + dex register/instruction utilities (`parameterRegister`, `insertBeforeReturns`, `replaceReturnsObject/Int`, …).
- `JarPatcher` / `DexSupport` — load/patch/write jars, 4-byte aligned writer, DEX 040 normalization (A17).
- `FlashZipBuilder` — builds the patch/stock zips with a dynamic `manifest.txt` consumed by the shell `update-binary`.
- `PropPatcher` — applies a JSON prop map to `build.prop` content.
- `PlatformProfile` — the ROM profile table.
- `integrity/` — `IntegrityData` (downloads Google roots + status list), `DerReader`, `KeyboxVerifier` (+ attestation parsing).
- CLI entry: `io.farewell.patcher.PatchCliKt`.

### `hook/` — the injected hook

- `java/android/security/keystore2/` — `KeyboxEngine` (software keypair + attestation chain), `AttestationBuilder`, `Der`, `BuildSpoofer`, `PropSpoofer`, `SettingsSpoofer`, `AppFilterSpoofer`, `KeyboxSpoofer`, `SecureFlagSpoofer`, `SystemFeatureSpoofer`, `HookConfig` (`k2:` transport), `HookState`, `HookProbe`, `HookCodec`, `HookLog`.
- The public facade is generated from `hook/entry-template/KeyStoreHooks.template.java` by `generateHookIdentity`; compiled against `android.jar` (Java 11), dexed by R8/D8 (min API 29) into `hook.dex`.

### `native/` — `farewelld` + ROM payload

- **Stock mode (default)** — the app streams the binary to `/data/local/tmp/pfix` via root and runs `--once` at `BOOT_COMPLETED`, on a manual button, and after a PIF refresh. No ROM edit needed.
- **ROM mode (optional)** — bake `native/rom/system/*` into the ROM: daemon at `/system/bin`, `etc/init/farewell.rc`, SELinux fragment (`etc/selinux/farewell.cil.txt`, applied with `native/verify-cil.ps1 -Rom <rom> -Apply`), privapp permissions, `/system/etc/farewell/props.conf`. Runs `on late-init`, before zygote.
- Both modes write the same way (property areas directly), update **existing** properties only, skip long/empty values, report via `sys.pfix_status`.
- Build (NDK): `pwsh -File native/build.ps1` (arm64-v8a; `-NoStrip`, `-Shared` variants). Copy the output to `app/src/main/assets/farewelld` after rebuilding.
- `native/tests/installer-test.sh` runs the installer end-to-end in a disposable Linux/WSL env (fake mount/unzip; checks patch, backup, restore.sh, stock restore, delete list, abort path).

### Flashable zips (built by the app)

Both zips use a **shell `update-binary`** (TWRP-shell installer pattern, credits osm0sis@xda) and a dynamic manifest:

```
# backup=yes|no
# stamp=<stamp>
# profile=<profile>
# delete=<comma separated paths>        (restore zip only)
system_root/system/framework/framework.jar 0644
product/build.prop 0644
...
```

- `Farewell-Patch-<profile>-<stamp>.zip` — patched jars, patched `build.prop`s (`system`, `product`, `vendor`, `vendor/odm/etc`), `etc/farewell/props.conf`, privapp permissions, patch marker. Mounts **only the partitions used by the manifest** (surya MIUI12: system/product/vendor; MIUI13/14 add system_ext; no `mi_ext`), TWRP mounts first with the e2fsck `unshare_blocks` helper as fallback. **Flash-time backup**: every replaced file is copied to `/data/media/0/Farewell/backup-<stamp>/` (mirrored paths) with a generated `restore.sh`; `0:0 0644` per file; wipes package cache/dalvik and boot artifacts; **aborts without touching anything** if an entry cannot be written.
- `Farewell-Stock-<profile>-<stamp>.zip` — same paths with the original contents; `# delete=` removes the privapp XML and `props.conf`, marker reset to `stock`; no backup created.

## 📦 Data layer

| File | Purpose |
|---|---|
| `Toolbox-data/Pif-props.json` | Current CANARY Pixel fingerprint + security patch (auto-updated daily by CI) |
| `Toolbox-data/device-model.json` | Device model spoof data |
| `Toolbox-data/app-props.json` | Per-app property override presets |
| `Toolbox-data/Blacklist.txt` | Packages excluded from spoofing |
| `Toolbox-data/Keybox.xml` | Default keybox payload |
| `Toolbox-data/date-update.txt` | Last data-update stamp |
| `Toolbox-data/quotes.txt` | Rotating quotes shown in the app |

CI (`.github/workflows/`): `update_pif` runs daily (`cron 0 0 * * *`) and auto-commits fresh PIF props; `update_keybox` and `notification` support the keybox/release flow.

## 🛠️ Building

Requirements: **JDK 17+**, Android SDK (platform 37, build-tools 36/37), Android NDK (for `farewelld`).

```bash
./gradlew :app:assembleDebug                        # build the APK (also builds hook.dex)
./gradlew -PrenewHookIdentity :app:assembleDebug    # rotate the per-build hook identity
./gradlew -PhookObfuscate=false :app:assembleDebug  # plain-D8 hook dex for debugging
./gradlew :patcher:test                             # unit tests (pure JVM, no device needed)
pwsh -File native/build.ps1                         # build farewelld (arm64-v8a)
```

### Tests

`./gradlew :patcher:test` runs the suite in `patcher/src/test/` — no device or ROM required:

| Class | Covers |
|---|---|
| `PropPatcherTest` | replace-in-place vs append-at-EOF, `ro.boot.*` blocking, CRLF preservation, and the per-SKU import trap that made the identity spoof a no-op |
| `PlatformProfileTest` | the per-SKU property targets, per-partition split, surya-only scope, and the "never patch `SettingsProvider.apk` / `miui-*.jar` / `miuix.jar`" invariant |
| `SpoofRulesTest` | the per-app config shape, asserted against the exact paths `HookConfig` walks |
| `JarPatcherTest` | the dex engine against synthetic jars (`DexFixture`): a rule fires on its exact signature, the patched body is asserted instruction by instruction, `apiRange` gating keeps a rule dormant below its API level, the hook dex is injected as the next `classesN.dex`, non-dex entries survive, and a re-patch is detected |
| `DerReaderTest` | the DER decoder: short/long lengths, multi-byte tag numbers (704–719), integers/enumerated/booleans, nested readers, OID decoding including multi-octet first subidentifiers, and clean failures on truncated or overrunning input |
| `AttestationParserTest` | a complete synthetic KeyDescription built with `DerFixture`, asserting every verdict input: header fields, RootOfTrust (locked/unlocked, boot state, boot hash, absent hash), patch levels, device identity, `attestationApplicationId`, and that `softwareEnforced` is ignored |
| `KeyboxVerifierTest` | certificate parsing, chain anchoring and status-list lookup against **real Google attestation roots** (`patcher/src/test/resources/*.pem`): anchors to the right root, rejects an unanchored keybox, and looks a serial up by hex (the documented format) while ignoring decimal keys |
| `PropSpoofTest` | the per-partition prop maps: every partition gets its own prefix, only `/system/build.prop` carries the unprefixed keys, vendor adds the bootimage fingerprint and `ro.adb.secure`, unusable identities produce nothing, and no map ever contains a blank value (which would erase a stock property) |
| `FlashZipBuilderTest` | the flashable zip: template and payload entries, binary payloads preserved byte for byte, LF-only scripts untouched, and the entry-name contract the shell installer depends on (`system_root/...` for system, native paths for the other partitions, first path segment = mount point) |
| `IntegrityDataTest` | the Google cache and parsing: the 24-hour max-age, roots from a JSON array, statuses from `entries` with optional `reason`/`comment`/`expires`, entries without a `status` skipped, and malformed JSON yielding empty results instead of throwing |

Fixtures are built in-process with `DexFixture` and `DerFixture`, so the dex and DER tests depend on no ROM extraction. A fixture states a signature or a structure exactly — that is the contract being matched on. The keybox fixtures are two genuine Google attestation roots, fetched from `https://android.googleapis.com/attestation/root`.

The app module has no JVM unit tests: AGP needs `androidJdkImage` for `compileDebugJavaWithJavac` even with no Java sources, and that transform fails on JDK 26. Any pure logic worth testing belongs in `:patcher` — which is why `SpoofRules` lives there and only file IO stays in the app.

### Patcher CLI

```bash
./gradlew :patcher:run --args="--input stock.jar --output patched.jar --hook build/hook/hook.dex --kind FRAMEWORK --profile surya-miui13"
./gradlew :patcher:run --args="--input stock.jar --scan"                    # ROM capability scan
./gradlew :patcher:run --args="--input stock.jar --grep AppsFilter"         # grep classes
./gradlew :patcher:run --args="--fetch-integrity"                           # download Google roots + status list
./gradlew :patcher:run --args="--verify-keybox /path/keybox.xml"            # offline keybox verification
./gradlew :patcher:run --args="--patch-prop build.prop --props props.json"  # apply a prop map to build.prop
./gradlew :patcher:run --args="--dump-prop-maps out/ --props Pif-props.json" # per-partition prop maps for a PIF
```

### Verifying a ROM without a device

The three `tools/rom-audit/` scripts answer, in order: what the ROM contains, who wins each property key, and whether the spoof actually survives. `verify_props.py` drives the real patcher and the real maps against a copy of the ROM tree:

```bash
./gradlew :patcher:installDist
patcher/build/install/patcher/bin/patcher --dump-prop-maps /tmp/maps --props Toolbox-data/Pif-props.json
python tools/rom-audit/verify_props.py --rom /path/to/MIUI13/ROM --maps /tmp/maps \
  --patcher patcher/build/install/patcher/bin/patcher.bat --java-home "$JAVA_HOME"
```

It exits non-zero if any identity key still resolves to a stock value. All three surya ROMs currently pass: 7 property files patched on MIUI 12 (no `system_ext`) and 8 on MIUI 13/14, with all 15 identity keys resolving to the spoofed value.

On-device verification:

```bash
getprop sys.pfix_status                  # helper/daemon result
getprop ro.boot.verifiedbootstate        # green after the helper runs
getprop ro.boot.flash.locked             # 1
logcat -s farewelld                      # daemon/helper log lines
```

## 📚 Documentation

- [Patch Guide 2.0.6.0](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0.md)
- [CorePatch (signature checks)](Toolbox-docs/V2.0.3+/CorePatch.md) · [Disable FLAG_SECURE](Toolbox-docs/V2.0.3+/Disable_Secure_Flag.md)
- [Native daemon & installer](native/rom/README.md)
- [ROM Audit: Surya (MIUI 12/13/14)](Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md) — what the stock ROMs actually contain, which rules can fire, and who wins each property key
- ROM porting tools in `tools/rom-audit/`: `rom_audit.py` (what a ROM contains vs. what the rules need), `prop_resolve.py` (which property file wins each key, `import` chain included) and `verify_props.py` (patches a real ROM tree with the real per-partition maps and asserts the spoofed identity wins)
- `tools/config-inspect/inspect_config.py` — decode a live `sys_keystore_cfg` blob and query it exactly the way `HookConfig` does, to confirm the per-app rules reached the framework
- Integrity data comes from `https://android.googleapis.com/attestation/{root,status}`, cached under `integrity-data/` and refreshed after 24 hours (the `max-age` Google advertises). `/attestation/root` is a JSON array of PEMs; `/attestation/status` is `{"entries": {"<lowercase hex serial>": {...}}}`
- Reference smali for every patched call-site: `Toolbox-docs/Template/Template_V2060/{framework,service}/`

**Advanced features** (per-app setting spoof) are implemented **client-side** on `Settings$NameValueCache.getStringForUser`, which exists on all three surya ROMs and covers both app and `system_server` reads. There is deliberately **no** server-side `SettingsProvider` hook: the provider lives in `/system/priv-app/SettingsProvider/SettingsProvider.apk` (not `services.jar`) and exposes neither `getStringForUser` nor `getString` on surya MIUI 12/13/14. Installing only the APK or loading only the DEX is not enough — the framework patch carries the hook. See [ROM Audit: Surya](Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md).

The **Rules** tab is where those per-app rules are authored. It writes all four sections the hook reads — `installer`, `settings`, `remove` and `features` — persists them to `filesDir/spoof-rules.json`, and hands them to the hook the next time **Apply Play Integrity setup** runs. The exact JSON shape is documented in the [patch guide](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0.md).

## ✨ Features

- ✅ Play Integrity fix (up to STRONG with a verified keybox).
- 🧩 Pixel & properties spoofing (Java + native layer).
- 🙈 Hide installed app list (caller-aware isolation).
- 🛠️ Hide Developer Options & ADB status.
- 🔓 Disable FLAG_SECURE (screenshots & screen recording in restricted apps).
- ⚙️ Per-app spoof rules: Settings value overrides, hidden keys, forced system features and installer-source spoof (framework patch required).
- 🧾 Keybox verification against Google's own root/revocation lists.
- 🧯 Flash-time backup + one-flash stock restore zip.

## 🗺️ Roadmap

- [ ] ⚡ **Automated Patcher Tool 2.0.6+**
- [x] ⚙️ **ROM validation for Fake & Filter System Settings** — done for surya MIUI 12/13/14. The documented server-side `filterSettingValue` / `shouldRemoveSetting` patches were audited and **removed**: the class they targeted is not reachable and has no such methods. Per-app Settings spoofing is client-side only. See [ROM Audit: Surya](Toolbox-docs/V2.0.3+/ROM_Audit_Surya.md) and re-run `tools/rom-audit/rom_audit.py` for any new ROM.
- [x] 📦 **Spoof Installer Source Package** — `PackageManagerInstallerRule` patches `PackageManagerService.getInstallerPackageName` on all three ROMs, and the app now writes the `installer` section of `sys_keystore_cfg` from the **Rules** tab.
- [x] 🧩 **Per-app spoofing manager** — the **Rules** tab writes all four sections the hook reads: `installer`, `settings` (per app / table / key), `remove` (hidden keys) and `features` (forced `hasSystemFeature`). Rules persist to `filesDir/spoof-rules.json` and reach the hook on the next **Apply Play Integrity setup**.

## 🌍 Localization & Translations

Help us translate Farewell-Toolbox into more languages! 🌐

- Translation files live here: **`Toolbox-languages/`** (currently: en, es, hi-rIN, in, ja, ru, th, vi, zh-rCN).
- Base file to translate: `values/strings.xml`.

## 🧠 Context for contributors & AI assistants

If you are an LLM or a new developer touching this repo, these are the load-bearing invariants — break one and the system degrades silently:

1. **Hook identity is generated, not static.** Never hardcode `KeyStoreCompat*` class/method names. Source of truth: `hook/entry-template/KeyStoreHooks.template.java` + `hookMethodNames` in the root `build.gradle.kts`. Adding an entry point: add the constant to `hookMethodNames`, add the `__M_<NAME>__` placeholder to the template, use the generated `HookIdentity.M_<NAME>` in patcher rules — never string literals in `patcher/`.
2. **`:patcher` is pure JVM** (JDK 17, no Android SDK imports) and shared by the app and the CLI. Dependencies today: `smali-dexlib2`, `org.json` — keep the list minimal.
3. **`hook/` compiles against `android.jar` only** (Java 11, `compileOnly`) and runs on the boot classpath of Android 10+. No lambdas/streams/AndroidX there; R8 renames everything except the generated facade.
4. **Rules are signature-gated.** Every rule must (a) set a correct `apiRange`, (b) report a graceful outcome instead of throwing when a signature is missing, (c) use `HookIdentity.M_*` constants. Porting to a new ROM: run `--scan` on its stock jars, then update `PlatformProfile`.
5. **Never patch `SettingsProvider.apk`**, `miui-framework.jar`, `miui-services.jar`, `miuix.jar` — audited not to contain the patched classes; touching them widens the blast radius on MIUI.
6. **The two config blobs are the app↔hook state contract**: `sys_keystore_cfg` (PIF/flags) and `sys_keybox_cfg` (keybox), both `k2:` base64+XOR via `Codec`/`HookCodec`. Any field added on the app side must be consumed on the hook side too.
7. **`farewelld` only updates existing properties** and skips long/empty values; the property-area layout is pinned to bionic (see `native/rom/README.md`). Do not make it create new properties.
8. **The installer must stay abort-safe**: if any manifest entry cannot be written, abort before changing anything. The backup/restore contract (`/data/media/0/Farewell/backup-<stamp>/` + generated `restore.sh`) is what users rely on for recovery — validate changes with `native/tests/installer-test.sh`.
9. **Scope is surya, and the docs say so.** Supported ROMs are MIUI 12 (Android 10), MIUI 13 and MIUI 14 (Android 12) on POCO X3 only. Do not add rules or profiles for classes that only exist on Android 13+ — audit the stock images first with `tools/rom-audit/rom_audit.py`, and check who wins each property key with `tools/rom-audit/prop_resolve.py`. Docs are **English only**; the smali templates in `Toolbox-docs/Template/Template_V2060/` are the canonical call-site reference — keep them in sync when rules change, and delete a template when its rule goes.
10. **Version strings** (`v2.0.6.0`, guide filenames) appear across docs and releases; bump them together, and keep `DATA_BASE_URL` in `app/build.gradle.kts` pointed at the published `Toolbox-data` raw URL.

## 🙏 Credits

- **AOSP Framework**
- **Trickystore**
- **osm0sis@xda-developers** (TWRP shell installer pattern used by the zips)
- **AlwaysStrong / PlayIntegrityFork** (prop-unification strategy, rootless adaptation)
