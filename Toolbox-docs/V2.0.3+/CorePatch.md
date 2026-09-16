This is a reference example. Some values may vary depending on the ROM!

## 1. `framework.jar`

### `android.content.pm.PackageParser`

**Smali example:** [`PackageParser.smali`](../Template/Template_V2060/framework/PackageParser.smali)

Find:

```smali
invoke-static {v2, v0, v1}, Landroid/util/apk/ApkSignatureVerifier;->unsafeGetCertsWithoutVerification(Landroid/content/pm/parsing/result/ParseInput;Ljava/lang/String;I)Landroid/content/pm/parsing/result/ParseResult;
```

Insert above:

```smali
const/4 v1, 0x1
```

Find the section that checks for the string `"<manifest> specifies bad sharedUserId name \""`. Immediately before the related `if-nez v5, :cond_x` branch, force the value:

```smali
const/4 v14, 0x1
```

### `android.content.pm.PackageParser$PackageParserException`

**Smali example:** [`PackageParser$PackageParserException.smali`](../Template/Template_V2060/framework/PackageParser$PackageParserException.smali)

Find:

```smali
iput p1, p0, Landroid/content/pm/PackageParser$PackageParserException;->error:I
```

Insert above:

```smali
const/4 p1, 0x0
```

### `android.content.pm.PackageParser$SigningDetails`

**Smali example:** [`PackageParser$SigningDetails.smali`](../Template/Template_V2060/framework/PackageParser$SigningDetails.smali)

Find the three `checkCapability` methods and make each method return `1`.

In the same class, find `hasAncestorOrSelf` and make the method return `1`.

> [!NOTE]
> This is the only `SigningDetails` class on surya. `android.content.pm.SigningDetails`
> (the top-level class AOSP moved to in Android 13) does **not** exist in any of the three
> stock framework.jar files — verified by binary search, 0 hits on MIUI 12/13/14. There is
> nothing to patch under that name.

### `android.util.apk.ApkSignatureSchemeV2Verifier`

**Smali example:** [`ApkSignatureSchemeV2Verifier.smali`](../Template/Template_V2060/framework/ApkSignatureSchemeV2Verifier.smali)

Find:

```smali
invoke-static {v8, v4}, Ljava/security/MessageDigest;->isEqual([B[B)Z
move-result v0
```

Replace `move-result v0` with:

```smali
const/4 v0, 0x1
```

### `android.util.apk.ApkSignatureSchemeV3Verifier`

**Smali example:** [`ApkSignatureSchemeV3Verifier.smali`](../Template/Template_V2060/framework/ApkSignatureSchemeV3Verifier.smali)

Find:

```smali
invoke-static {v9, v3}, Ljava/security/MessageDigest;->isEqual([B[B)Z
move-result v11
```

Replace `move-result v11` with:

```smali
const/4 v11, 0x1
```

### `android.util.apk.ApkSignatureVerifier`

**Smali example:** [`ApkSignatureVerifier.smali`](../Template/Template_V2060/framework/ApkSignatureVerifier.smali)

Find `getMinimumSignatureSchemeVersionForTargetSdk` and make the method return `0`.

Find:

```smali
invoke-static {p0, p1, p3}, Landroid/util/apk/ApkSignatureVerifier;->verifyV1Signature(Landroid/content/pm/parsing/result/ParseInput;Ljava/lang/String;Z)Landroid/content/pm/parsing/result/ParseResult;
```

Insert above:

```smali
const/4 p3, 0x0
```

### `android.util.apk.ApkSigningBlockUtils`

**Smali example:** [`ApkSigningBlockUtils.smali`](../Template/Template_V2060/framework/ApkSigningBlockUtils.smali)

Find:

```smali
invoke-static {v5, v6}, Ljava/security/MessageDigest;->isEqual([B[B)Z
move-result v7
```

Replace `move-result v7` with:

```smali
const/4 v7, 0x1
```

### `android.util.jar.StrictJarVerifier`

**Smali example:** [`StrictJarVerifier.smali`](../Template/Template_V2060/framework/StrictJarVerifier.smali)

Find `verifyMessageDigest` and make the method return `1`.

### `android.util.jar.StrictJarFile`

**Smali example:** [`StrictJarFile.smali`](../Template/Template_V2060/framework/StrictJarFile.smali)

After the following call:

```smali
invoke-virtual {p0, v5}, Landroid/util/jar/StrictJarFile;->findEntry(Ljava/lang/String;)Ljava/util/zip/ZipEntry;
```

Remove the `if-eqz v6, :cond_x` branch and its corresponding `:cond_x` label that block the mismatched entry.

### `com.android.internal.pm.pkg.parsing.ParsingPackageUtils`

**Not applicable to surya.** This class is the Android 13+ replacement for the parsing
logic in `PackageParser`. It does not exist in any of the three stock framework.jar files
(0 hits on MIUI 12/13/14), so the `"<manifest> specifies bad sharedUserId name \""` check
is patched in `android.content.pm.PackageParser` instead — see the first section above.

## 2. `services.jar`

**Smali examples:** [`PackageManagerServiceUtils.smali`](../Template/Template_V2060/service/PackageManagerServiceUtils.smali) | [`KeySetManagerService.smali`](../Template/Template_V2060/service/KeySetManagerService.smali)

Patch the following methods according to the specified return values:

| Method | Return |
|---|---:|
| `checkDowngrade` | `return-void` |
| `shouldCheckUpgradeKeySetLocked` | `0` |
| `verifySignatures` | `0` |
| `compareSignatures` | `0` |
| `matchSignaturesCompat` | `1` |

> [!NOTE]
> `InstallPackageHelper` and `ReconcilePackageUtils` are not patched: both are Android 13+
> refactors of `PackageManagerService` and do not exist in any of the three stock
> services.jar files (0 hits on MIUI 12/13/14).

## 3. `miui-services.jar`

**Not applicable to surya.** The `verifyIsolationViolation` and `canBeUpdate` methods this
section used to target do **not** exist in `miui-services.jar` or `miui-framework.jar` on
MIUI 13/14 (0 hits on both, and MIUI 12 ships neither jar). This matches the repository
rule that those jars are never patched.