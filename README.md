# Farewell Toolbox

Android app that patches `framework.jar` / `services.jar` with the Farewell hook on-device and produces a flashable recovery zip.

## 🧱 Project layout

- `app/` — Farewell Toolbox APK (Jetpack Compose): patch status ON/OFF, build & export the flashable zip, stock backup zip, data sync.
- `patcher/` — dexlib2 patch engine: signature-based rules for framework/services, 4-byte aligned jar writer, flashable zip builder, CLI.
- `hook/` — `android.security.farewell.FarewellHook` library, compiled to `hook.dex` with D8 and injected into the patched jars.
- `Toolbox-data/`, `Toolbox-languages/`, `Toolbox-docs/` — app data, translations, framework patch documentation.

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
