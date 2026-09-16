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

### How init actually finds these files

Verified against the stock `init` binary rather than assumed from AOSP. The binary
contains the literal templates `/build.prop`, `/default.prop` and `/etc/build.prop`,
plus the error string `Could not expand import: `. So init:

- loads, for each partition it knows about, `<mount_point>/build.prop`,
  `<mount_point>/default.prop` and `<mount_point>/etc/build.prop` — one set of templates
  explains why Android 10 keeps product props at `/product/build.prop` while Android 11+
  moved them to `/product/etc/build.prop`;
- processes `import <path>` **inline**, with `${property}` substitution (the
  `Could not expand import:` string is init's own failure path for that expansion);
- does **not** have a top-level `/odm` on surya. There is no `odm` entry in
  `vendor/etc/fstab.default` and no `odm/` directory in the dump — the odm partition is
  mounted at `/vendor/odm`, which is why the odm properties live at
  `/vendor/odm/etc/build.prop`. (`system/etc/ueventd.rc` imports `/odm/etc/ueventd.rc`,
  and `libcutils.so` carries the relative list `odm/build.prop`, `odm/etc/build.prop`,
  `product/build.prop`, `system/build.prop`, `system_ext/build.prop`, `vendor/build.prop`.)

The practical upshot: the odm file **is** read, and its `import` **is** processed in
place — which is what makes the next section matter.

`prop_resolve.py` models exactly this and reports who wins each key; see §9.

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

### Proof, using the real `PropPatcher`

`tools/rom-audit/prop_resolve.py` expands `import` directives in load order and reports
who wins each key. Against stock MIUI 13 it shows the SKU file winning outright:

```text
TRACE ro.product.odm.model
  vendor/odm/etc/build.prop:20                   M2007J20CG
  vendor/odm/etc/build_surya.prop:7              M2007J20CG  <- WINNER
```

Then, applying the ODM partition map with the real `PropPatcher` (via
`patcher --patch-prop`):

**Before the fix — patch `vendor/odm/etc/build.prop` only.** The patcher reports
`11 replaced, 1 appended`, and the file really does change:

```text
TRACE ro.product.odm.model
  vendor/odm/etc/build.prop:20                   Pixel 8 Pro
  vendor/odm/etc/build_surya.prop:7              M2007J20CG  <- WINNER
```

Final value: **M2007J20CG**. The patch succeeded and the device is still a POCO X3.

**After the fix — also patch `vendor/odm/etc/build_surya.prop`:**

```text
TRACE ro.product.odm.model
  vendor/odm/etc/build.prop:20                   Pixel 8 Pro
  vendor/odm/etc/build_surya.prop:7              Pixel 8 Pro  <- WINNER
```

Final value: **Pixel 8 Pro**, with both writers agreeing, so the result no longer depends
on which one init happens to apply last. `ro.odm.build.fingerprint`,
`ro.product.odm.brand` and `ro.product.odm.device` resolve the same way.

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

## 6. End-to-end verification

Knowing a signature exists is not the same as knowing the patch applies. The patcher CLI
was therefore run against all six stock jars:

```bash
patcher/build/install/patcher/bin/patcher \
  --input <stock framework.jar|services.jar> \
  --output out.jar --kind framework|services \
  --profile surya-miui12|surya-miui13|surya-miui14 \
  --hook build/hook/hook.dex
```

Result: **0 skipped, 0 engine errors** on every jar.

| ROM | Jar | Rules applied | Dex files | Classes before → after |
|---|---|---|---|---|
| MIUI 12 | framework | 14 | 5 (2 with hook calls) | 19,197 → 19,216 |
| MIUI 12 | services | 6 | 3 (2 with hook calls) | 7,368 → 7,387 |
| MIUI 13 | framework | 15 | 5 (2 with hook calls) | 23,675 → 23,694 |
| MIUI 13 | services | 6 | 3 (2 with hook calls) | 10,934 → 10,953 |
| MIUI 14 | framework | 15 | 5 (2 with hook calls) | 23,682 → 23,701 |
| MIUI 14 | services | 6 | 3 (2 with hook calls) | 10,937 → 10,956 |

Every jar gained **exactly +19 classes** — the hook's own class count — so no original
class was lost or duplicated by the dexlib2 rewrite.

Which rules fire per ROM matches the `apiRange` table in §5 exactly, which is the real
point of the exercise:

- MIUI 12 uses the **legacy** keystore path (`android/security/keystore/*`); MIUI 13/14
  use **keystore2**. Never both.
- `corepatch.apksignatureverifier.minimumscheme` fires on MIUI 13/14 only — correct, it is
  gated to API 31+ and MIUI 12 is API 29.
- `legacy.appsfilter.shouldFilterApplication` fires on MIUI 13/14 only — correct, `AppsFilter`
  is A11/12 and the gate is `30..32`.
- `legacy.wm.isSecureLocked` and `legacy.devicepolicy.getScreenCaptureDisabled` fire on
  MIUI 12 only — correct, both are gated `0..30`.
- `WindowManagerCapture` never fires anywhere — correct, the method is inlined away.
- `corepatch.strictjarverifier.verifydigest` fires on **all three** — confirming the rule was
  never dead (see §7).

---

## 7. Rules that were wrong, and what changed

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

## 8. Known residuals

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

## 9. Reproducing this audit

Two scripts, because the question has two halves — what is in the ROM, and who wins.

**`rom_audit.py` — what the ROM contains.** Dumps `framework.jar`, `services.jar` and
`SettingsProvider.apk` with `dexdump`, compares every rule target honouring its
`apiRange`, and lists the property-file layout.

```bash
python tools/rom-audit/rom_audit.py \
  --rom MIUI12=C:/Users/.../MIUI12/ROM \
  --rom MIUI13=C:/Users/.../MIUI13/ROM \
  --rom MIUI14=C:/Users/.../MIUI14/ROM \
  --json tools/rom-audit/last-audit.json
```

**`prop_resolve.py` — who wins each key.** Expands `import` directives in load order and
reports the winning value and file for each key, with `--trace` to list every writer and
`--patch FILE=JSON` to apply a map first.

```bash
# who currently wins the identity keys?
python tools/rom-audit/prop_resolve.py --rom /path/to/MIUI13/ROM --sku surya

# prove a patch end to end
python tools/rom-audit/prop_resolve.py --rom /path/to/MIUI13/ROM --sku surya \
  --patch vendor/odm/etc/build.prop=odm.json \
  --patch vendor/odm/etc/build_surya.prop=odm.json \
  --trace ro.product.odm.model
```

And to validate the dex side end to end:

```bash
./gradlew :patcher:installDist
patcher/build/install/patcher/bin/patcher \
  --input <stock jar> --output out.jar --kind framework \
  --profile surya-miui13 --hook build/hook/hook.dex
```

Both extraction layouts are handled automatically:
`<rom>/system/system/framework/framework.jar` (system-as-root) and
`<rom>/system/framework/framework.jar`, while `product/`, `vendor/` and `system_ext/`
are looked up directly under `<rom>`.

When porting to a new device, the three things to check first are:

1. `ro.product.property_source_order` — which partition wins the identity keys.
2. Whether `build.prop` ends with a per-SKU `import` — if so, the SKU file must be
   patched too, and `prop_resolve.py --trace` will show it winning.
3. The `no-class` / `no-method` rows for rules whose `apiRange` covers the target API.
