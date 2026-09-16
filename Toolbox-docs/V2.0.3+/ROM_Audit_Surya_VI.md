# Kiểm toán ROM: Surya (POCO X3 / M2007J20CG)

[English](ROM_Audit_Surya.md) | **Tiếng Việt**

Trang này ghi lại **ROM stock** surya MIUI thực sự chứa những gì, và điều đó có ý nghĩa
gì với patcher. Mọi khẳng định dưới đây đều được kiểm chứng trực tiếp trên ảnh stock đã
giải nén, không dựa vào AOSP gốc.

> [!IMPORTANT]
> Bản patch Farewell được viết theo hướng không phụ thuộc thiết bị, nhưng tầng
> *property* thì không: file nào thắng một key là do `ro.product.property_source_order`
> của từng ROM quyết định. Những phát hiện dưới đây chính là thứ giúp việc spoof thực sự
> có tác dụng trên surya.

---

## 1. Phạm vi và phương pháp

| ROM | Android | API | Bản dựng |
|---|---|---|---|
| MIUI 12 | 10 | 29 | `V12.0.9.0.QJGMIXM` |
| MIUI 13 | 12 | 31 | `V13.0.5.0.SJGIDXM` |
| MIUI 14 | 12 | 31 | `V14.0.2.0.SJGIDXM` |

Thư mục giải nén: `crb_340/Projects/MIUI{12,13,14}/ROM`.

Phương pháp:

- `dexdump` từ Android SDK **build-tools 36.0.0** dump `framework.jar`, `services.jar`
  và `SettingsProvider.apk` thành bảng chỉ mục class → chữ ký method.
- Mục tiêu của từng rule được đối chiếu với bảng đó, có tôn trọng `apiRange` của rule.
- Các file property được đọc trực tiếp từ bản giải nén.

Toàn bộ quy trình đã được script hoá — chạy lại cho bất kỳ ROM mới nào:

```bash
python tools/rom-audit/rom_audit.py \
  --rom MIUI12=/path/to/MIUI12/ROM \
  --rom MIUI13=/path/to/MIUI13/ROM \
  --rom MIUI14=/path/to/MIUI14/ROM
```

Thêm `--json out.json` để lấy kết quả dạng máy đọc được, `--dexdump <path>` để chỉ định
vị trí binary.

> [!NOTE]
> Công cụ so sánh **toàn bộ** chữ ký method. Một vài rule chỉ khớp theo tên + kiểu trả
> về (ví dụ `StrictJarVerifierRule`), nên dòng `no-method` chỉ là gợi ý để bạn đọc
> `applyMethod()` của rule đó trước khi kết luận.

---

## 2. Surya suy ra `ro.product.*` như thế nào — phát hiện then chốt

Không ROM nào trong ba bản lưu các key trần `ro.product.brand` / `.device` / `.model`.
init **suy ra** chúng lúc khởi động từ các biến thể có tiền tố phân vùng, theo
`ro.product.property_source_order`:

```properties
# MIUI 12  (system/build.prop:278)
ro.product.property_source_order=odm,vendor,product,product_services,system

# MIUI 13 / 14  (system/build.prop:240)
ro.product.property_source_order=odm,vendor,product,system_ext,system
```

**`odm` đứng đầu thứ tự trên cả ba ROM.** Vì vậy giá trị quyết định model của thiết bị
là `ro.product.odm.model` — và trên surya nó nằm ở `/vendor/odm/etc/build.prop`,
*không phải* `/system/build.prop`:

```properties
# /vendor/odm/etc/build.prop (MIUI 13)
ro.product.odm.brand=POCO
ro.product.odm.device=surya
ro.product.odm.manufacturer=Xiaomi
ro.product.odm.model=M2007J20CG
ro.product.odm.name=surya_id
ro.product.odm.marketname=POCO X3 NFC
```

Hệ quả với bản patch:

