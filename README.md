# Farewell Toolbox

Android app that patches `framework.jar` / `services.jar` with the Farewell hook on-device and produces a flashable recovery zip.

## 🧱 Project layout

- `app/` — Farewell Toolbox APK (Jetpack Compose): patch status ON/OFF, build & export the flashable zip, stock backup zip, data sync.
- `patcher/` — dexlib2 patch engine: signature-based rules for framework/services, 4-byte aligned jar writer, flashable zip builder, CLI.
- `hook/` — `android.security.keystore2.KeyStoreHooks` library, compiled to `hook.dex` with D8 and injected into the patched jars.
- `Toolbox-data/`, `Toolbox-languages/`, `Toolbox-docs/` — app data, translations, framework patch documentation.

## 📱 Supported ROM profiles

| Profile id | Device | ROM | Android |
|---|---|---|---|
| `surya-miui12` | POCO X3 (surya) | MIUI 12 | 10 (SDK 29) |
| `surya-miui13` | POCO X3 (surya) | MIUI 13 | 12 (SDK 31) |
| `surya-miui14` | POCO X3 (surya) | MIUI 14 | 12 (SDK 31) |
| `modern-a13plus` | any | AOSP/MIUI 13+ | 13+ |

The app detects device codename, MIUI version and Android API, then picks the profile automatically. Rules are gated by `apiRange`, so legacy (keystore v1, `AppsFilter`, `DevicePolicyCacheImpl.getScreenCaptureDisabled`) and modern (keystore2, `AppsFilterBase`, WMS capture) patch sets stay separate and maintainable.

### ROM audit notes (surya MIUI 12 / 13 / 14)

- Patch targets live in `/system/framework/framework.jar` and `/system/framework/services.jar`. `miui-framework.jar` (boot classpath), `miui-services.jar` and `miuix.jar` contain no copies of the patched classes, so they stay untouched.
- `SettingsProvider.apk` is never modified. Settings spoof/removal/probe hooks `Settings$NameValueCache.getStringForUser` client-side, which exists in all three ROMs and covers both app and system_server reads.
- Installer spoof uses `PackageManagerService.getInstallerPackageName(String)` on MIUI 12/13/14 and `ComputerEngine` on Android 13+.
- App-list hiding uses `AppsFilter` (MIUI 13/14) and `PackageManagerService.filterAppAccess*` (MIUI 12).
- The flashable zip clears the prebuilt boot artifacts (`boot-framework.*` in `framework/arm[64]` and `framework/oat/arm[64]`, `services.*`) plus dalvik caches, so ART re-verifies and recompiles the patched jars on first boot.

### Play Integrity / Play Store certification

- `Apply Play Integrity setup` in the app writes a single `sys_keystore_cfg` JSON into `Settings.Global` (via root):
  - `build`: PIF Build fields applied to `com.google.android.gms`, `com.android.vending`, `com.google.android.gsf` (from the synced `Pif-props.json`).
  - `props`: `SystemProperties.get` overrides (`ro.build.fingerprint`, `ro.product.*`, `ro.build.version.security_patch`).
  - `flags`: hidden developer status, hidden app list, FLAG_SECURE and keybox spoof.
- Keybox XML can be imported from the device and is stored in `sys_keybox_cfg`. `KeyboxEngine` now also implements the software keypair path: when an app requests hardware attestation (`KeyGenParameterSpec` with an attestation challenge), the hook generates a P-256 keypair and a fresh X.509 leaf certificate containing a KeyDescription attestation extension (verified boot state, locked bootloader, OS/patch levels, attestation application id) signed by the keybox private key, then returns `[newLeaf, keybox chain...]` for `engineGetCertificate` / `engineGetCertificateChain` through an alias cache.
- `Build` fields are also unfinalized and spoofed in `system_server` (`initSystemServer`) so GMS/Play Store checks in system processes see the same identity.

### Rootless adaptation (no Zygisk)

AlwaysStrong / TEESimulator + PlayIntegrityFork require a Zygisk implementation because they inject into zygote at runtime. This project does not: the hook lives inside the boot-classpath jars (`framework.jar` / `services.jar`), so it already runs inside every app and system process. No zygote injection, no companion process, no Zygisk module.

What was adopted from AlwaysStrong's strategy:

