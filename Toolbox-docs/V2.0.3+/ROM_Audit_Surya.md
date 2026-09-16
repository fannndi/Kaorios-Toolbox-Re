# ROM Audit: Surya (POCO X3 / M2007J20CG)

**English** | [Tiếng Việt](ROM_Audit_Surya_VI.md)

This page records what the **stock** surya MIUI ROMs actually contain, and what that
means for the patcher. Every claim below was verified against the extracted stock
images, not against upstream AOSP.

> [!IMPORTANT]
> The Farewell patch is written to be device-agnostic, but the *property* layer is not:
> which file wins a key is decided by each ROM's `ro.product.property_source_order`.
> The findings below are what make the spoof actually take effect on surya.

---

## 1. Scope and method

| ROM | Android | API | Build |
|---|---|---|---|
| MIUI 12 | 10 | 29 | `V12.0.9.0.QJGMIXM` |
| MIUI 13 | 12 | 31 | `V13.0.5.0.SJGIDXM` |
| MIUI 14 | 12 | 31 | `V14.0.2.0.SJGIDXM` |

Extraction root: `crb_340/Projects/MIUI{12,13,14}/ROM`.

Method:

- `dexdump` from Android SDK **build-tools 36.0.0** dumps `framework.jar`,
  `services.jar` and `SettingsProvider.apk` to a class → method-signature index.
- Rule targets are compared against that index, honouring each rule's `apiRange`.
- Property files are read directly from the extraction.

The whole procedure is scripted — re-run it for any new ROM:

```bash
python tools/rom-audit/rom_audit.py \
  --rom MIUI12=/path/to/MIUI12/ROM \
  --rom MIUI13=/path/to/MIUI13/ROM \
  --rom MIUI14=/path/to/MIUI14/ROM
```

Add `--json out.json` for machine-readable output, `--dexdump <path>` to override the
binary location.

> [!NOTE]
> The tool compares **full** method signatures. A few rules match on
> name + return type only (for example `StrictJarVerifierRule`), so a `no-method`
> row is a hint to read that rule's `applyMethod()` before acting on it.

---

## 2. How surya derives `ro.product.*` — the key finding

None of the three ROMs store the plain `ro.product.brand` / `.device` / `.model` keys.
init **derives** them at boot from the partition-prefixed variants, following
`ro.product.property_source_order`:

```properties
# MIUI 12  (system/build.prop:278)
ro.product.property_source_order=odm,vendor,product,product_services,system

# MIUI 13 / 14  (system/build.prop:240)
ro.product.property_source_order=odm,vendor,product,system_ext,system
```

**`odm` is first in the order on all three ROMs.** The value that decides the device
model is therefore `ro.product.odm.model` — which on surya lives in
`/vendor/odm/etc/build.prop`, *not* in `/system/build.prop`:

```properties
# /vendor/odm/etc/build.prop (MIUI 13)
ro.product.odm.brand=POCO
ro.product.odm.device=surya
ro.product.odm.manufacturer=Xiaomi
ro.product.odm.model=M2007J20CG
ro.product.odm.name=surya_id
ro.product.odm.marketname=POCO X3 NFC
```

Consequences for the patch:

1. Writing only to `/system/build.prop` cannot change the device identity.
2. The same spoofed values must be written to **every** partition that participates in
   the source order, because a reader may ask for either the plain or a prefixed key
   (DroidGuard asks for both). This is what `PlayIntegritySetup.propMapFor()` does.

---

## 3. The per-SKU import trap

Both vendor property files end with a **per-SKU import**:

```properties
# /vendor/build.prop:445 (MIUI 12) / :452 (MIUI 13, 14)
import /vendor/build_${ro.boot.product.hardware.sku}.prop

# /vendor/odm/etc/build.prop:27 (MIUI 12) / :28 (MIUI 13, 14)
import /vendor/odm/etc/build_${ro.boot.product.hardware.sku}.prop
```

On surya `ro.boot.product.hardware.sku=surya`, so `build_surya.prop` is pulled in — and
it re-declares exactly the identity block:

```properties
# /vendor/odm/etc/build_surya.prop
ro.odm.build.fingerprint=POCO/surya_id/surya:12/RKQ1.211019.001/V13.0.5.0.SJGIDXM:user/release-keys
ro.product.odm.brand=POCO
ro.product.odm.device=surya
ro.product.odm.model=M2007J20CG
ro.product.odm.name=surya_id
```

Because the `import` sits **after** the identity block, the SKU file is the last writer
and wins for every key it repeats.

> [!WARNING]
> `PropPatcher` replaces a key **in place** when it already exists and appends new keys
> at EOF. Replacing `ro.product.odm.model` in `build.prop` therefore looks successful —
> the line really is rewritten — but the import restores the stock value at boot. The
> patch reports success and the device is still a POCO X3. This was a real bug.

**Fix.** `PlatformProfiles.skuPropTargets()` adds the SKU files as patch targets:

```kotlin
PatchTarget(JarKind.PROPS, "vendor/build_$sku.prop", required = false)
PatchTarget(JarKind.PROPS, "vendor/odm/etc/build_$sku.prop", required = false)
```