1. Chỉ ghi vào `/system/build.prop` thì không thể đổi danh tính thiết bị.
2. Phải ghi cùng giá trị spoof vào **mọi** phân vùng tham gia thứ tự nguồn, vì bên đọc
   có thể hỏi key trần hoặc key có tiền tố (DroidGuard hỏi cả hai). Đây chính là việc
   `PlayIntegritySetup.propMapFor()` làm.

---

## 3. Cái bẫy import theo SKU

Cả hai file property của vendor đều kết thúc bằng một **import theo SKU**:

```properties
# /vendor/build.prop:445 (MIUI 12) / :452 (MIUI 13, 14)
import /vendor/build_${ro.boot.product.hardware.sku}.prop

# /vendor/odm/etc/build.prop:27 (MIUI 12) / :28 (MIUI 13, 14)
import /vendor/odm/etc/build_${ro.boot.product.hardware.sku}.prop
```

Trên surya `ro.boot.product.hardware.sku=surya`, nên `build_surya.prop` được nạp vào —
và nó khai báo lại đúng khối danh tính:

```properties
# /vendor/odm/etc/build_surya.prop
ro.odm.build.fingerprint=POCO/surya_id/surya:12/RKQ1.211019.001/V13.0.5.0.SJGIDXM:user/release-keys
ro.product.odm.brand=POCO
ro.product.odm.device=surya
ro.product.odm.model=M2007J20CG
ro.product.odm.name=surya_id
```

Vì `import` nằm **sau** khối danh tính, file SKU là bên ghi cuối cùng và thắng mọi key
mà nó lặp lại.

> [!WARNING]
> `PropPatcher` thay thế key **tại chỗ** nếu key đã tồn tại, và thêm key mới vào cuối
> file. Vì vậy việc thay `ro.product.odm.model` trong `build.prop` trông như thành
> công — dòng đó thực sự được ghi lại — nhưng import sẽ khôi phục giá trị stock lúc
> khởi động. Patch báo thành công mà thiết bị vẫn là POCO X3. Đây là một bug thật.

**Cách sửa.** `PlatformProfiles.skuPropTargets()` thêm các file SKU thành mục tiêu patch:

```kotlin
PatchTarget(JarKind.PROPS, "vendor/build_$sku.prop", required = false)
PatchTarget(JarKind.PROPS, "vendor/odm/etc/build_$sku.prop", required = false)
```

SKU của chính thiết bị được patch trước, rồi tới mọi SKU đã biết (`surya`, `karna`), nên
một file zip vẫn dùng được cho thiết bị anh em. File thiếu sẽ bị bỏ qua
(`required = false`). `DeviceDetector` đọc `ro.boot.product.hardware.sku` (dự phòng
`ro.boot.hardware.sku`) để điền `DeviceProfileInfo.hardwareSku`.

Cả ba ROM đều có đủ bốn file SKU, nên cách sửa áp dụng đồng nhất:

| File SKU | MIUI 12 | MIUI 13 | MIUI 14 |
|---|---|---|---|
| `vendor/build_surya.prop` | ✔ | ✔ | ✔ |
| `vendor/build_karna.prop` | ✔ | ✔ | ✔ |
| `vendor/odm/etc/build_surya.prop` | ✔ | ✔ | ✔ |
| `vendor/odm/etc/build_karna.prop` | ✔ | ✔ | ✔ |

Không cần sửa installer: `installer.sh` suy ra phân vùng cần mount và backup từ đường
dẫn trong manifest (`cut -d/ -f1`), nên mục tiêu `vendor/...` đã được xử lý sẵn.

---

## 4. Bố cục file property

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

Ghi chú:

- **MIUI 12 hoàn toàn không có phân vùng `system_ext`** và giữ prop của product ở
  `/product/build.prop`. MIUI 13/14 chuyển sang `/product/etc/build.prop` và thêm
  `/system_ext/etc/build.prop`. `PlatformProfiles` mô hình hoá việc này bằng
  `legacyPropTargets` và `modernPropTargets`.