- **Prop unification**: `ro.product.brand/name/device/model/manufacturer`, `ro.product.system.model`, `ro.build.product`, `ro.build.description` (reconstructed from the fingerprint), `ro.build.tags/type`, `ro.system.build.tags/type`, and the OEM leak scrub (`ro.product.{odm,vendor,product,system_ext}.{model,brand,manufacturer,device,name}` returned empty) for every target package.
- **Security-patch sync**: `ro.build.version.security_patch`, `ro.vendor.build.security_patch`, `ro.system.build.version.security_patch` and the attestation `osPatchLevel`/`vendorPatchLevel`/`bootPatchLevel` all use the PIF patch (never moving the device patch backwards).
- **Target list**: Play Store, GMS/GSF and the common attestation checkers (`vvb2060.keyattestation`, `nativecheck`, `riskdetector`, `luna.safe.luna`, …).
- **Refresh action**: `Refresh + clear Play Store` re-syncs the PIF data, re-applies the config, force-stops DroidGuard and clears the Play Store so a new fingerprint/keybox takes effect without rebooting.

Known limitation of the rootless model: native property reads (`__system_property_get` used by DroidGuard's native code) cannot be intercepted from framework Java; Zygisk/PLT hooks would be required for that layer. Java-level reads (`SystemProperties.get/getInt/getLong/getBoolean`, `Build` fields) are covered.

### Stealth hardening

- **Per-build randomized hook identity.** `:generateHookIdentity` produces `KeyStoreCompat<hex>` plus random method names for all 21 hook entry points; both the generated Java facade and the patcher constants come from that single source. `Class.forName` scanning or hardcoded string matching for a known hook class fails. Use `./gradlew -PrenewHookIdentity :app:assembleDebug` (or delete `build/hook-identity.txt`) to rotate the identity for a release.
- **R8-obfuscated hook dex.** The hook is compiled with R8 (`-repackageclasses 'o'`) while the entry class + members are kept, so helper class names (`HookState`, `KeyboxEngine`, `AttestationBuilder`, …) no longer exist as strings in the flashed artifacts. `./gradlew -PhookObfuscate=false` falls back to plain D8 for debugging.
- **Obfuscated configuration transport.** `sys_keystore_cfg` and `sys_keybox_cfg` values are stored as `k2:` base64+XOR blobs, so `settings list global` never shows readable PIF/keybox payloads.
- **Authentic attestation identity.** The generated leaf contains `ATTESTATION_ID_BRAND/DEVICE/PRODUCT/MANUFACTURER/MODEL`, a real APK signing-certificate digest in `attestationApplicationId`, a derived `verifiedBootHash` (never all-zero), `RootOfTrust` encoded in the official order (`verifiedBootKey`, `deviceLocked`, `verifiedBootState`, `verifiedBootHash`) and `attestationVersion` 3 on Android 10–11 / 4 on Android 12+.

### Offline integrity self-check (Google public endpoints)

The app/CLI can verify a keybox against Google's own published data instead of blindly trusting it:

- `https://android.googleapis.com/attestation/root` — Google attestation root certificates (RSA + the new EC "Key Attestation CA1").
- `https://android.googleapis.com/attestation/status` — Google's revocation list (~1700 revoked keybox serials with `KEY_COMPROMISE` / `SOFTWARE_FLAW` reasons).

`KeyboxVerifier` (pure JVM, in `patcher`) rebuilds the certificate chain, verifies every signature, checks it terminates at a Google root, looks up the leaf serial in the revocation list, and parses the attestation extension (`attestationVersion`, security level, verified boot state, device lock, os/vendor/boot patch levels, attested device IDs, attested application id). The Android app exposes it as **Verify keybox (Google lists)** and reports `valid` / `revoked` / `invalid` with per-field details.

CLI:

```bash
./gradlew :patcher:run --args="--fetch-integrity"                     # download roots + status list
./gradlew :patcher:run --args="--verify-keybox /path/keybox.xml"     # verify a keybox offline afterwards
```

`Toolbox-data/Pif-props.json` now ships a current CANARY Pixel fingerprint (Pixel 8 Pro, security patch 2026-06-05) and the same file is bundled in the APK as a fallback when data sync is unavailable.

### What Google's own documentation says (and how we use it)

- **Android 13+**: `MEETS_STRONG_INTEGRITY` = `MEETS_DEVICE_INTEGRITY` **plus** OS and vendor security patches from the last 12 months. Our attester stamps `osPatchLevel`/`vendorPatchLevel`/`bootPatchLevel` from the PIF patch, and the app warns when a PIF patch is older than 12 months or older than the device patch.
- **Android 12 and lower**: `MEETS_STRONG_INTEGRITY` only needs **hardware-backed proof of boot integrity** — no patch recency requirement. That is exactly the MIUI 12/13/14 situation, so a valid keybox + software-generated TEE-level attestation is sufficient.
- `MEETS_BASIC_INTEGRITY` on Android 13+ only requires that the attestation root of trust is provided by Google — our chain terminates at the Google root bundle.
- The app's **STRONG readiness check** combines these rules with the keybox verification (chain, revocation, boot state, lock state) and reports what still blocks a STRONG verdict.

- All patched targets exist and all rules apply (MIUI12: framework 14 / services 6; MIUI13/14: framework 15 / services 6). Two expected absences on MIUI 12 (Android 10): `ApkSignatureVerifier.getMinimumSignatureSchemeVersionForTargetSdk` and `AppsFilter.shouldFilterApplication` (the app-filter path there is `PackageManagerService.filterAppAccessLPr`).
- Target jars use DEX 039 on all three ROMs; the A17-only 040 normalization never triggers.
- The update script deletes exactly the boot artifacts that exist (`boot-framework.*` under `framework/arm[64]`, `framework/oat/arm64/services.*`); `miui-services`/`boot-miui-framework` artifacts are left alone because those jars are not patched.
- `SettingsProvider.apk` is untouched on all three ROMs.

## 🛠️ Building

Requirements: JDK 17+ and Android SDK (platform 37, build-tools 36/37).

```bash
./gradlew :app:assembleDebug        # build the APK
./gradlew :patcher:run --args="--input stock.jar --output patched.jar --hook build/hook/hook.dex --kind FRAMEWORK"   # patch a jar/APK
./gradlew :patcher:run --args="--input stock.jar --scan"   # ROM capability scan
```

## ✨ Features

- ✅ Play Integrity fix.
- 🧩 Pixel & properties spoofing.
- ⚙️ Per-app spoofing manager.
- 🙈 Hide installed app list (Caller-aware isolation).
- 🛠️ Hide Developer Options & ADB status.
- 🔓 Disable FLAG_SECURE (Take screenshots & screen record restricted apps).
- ⚙️ Spoof setting value per app

---

## 🚀 How to use

## 📦 Latest release: v2.0.6.0

> ⚡ **Auto Patcher**: *Stay tuned!*  
> See Patch Guide v2.0.6.0 in [English](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0.md) or [Tiếng Việt](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0_VI.md).

Follow the detailed usage guide here:  
👉 [Farewell-Toolbox Guide](Toolbox-docs)

### Advanced features: framework patch required

For builds with the Advanced patch check, use a matching Toolbox APK and framework
DEX with probe support. Advanced unlocks only after a live check reaches **both
SettingsProvider hooks** for Global, Secure and System, including missing keys.
Installing only the APK, loading only the DEX, or enabling root fallback is not
enough. Apply the ROM call-site patches and reboot.

See the setup and troubleshooting steps in the [English patch guide](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0.md#advanced-features-patch-check)
or [hướng dẫn tiếng Việt](Toolbox-docs/V2.0.3+/Patch_Guide_2.0.6.0_VI.md#kiểm-tra-patch-cho-tính-năng-nâng-cao).
This check does not certify AppsFilter/installer-source patches or replace testing
on the target ROM; a framework version string alone is not proof of hook coverage.

---

## 📋 Todo List / Roadmap

- [ ] ⚡ **Automated Patcher Tool 2.0.6+**
- [ ] ⚙️ **ROM validation for Fake & Filter System Settings**: Verify the documented `filterSettingValue` / `shouldRemoveSetting` patches and Advanced capability check on each target ROM.
- [ ] 📦 **Spoof Installer Source Package**: Spoof package installer origin per-app (`filterInstallerPackageName`, e.g. masquerade as Google Play Store `com.android.vending`).

---

## 🌍 Localization & Translations

Help us translate Farewell-Toolbox into your language! 🌐

- Translation files live here: **`Toolbox-languages/`**
- Base file to translate: `values/strings.xml`

---

## 🙏 Credits

- **AOSP Framework**
- **Trickystore**
