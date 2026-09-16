# Farewell Toolbox Framework 2.0.6.0  

> Keep the stock JARs. Do not replace a stock DEX or copy a complete template class into a different ROM.  

> [!IMPORTANT]
> **Scope: surya (POCO X3 / M2007J20CG, M2007J20CT, M2007J20CI) only** — MIUI 12
> (Android 10), MIUI 13 and MIUI 14 (Android 12). Patches for classes that only exist in
> Android 13+ have been removed; see [ROM Audit: Surya](ROM_Audit_Surya.md) for what each
> stock ROM actually contains.

> [!IMPORTANT]
> **Hook class and method names are generated per build.** Everywhere this guide writes a
> `Landroid/security/farewell/FarewellHook;->someMethod` style call, substitute the real
> names from `build/hook-identity.txt` (or the generated
> `patcher/src/main/kotlin/io/farewell/patcher/HookIdentity.kt`). The class name and all
> method names are randomised on every build and must never be copied literally.
  
## 1. `framework.jar`  
  
### A. Initialize each app  
  
**Class:**  
```smali  
Landroid/app/Instrumentation;  
```  
  
**Reference smali:** [`Instrumentation.smali`](../Template/Template_V2060/framework/Instrumentation.smali)  
  
**Method:**  
```smali  
 newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;  
```  
  
before line  
```smali  
return-object xY  
    .end method  
```  
  
Add  
```smali  
invoke-static {p1}, Landroid/security/farewell/FarewellHook;->initContext(Landroid/content/Context;)V  
```  
  
**Method:**  
```smali  
 newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;  
```  
  
before line  
```smali  
return-object xY  
    .end method  
```  
  
add  
```smali  
invoke-static {p3}, Landroid/security/farewell/FarewellHook;->initContext(Landroid/content/Context;)V  
```  
---  
  
### B. Hook system features  
  
**Class:**  
```smali  
Landroid/app/ApplicationPackageManager;  
```  
  
**Reference smali:** [`ApplicationPackageManager.smali`](../Template/Template_V2060/framework/ApplicationPackageManager.smali)  
  
**Method:**   
```smali  
 hasSystemFeature(Ljava/lang/String;I)Z  
```  
  
Add the following code below `.registers X`:  
```smali  
invoke-static {p1, p2}, Landroid/security/farewell/FarewellHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;  
move-result-object v0  
  
if-eqz v0, :cond_farewell_feature_stock  
invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z  
move-result v0  
return v0  
  
:cond_farewell_feature_stock  
```  
  
---  
  
### C. Hook software key generation  
  
**Class:**  
```smali  
Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;  
```  
  
**Reference smali:** [`AndroidKeyStoreKeyPairGeneratorSpi.smali`](../Template/Template_V2060/framework/AndroidKeyStoreKeyPairGeneratorSpi.smali)  
  
**Method:**  
```smali  
 generateKeyPair()Ljava/security/KeyPair;  
```  
  
Add the following code below `.registers X`:  
```smali  
invoke-static {p0}, Landroid/security/farewell/FarewellHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;  
move-result-object vX  
  
if-eqz vX, :cond_farewell_gen_stock  
return-object vX  
  
:cond_farewell_gen_stock  
```  
  
In this method, pay attention to `.registers X`.  
  
- Increase the current register count by `1`  
- Replace `vX` with the register number at `registers - 2`  
  
Example:  
  
- If the method originally uses `15` registers  
- Change it to `16` registers  
- Then change `vX` to `v14`  
---  
  
### D. Hook the certificate chain  
  
**Class:**  
```smali  
Landroid/security/keystore2/AndroidKeyStoreSpi;  
```  
  
**Reference smali:** [`AndroidKeyStoreSpi.smali`](../Template/Template_V2060/framework/AndroidKeyStoreSpi.smali)  
  
**Method:**  
```smali  
 engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;  
```  
  
Before the final return, pass the final `Certificate[]` through Farewell:  
  
Find this part:  
  
```smali  
const/4 vA, 0x0  
aput-object vB, vC, vA  
return-object vD  
```  
  
below line  
```smali  
const/4 vA, 0x0  
aput-object vB, vC, vA  
```  
add  
  
```smali  
invoke-static {vC}, Landroid/security/farewell/FarewellHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;  
move-result-object vD  
# return-object vD  
```  
  
#### Note  
  
`move-result-object vD` is the value returned by `return-object vD`.  
  
Also, in `invoke-static {vC}`, the array register `vC` is the same register used by `aput-object vB, vC, vA`.  
  
#### Example  
  
```smali  
const/4 v4, 0x0  
aput-object v2, v3, v4  
  
invoke-static {v3}, Landroid/security/farewell/FarewellHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;  
move-result-object v3  
  
return-object v3  
```  
  
---  
  
## 2. `services.jar`  
  
**Class:**  
```smali  
Lcom/android/server/SystemServer;  
```  
  