- **`system/default.prop` không tồn tại trên bất kỳ ROM nào trong ba bản này.** Mục
  tiêu vẫn được giữ (`required = false`) cho các bố cục cũ hơn, nhưng ở đây nó vô tác
  dụng.
- `ro.secure`, `ro.debuggable`, `ro.adb.secure` được khai báo trong `/system/build.prop`
  trên MIUI 13/14, và trong `/vendor/default.prop` (chỉ `ro.adb.secure`) trên MIUI 12.
  **MIUI 12 không khai báo `ro.secure`/`ro.debuggable` trong bất kỳ build.prop nào** —
  chúng đến từ `default.prop` trong ramdisk của `boot.img`. Do property `ro.*` chỉ ghi
  được một lần, việc thêm chúng vào build.prop sẽ bị bỏ qua; chỉ hook trên boot
  classpath (bộ lọc `SystemProperties`) mới trả lời được cho chúng ở đó.

---

## 5. Kết luận từng rule

`ok` = chữ ký có mặt, rule có thể hoạt động. `n/a` = không có, nhưng rule được giới hạn
ở mức API khác nên là điều bình thường.

| Rule | MIUI 12 | MIUI 13 | MIUI 14 |
|---|---|---|---|
| `InstrumentationInit` (2 tham số) | ok | ok | ok |
| `InstrumentationInit` (3 tham số) | ok | ok | ok |
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
| `SigningDetails` (hiện đại, `android.content.pm`) | no-class | no-class | no-class |
| `SigningDetails` (legacy `PackageParser$SigningDetails`) | ok | ok | ok |
| `MessageDigestForce` (V2 / V3 / blockutils) | ok | ok | ok |
| `MinimumSignatureScheme` | n/a | ok | ok |
| `StrictJarVerifier` | ok | ok | ok |
| `SystemServerInit` (điểm gọi) | ok | ok | ok |
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
| `WindowManagerCapture` (điểm gọi) | n/a | n/a | n/a |
| `SettingsProvider` | no-class | no-class | no-class |

---

## 6. Những rule sai, và đã thay đổi gì

### `SettingsProviderRule` — đã xoá

Hướng dẫn patch 2.0.6.0 mô tả một bản patch phía server `filterSettingValue` /
`shouldRemoveSetting` trên đường GET của SettingsProvider. Nó không thể hoạt động:

- `com.android.providers.settings.SettingsProvider` nằm ở
  `/system/priv-app/SettingsProvider/SettingsProvider.apk`, **không phải** trong
  `services.jar`. `services.jar` không chứa class `providers/settings` nào. Rule cũ
  được giới hạn ở `JarKind.SERVICES`, nên nó không bao giờ tới được class đó.
- Provider **không có `getStringForUser` và cũng không có `getString`** trên bất kỳ ROM
  nào trong ba bản. Điểm vào phía server duy nhất trả về String là
  `getSettingValue(Landroid/os/Bundle;)Ljava/lang/String;`, với thân hàm thực chất là
  `return request.getString("value")` — nó **không mang tên bảng**, nên không thể xây
  bộ lọc theo từng ứng dụng trên đó.

Vì vậy việc spoof Settings theo từng ứng dụng chỉ còn ở phía client, trên
`Settings$NameValueCache.getStringForUser` — tồn tại trên cả ba ROM và bao phủ cả lượt
đọc từ ứng dụng lẫn `system_server`. Một biến thể phía server thực thụ sẽ phải xử lý
hậu kỳ Bundle từ `SettingsProvider.call(String, String, Bundle)` và sẽ đòi hỏi patch
APK priv-app — tức thay một ứng dụng hệ thống đã ký bằng một bản chưa ký, mà bộ quét
lúc khởi động sẽ từ chối. **Rule đã bị xoá**; lý do được giữ lại dưới dạng chú thích
trong `ServiceRules.kt`.

### `StrictJarVerifierRule` — không cần sửa

