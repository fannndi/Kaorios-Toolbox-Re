# Kaorios Toolbox

Starting with **version 2.0.4+**, Kousei will no longer be involved in the development or decision-making of Kaorios Toolbox.

📢 [**Original announcement**](https://t.me/KariosToolboxDiscussion/124662)

### Previous Versions

For **version 2.0.4.0 and below**, see:  
[Wuang26/Kaorios-Toolbox](https://github.com/Wuang26/Kaorios-Toolbox)

## ✨ Features

- ✅ Play Integrity fix.
- 🧩 Pixel & properties spoofing.
- ⚙️ Per-app spoofing manager.
- 🙈 Hide installed app list (Caller-aware isolation).
- 🛠️ Hide Developer Options & ADB status.
- 🔓 Disable FLAG_SECURE (Take screenshots & screen record restricted apps).
- ☁️ Google Photos unlimited backup.
- 🧰 Payload dumper integration.
- 🎮 Unlock high-FPS modes in games.
- 📊 Overlay display for FPS and CPU.
- ⚙️ Spoof setting value per app

---
## 🖼️ Screenshots

<p align="center">
  <a href="https://raw.githubusercontent.com/hzzmonetvn/Kaorios-Toolbox/refs/heads/main/Toolbox-screenshots/Home.png">
    <img src="https://raw.githubusercontent.com/hzzmonetvn/Kaorios-Toolbox/refs/heads/main/Toolbox-screenshots/Home.png" alt="Home Screen" width="45%" style="max-width:320px; border-radius:8px;"/>
  </a>
  <a href="https://raw.githubusercontent.com/hzzmonetvn/Kaorios-Toolbox/refs/heads/main/Toolbox-screenshots/Tools.png">
    <img src="https://raw.githubusercontent.com/hzzmonetvn/Kaorios-Toolbox/refs/heads/main/Toolbox-screenshots/Tools.png" alt="Tools Screen" width="45%" style="max-width:320px; border-radius:8px;"/>
  </a>
</p>

<p align="center">
  <a href="https://github.com/hzzmonetvn/Kaorios-Toolbox/tree/main/Toolbox-screenshots">🔍 See more screenshots →</a>
</p>

---

## 🚀 How to use

## 📦 Latest release: v2.0.6.0

> ⚡ **Auto Patcher**: *Stay tuned!.*   
> See Patch Guide v2.0.6.0 in [English](https://github.com/hzzmonetvn/Kaorios-Toolbox/blob/main/Toolbox-docs/V2.0.3%2B/Patch_Guide_2.0.6.0.md) or [Tiếng Việt](https://github.com/hzzmonetvn/Kaorios-Toolbox/blob/main/Toolbox-docs/V2.0.3%2B/Patch_Guide_2.0.6.0_VI.md).  

Follow the detailed usage guide here:  
👉 [Kaorios-Toolbox Guide](https://github.com/hzzmonetvn/Kaorios-Toolbox/tree/main/Toolbox-docs)

Releases: [Kaorios-Toolbox Releases](https://github.com/hzzmonetvn/Kaorios-Toolbox/releases)
Old release: [Kaorios-Toolbox old_release](https://github.com/wuang26/Kaorios-Toolbox/releases)

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

Help us translate Kaorios-Toolbox into your language! 🌐

- Translation files live here: **[Toolbox-languages](https://github.com/hzzmonetvn/Kaorios-Toolbox/tree/main/Toolbox-languages)**
- Base file to translate: `values/strings.xml`

---
## 👉 Join KaoriosToolbox
- **[KaoriosToolbox-Chanel](https://t.me/KaoriosToolbox)**.
- **[KaoriosToolbox-Discussion](https://t.me/KariosToolboxDiscussion)**.

---

## 🙏 Credits

- **Payload Dumper** — [rcmiku](https://github.com/rcmiku/Payload-Dumper-Compose).
- **AOSP Framework**
- **Trickystore**