**Reference smali:** [`SystemServer.smali`](../Template/Template_V2060/service/SystemServer.smali)  
  
before line  
```smali  
Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V  
```  
  
add  
```smali  
invoke-static {}, Landroid/security/farewell/FarewellHook;->initSystemServer()V  
```  
  
---  
  
## Notes  

- The Build-field patch (`framework.build.unfinal`) is applied by the patcher on all three
  surya ROMs, so no separate step is needed.  

## 3. Supplementary patches (test)  
  
These are optional. Add only the feature you need, after the core patch boots correctly.  
  
### Hide Developer options / ADB state  
  
**Class:** `Landroid/provider/Settings$NameValueCache;`    
**Reference smali:** [`Settings$NameValueCache.smali`](../Template/Template_V2060/framework/Settings$NameValueCache.smali)    
**Method:** `getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;`  
  
Add the following code below `.registers X`:  
  
```smali  
if-eqz p2, :cond_farewell_dev_stock  
invoke-static/range {p1 .. p3}, Landroid/security/farewell/FarewellHook;->shouldHideDevStatusFromNameValueCache(Landroid/content/ContentResolver;Ljava/lang/String;I)Z  
move-result v0  
if-eqz v0, :cond_farewell_dev_stock  
const-string v0, "0"  
return-object v0  
  
:cond_farewell_dev_stock  
```  
  
Use only the overload returning `String`; do not paste this into a `Pair`-returning overload.  
  
### Hide installed apps per caller  

Patch the Package Manager filter method the target ROM actually has. The patcher handles
this with `LegacyAppsFilterRule` on MIUI 13/14 and `FilterAppAccessRule` on MIUI 12:

| ROM | Class | Method |
|---|---|---|
| MIUI 13 / 14 | `Lcom/android/server/pm/AppsFilter;` | `shouldFilterApplication(...)` |
| MIUI 12 | `Lcom/android/server/pm/PackageManagerService;` | `filterAppAccessLPr(Lcom/android/server/pm/PackageSetting;II)Z` |

> [!NOTE]
> `AppsFilterBase` / `AppsFilterImpl` are Android 13+ and do **not** exist on surya
> (0 hits in all three stock services.jar files), so there is nothing to patch under those
> names.

```smali  
# callingUid, null resolver, target package name, userId  
invoke-static {vCallingUid, vNull, vTargetPackage, vUserId}, Landroid/security/farewell/FarewellHook;->shouldHideAppListForCaller(ILandroid/content/ContentResolver;Ljava/lang/String;I)Z  
move-result vResult  
if-eqz vResult, :cond_farewell_hide_stock  
const/4 v0, 0x1  
return v0  

:cond_farewell_hide_stock  
```  
  
The argument order is fixed: `callingUid, resolver, targetPackageName, userId`. Find the real registers in your ROM; the template is reference only.  
  
### Spoof installer source  

**Class:** `Lcom/android/server/pm/PackageManagerService;`    
**Method:** `getInstallerPackageName(Ljava/lang/String;)Ljava/lang/String;`  

> [!NOTE]
> The method is declared on `PackageManagerService` itself on all three surya ROMs, so the
> patcher uses `PackageManagerInstallerRule`. `ComputerEngine` (Android 13+) does not exist
> on surya — 0 hits in all three stock services.jar files.

After the stock installer value is resolved, pass it through:  
  
```smali  
const/4 vNull, 0x0  
invoke-static {vNull, vCallingUid, p2, p1, vInstaller}, Landroid/security/farewell/FarewellHook;->filterInstallerPackageName(Landroid/content/ContentResolver;IILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;  
move-result-object vInstaller  
return-object vInstaller  
```  
  
Identify the calling UID, user ID, queried package and stock installer value before adapting this block.  
  
### Filter Settings values per calling app

This patch changes only the value returned to the app that is reading Settings;
it never writes or changes the real setting. It supports the three exact table
names `global`, `secure`, and `system`.

**Patch location:** `Landroid/provider/Settings$NameValueCache;` →
`getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;`.
The patcher applies this with `SettingsNameValueCacheRule`. `Settings$NameValueCache` lives
in `framework.jar`, which sits on the boot classpath of every process, so the hook runs in
the process doing the reading — the caller identity is the app itself and there is no Binder
identity to preserve.

> [!IMPORTANT]
> There is deliberately **no** server-side `SettingsProvider` patch. On surya MIUI 12/13/14
> `com.android.providers.settings.SettingsProvider` lives in
> `/system/priv-app/SettingsProvider/SettingsProvider.apk`, **not** in `services.jar`, and it
> declares neither `getStringForUser` nor `getString`. Its only String-returning entry point
> is `getSettingValue(Landroid/os/Bundle;)Ljava/lang/String;`, whose body is effectively
> `return request.getString("value")` — it carries no table name, so a per-app filter cannot
> be built there. Patching the priv-app would also swap a signed system app for an unsigned
> one, which the boot scanner rejects. See [ROM Audit: Surya](ROM_Audit_Surya.md).