Một ghi chú trước đây cho rằng rule này đã chết vì "mong đợi
`verifyMessageDigest([BLjava/lang/String;)Z` trong khi ROM có `([B[B)Z`". Đó là báo
động giả: rule chỉ khớp theo **tên + kiểu trả về**, nên nó hoạt động đúng trên
`([B[B)Z`. Đã xác minh có mặt trên cả ba ROM.

### Các giới hạn API được thêm vào

Trước đây những rule này khai báo khoảng vô hạn `0..Int.MAX_VALUE`, tức tự nhận phạm vi
bao phủ mà chúng chưa từng có. Mỗi rule giờ mang khoảng mà kiểm toán chứng minh:

| Rule | `apiRange` mới | Lý do |
|---|---|---|
| `AppsFilterRule` | `33..MAX` | `AppsFilterBase`/`AppsFilterImpl` là A13+; không có trên cả ba ROM |
| `InstallerSourceRule` | `33..MAX` | `ComputerEngine.getInstallerPackageName` không có trên cả ba |
| `DevicePolicySecureRule` | `31..MAX` | `isScreenCaptureAllowed(int,boolean)` là A11+ |
| `WindowManagerCaptureRule` | `33..MAX` | `notAllowCaptureDisplay` có **0 lần xuất hiện** — bị R8 inline mất trên surya |
| `LegacyAppsFilterRule` | `30..32` | `AppsFilter` chỉ có ở A11/12; A10 không có class AppsFilter |
| `LegacyWindowManagerSecureRule` | `0..30` | `WindowManagerService.isSecureLocked(WindowState)` chỉ có ở A10 |
| `MinimumSignatureSchemeRule` | `31..MAX` | method không có trên MIUI 12 |

---

## 7. Những tồn dư đã biết

Đây là các khoảng trống có chủ đích và đã được ghi lại, không phải bug.

- **`ro.product.board` vẫn là `surya`.** Nó nằm trong `vendor/build_surya.prop` nên có
  thể ghi đè, nhưng profile PIF không mang giá trị `BOARD` và việc đoán một giá trị có
  thể làm lệch cấu hình HAL đặc thù thiết bị. Nó cũng không phải lớp nguỵ trang có ý
  nghĩa: `ro.boot.product.hardware.sku` và `ro.board.platform` là nhóm giá trị
  `ro.boot.*` mà bản patch vốn không thể chạm tới.
- **`ro.product.odm.marketname=POCO X3 NFC` vẫn giữ.** Đây là key chỉ mang tính hiển
  thị của MIUI, không được dùng để attestation.
- **`ro.secure` / `ro.debuggable` trên MIUI 12** chỉ có thể spoof qua hook, không qua
  build.prop (xem §4).
- **Các rule A13+ chưa được kiểm chứng trên surya**, vì không ROM surya nào trong bộ
  này là A13+. `AppsFilterRule`, `AppsFilterImpl`, `InstallerSourceRule` và
  `WindowManagerCaptureRule` được viết theo AOSP gốc và nằm im ở đây.

---

## 8. Tái lập kiểm toán này

```bash
# báo cáo đầy đủ cho ba ROM
python tools/rom-audit/rom_audit.py \
  --rom MIUI12=C:/Users/.../MIUI12/ROM \
  --rom MIUI13=C:/Users/.../MIUI13/ROM \
  --rom MIUI14=C:/Users/.../MIUI14/ROM \
  --json tools/rom-audit/last-audit.json
```

Cả hai kiểu giải nén đều được xử lý tự động:
`<rom>/system/system/framework/framework.jar` (system-as-root) và
`<rom>/system/framework/framework.jar`, trong khi `product/`, `vendor/` và `system_ext/`
được tìm trực tiếp dưới `<rom>`.

Khi port sang thiết bị mới, ba thứ cần kiểm tra trước tiên là:

1. `ro.product.property_source_order` — phân vùng nào thắng các key danh tính.
2. `build.prop` có kết thúc bằng `import` theo SKU hay không — nếu có thì file SKU cũng
   phải được patch.
3. Các dòng `no-class` / `no-method` của những rule có `apiRange` bao phủ mức API đích.