The device's own SKU is patched first, then every known SKU (`surya`, `karna`), so one
zip still works on a sibling device. Missing files are skipped (`required = false`).
`DeviceDetector` reads `ro.boot.product.hardware.sku` (falling back to
`ro.boot.hardware.sku`) to fill `DeviceProfileInfo.hardwareSku`.

All three ROMs ship all four SKU files, so the fix applies uniformly:

| SKU file | MIUI 12 | MIUI 13 | MIUI 14 |
|---|---|---|---|
| `vendor/build_surya.prop` | ✔ | ✔ | ✔ |
| `vendor/build_karna.prop` | ✔ | ✔ | ✔ |
| `vendor/odm/etc/build_surya.prop` | ✔ | ✔ | ✔ |
| `vendor/odm/etc/build_karna.prop` | ✔ | ✔ | ✔ |

No installer change was needed: `installer.sh` derives the partitions to mount and back
up from the manifest paths (`cut -d/ -f1`), so `vendor/...` targets are handled already.

---

## 4. Property file layout

| File | MIUI 12 | MIUI 13 | MIUI 14 |
|---|---|---|---|
| `system/build.prop` | ✔ | ✔ | ✔ |
| `system/default.prop` | ✘ | ✘ | ✘ |
| `product/build.prop` | ✔ | ✘ | ✘ |
| `product/etc/build.prop` | ✘ | ✔ | ✔ |
| `system_ext/etc/build.prop` | ✘ | ✔ | ✔ |
| `vendor/build.prop` | ✔ | ✔ | ✔ |
| `vendor/default.prop` | ✔ | ✔ | ✔ |
| `vendor/odm/etc/build.prop` | ✔ | ✔ | ✔ |

Notes:

- **MIUI 12 has no `system_ext` partition at all** and keeps product props in
  `/product/build.prop`. MIUI 13/14 moved them to `/product/etc/build.prop` and added
  `/system_ext/etc/build.prop`. `PlatformProfiles` models this as `legacyPropTargets`
  vs `modernPropTargets`.
- **`system/default.prop` does not exist on any of the three ROMs.** The target is kept
  (`required = false`) for older layouts, but it is a no-op here.
- `ro.secure`, `ro.debuggable`, `ro.adb.secure` are declared in `/system/build.prop`
  on MIUI 13/14, and in `/vendor/default.prop` (`ro.adb.secure` only) on MIUI 12.
  **MIUI 12 declares no `ro.secure`/`ro.debuggable` in any build.prop** — those come
  from the ramdisk `default.prop` inside `boot.img`. Since `ro.*` properties are
  write-once, appending them to a build.prop is ignored; only the boot-classpath hook
  (`SystemProperties` filter) can answer for them there.

---

## 5. Rule-by-rule verdict

`ok` = signature present, the rule can fire. `n/a` = absent, but the rule is gated to a
different API level, which is expected.

| Rule | MIUI 12 | MIUI 13 | MIUI 14 |
|---|---|---|---|
| `InstrumentationInit` (2-arg) | ok | ok | ok |
| `InstrumentationInit` (3-arg) | ok | ok | ok |
| `HasSystemFeature` | ok | ok | ok |
| `GenerateSoftwareKeyPair` (keystore2) | n/a | ok | ok |
| `GenerateSoftwareKeyPair` (legacy) | ok | n/a | n/a |
| `CertificateChain` (keystore2) | n/a | ok | ok |
| `CertificateChain` (legacy) | ok | n/a | n/a |
| `CertificateAlias` (keystore2) | no-class | ok | ok |
| `CertificateAlias` (legacy) | ok | no-class | no-class |
| `HideDevStatus` / `SettingsNameValueCache` | ok | ok | ok |
| `SystemProperties` get ×2 | ok | ok | ok |
| `SystemPropertiesPrimitive` getInt/getLong/getBoolean | ok | ok | ok |
| `BuildFieldClassRule` (`Build`, `Build$VERSION`) | ok | ok | ok |
| `SigningDetails` (modern `android.content.pm`) | no-class | no-class | no-class |
| `SigningDetails` (legacy `PackageParser$SigningDetails`) | ok | ok | ok |
| `MessageDigestForce` (V2 / V3 / blockutils) | ok | ok | ok |
| `MinimumSignatureScheme` | n/a | ok | ok |
| `StrictJarVerifier` | ok | ok | ok |
| `SystemServerInit` (invoke site) | ok | ok | ok |
| `AppsFilter` / `AppsFilterImpl` (A13+) | n/a | n/a | n/a |
| `LegacyAppsFilter` (A11/12) | n/a | ok | ok |
| `InstallerSource` (A13+) | n/a | n/a | n/a |
| `PackageManagerInstaller` | ok | ok | ok |
| `FilterAppAccess` (`String,int,int`) | no-method | ok | ok |
| `FilterAppAccess` (`PackageSetting,int,int`) | ok | no-method | no-method |
| `DevicePolicySecure` (A11+) | n/a | ok | ok |
| `LegacyScreenCapture` (A10) | ok | n/a | n/a |
| `WindowSecure` (`WindowState`) | no-method | ok | ok |
| `WindowSecure` (`WindowStateAnimator`) | ok | ok | ok |
| `LegacyWindowManagerSecure` (A10) | ok | n/a | n/a |
| `WindowManagerCapture` (invoke site) | n/a | n/a | n/a |
| `SettingsProvider` | no-class | no-class | no-class |

