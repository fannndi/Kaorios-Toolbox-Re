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