Insert at the top of the method, below `.registers X`. `p0` is the `ContentResolver`, `p1`
the setting name, `p2` the user id and `v0` any free local register:

```smali
invoke-static {p0, p1, p2}, Landroid/security/farewell/FarewellHook;->hasSettingOverride(Ljava/lang/Object;Ljava/lang/String;I)Z
move-result v0
if-eqz v0, :cond_farewell_setting_stock
invoke-static {p0, p1}, Landroid/security/farewell/FarewellHook;->settingOverrideValue(Ljava/lang/Object;Ljava/lang/String;)Ljava/lang/String;
move-result-object v0
return-object v0

:cond_farewell_setting_stock
```

The two entry points are ordered: `hasSettingOverride` decides whether an override exists
for this app, and only then does `settingOverrideValue` produce the replacement `String`.
Use only the `String`-returning overload; do not paste this into a `Pair`- or
`Bundle`-returning method.

#### Advanced features patch check

These requirements apply to Toolbox/framework builds that include the Advanced
patch probe. Older builds without the probe do not satisfy the check, even if
they report the same framework version.

1. Install a matching Toolbox APK and framework DEX **with probe support**.
2. Patch `Settings$NameValueCache.getStringForUser` as shown above. The probe drives
   the same two entry points, so a patch that skips either one fails the check.
3. Cover missing keys as well as existing values. An early return for a missing
   key must not skip these hooks.
4. Reboot after updating the framework patch, reopen Toolbox and let
   its check finish before enabling **Advanced features**.

The app requires a non-empty framework version and sends fresh, read-only
challenges through each table's Settings reader. The probe answers only when the
override stage has run for the same namespace/key on the same thread. It runs before
HMA/config reads and does not require Advanced to be enabled. It does not write
test settings or trust saved working flags.

| Situation | Expected behavior |
|---|---|
| Check pending or toggle write in progress | Switch disabled |
| Framework absent/old, missing hook, or probe read fails | Advanced unavailable; patch-required message |
| Matching framework and all three table probes pass | Switch available; enabling still requires write permission |
| Root fallback enabled, but probe fails | Enabling remains blocked before any write fallback |
| Saved Advanced flag is ON, but probe fails | Advanced UI stays unavailable; reading does not rewrite the saved flag |
| Probe passes, but writing fails | Previous switch state retained; write failure shown |

Before writing an enabled value, the
app checks again; `true`/`TRUE` and surrounding Java-style whitespace follow the
same boolean rules as the framework. Disabling is not blocked by the probe at
the write API, although normal permissions/block policy still apply.

If the switch stays locked, verify the deployed framework contains the probe,
that both call sites execute for missing keys in every table, and that the device
rebooted into the patched files. Do not seed probe keys or force the saved
Advanced flag to bypass the check. A matching version string or root access is
insufficient. The probe checks Settings capability, not AppsFilter or
installer-source hooks; it is not a security attestation against a
modified/rooted system.

#### Toolbox configuration

Once the patch check passes, enable **Advanced features**, then add entries in Toolbox. The stored format is
version 2 and separates each app by table:

```json
{
  "version": 2,
  "apps": {
    "com.example.app": {
      "secure": { "android_id": "0123456789abcdef" },
      "global": { "example_key": "1" },
      "system": { "example_key": "value" }
    }
  }
}
```

The per-app Settings spoof branch leaves its input unchanged when Advanced is
off, the table is unsupported, the caller is system/Toolbox, the caller UID maps
to more than one package, or no matching entry exists. Its input may already
have been changed by HMA: HMA value filtering/removal runs separately and does
not share every guard. Do not assume that switching Advanced off restores stock
values for existing HMA rules. Per-app value spoofs are strings; a key removal
is supplied by the HMA Settings rule through `shouldRemoveSetting(...)` above.

Test one configured app, an unconfigured app, all three tables and a missing
setting before shipping. Do not use this hook to bypass permissions or alter
SettingsProvider's access checks.

Host regression tests cover the probe/state reader and write-gate logic with
Android/provider boundaries simulated; they do not prove real Binder identity,
permissions, Compose behavior or ROM compatibility. On the target ROM, test
missing/partial patches, root fallback with no patch, a stale enabled flag,
permission-denied writes, rapid toggles and both Settings layouts. Also check
ordinary GETs with a cold config cache for recursion, and verify that no probe
keys are persisted. A successful capability check is not a full runtime audit.
  
### Disable `FLAG_SECURE`  
  
[Disable Secure Flag guide](Disable_Secure_Flag.md).  
  
### Disable Signature Verification  
  
[CorePatch guide](CorePatch.md). (It may differ from some ROMs)  
  
Reference smali directory: [`Template/Template_V2060`](../Template/Template_V2060)  