---

## 6. Rules that were wrong, and what changed

### `SettingsProviderRule` — removed

The 2.0.6.0 patch guide documents a server-side `filterSettingValue` /
`shouldRemoveSetting` patch on the SettingsProvider GET path. It cannot work:

- `com.android.providers.settings.SettingsProvider` lives in
  `/system/priv-app/SettingsProvider/SettingsProvider.apk`, **not** in `services.jar`.
  `services.jar` contains zero `providers/settings` classes. The old rule was gated to
  `JarKind.SERVICES`, so it could never reach the class.
- The provider has **no `getStringForUser` and no `getString`** on any of the three
  ROMs. The only String-returning server-side entry point is
  `getSettingValue(Landroid/os/Bundle;)Ljava/lang/String;`, whose body is effectively
  `return request.getString("value")` — it carries **no table name**, so a per-app
  filter cannot be built on it.

Per-app Settings spoofing is therefore client-side only, on
`Settings$NameValueCache.getStringForUser`, which exists on all three ROMs and covers
both app and `system_server` reads. A genuine server-side variant would have to
post-process the Bundle from `SettingsProvider.call(String, String, Bundle)` and would
require patching the priv-app APK — replacing a signed system app with an unsigned one,
which the boot scanner rejects. **The rule was deleted**; the reasoning is preserved as
a comment in `ServiceRules.kt`.

### `StrictJarVerifierRule` — no change needed

An earlier note claimed this rule was dead because it "expects
`verifyMessageDigest([BLjava/lang/String;)Z` while the ROM has `([B[B)Z`". That was a
false alarm: the rule matches on **name + return type** only, so it fires correctly on
`([B[B)Z`. Verified present on all three ROMs.

### API gates added

These rules previously declared an unbounded `0..Int.MAX_VALUE` range, claiming
coverage they never had. Each now carries the range the audit supports:

| Rule | New `apiRange` | Why |
|---|---|---|
| `AppsFilterRule` | `33..MAX` | `AppsFilterBase`/`AppsFilterImpl` are A13+; absent on all three ROMs |
| `InstallerSourceRule` | `33..MAX` | `ComputerEngine.getInstallerPackageName` absent on all three |
| `DevicePolicySecureRule` | `31..MAX` | `isScreenCaptureAllowed(int,boolean)` is A11+ |
| `WindowManagerCaptureRule` | `33..MAX` | `notAllowCaptureDisplay` has **0 occurrences** — R8-inlined away on surya |
| `LegacyAppsFilterRule` | `30..32` | `AppsFilter` exists A11/12 only; no AppsFilter class in A10 |
| `LegacyWindowManagerSecureRule` | `0..30` | `WindowManagerService.isSecureLocked(WindowState)` is A10 only |
| `MinimumSignatureSchemeRule` | `31..MAX` | method absent on MIUI 12 |

---

## 7. Known residuals

These are deliberate, documented gaps rather than bugs.

- **`ro.product.board` stays `surya`.** It lives in `vendor/build_surya.prop`, so it
  could be overwritten, but the PIF profile carries no `BOARD` value and guessing one
  risks mismatching device-specific HAL config. It is also not meaningful cover:
  `ro.boot.product.hardware.sku` and `ro.board.platform` are `ro.boot.*`-class values
  the patch cannot touch anyway.
- **`ro.product.odm.marketname=POCO X3 NFC` stays.** A MIUI-only cosmetic key, not
  consulted for attestation.
- **`ro.secure` / `ro.debuggable` on MIUI 12** can only be spoofed through the hook,
  not through build.prop (see §4).
- **A13+ rules are untested on surya**, because no surya ROM in this set is A13+.
  `AppsFilterRule`, `AppsFilterImpl`, `InstallerSourceRule` and
  `WindowManagerCaptureRule` are written against upstream AOSP and stay dormant here.

---

## 8. Reproducing this audit

```bash
# full report for three ROMs
python tools/rom-audit/rom_audit.py \
  --rom MIUI12=C:/Users/.../MIUI12/ROM \
  --rom MIUI13=C:/Users/.../MIUI13/ROM \
  --rom MIUI14=C:/Users/.../MIUI14/ROM \
  --json tools/rom-audit/last-audit.json
```

Both extraction layouts are handled automatically:
`<rom>/system/system/framework/framework.jar` (system-as-root) and
`<rom>/system/framework/framework.jar`, while `product/`, `vendor/` and `system_ext/`
are looked up directly under `<rom>`.

When porting to a new device, the three things to check first are:

1. `ro.product.property_source_order` — which partition wins the identity keys.
2. Whether `build.prop` ends with a per-SKU `import` — if so, the SKU file must be
   patched too.
3. The `no-class` / `no-method` rows for rules whose `apiRange` covers the target API.
