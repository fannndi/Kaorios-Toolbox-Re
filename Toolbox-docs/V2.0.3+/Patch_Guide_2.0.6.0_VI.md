# Kaorios Toolbox Framework 2.0.6.0

[English](Patch_Guide_2.0.6.0.md) | **Tiếng Việt**


> Giữ nguyên các file JAR stock. Không thay thế DEX stock hoặc sao chép toàn bộ class từ template sang một ROM khác.

## 1. `framework.jar`

### A. Khởi tạo cho từng ứng dụng

**Class:**
```smali
Landroid/app/Instrumentation;
```

**Smali mẫu:** [`Instrumentation.smali`](../Template/Template_V2060/framework/Instrumentation.smali)

**Method:**
```smali
 newApplication(Ljava/lang/Class;Landroid/content/Context;)Landroid/app/Application;
```

Trước dòng:
```smali
return-object xY
    .end method
```

Thêm:
```smali
invoke-static {p1}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V
```

**Method:**
```smali
 newApplication(Ljava/lang/ClassLoader;Ljava/lang/String;Landroid/content/Context;)Landroid/app/Application;
```

Trước dòng:
```smali
return-object xY
    .end method
```

Thêm:
```smali
invoke-static {p3}, Landroid/security/kaorios/KaoriosHook;->initContext(Landroid/content/Context;)V
```

---

### B. Hook các tính năng hệ thống

**Class:**
```smali
Landroid/app/ApplicationPackageManager;
```

**Smali mẫu:** [`ApplicationPackageManager.smali`](../Template/Template_V2060/framework/ApplicationPackageManager.smali)

**Method:** 
```smali
 hasSystemFeature(Ljava/lang/String;I)Z
```

Thêm đoạn code sau ngay bên dưới `.registers X`:
```smali
invoke-static {p1, p2}, Landroid/security/kaorios/KaoriosHook;->hasSystemFeature(Ljava/lang/String;I)Ljava/lang/Boolean;
move-result-object v0

if-eqz v0, :cond_kaorios_feature_stock
invoke-virtual {v0}, Ljava/lang/Boolean;->booleanValue()Z
move-result v0
return v0

:cond_kaorios_feature_stock
```

---

### C. Hook quá trình tạo software key

**Class:**
```smali
Landroid/security/keystore2/AndroidKeyStoreKeyPairGeneratorSpi;
```

**Smali mẫu:** [`AndroidKeyStoreKeyPairGeneratorSpi.smali`](../Template/Template_V2060/framework/AndroidKeyStoreKeyPairGeneratorSpi.smali)

**Method:**
```smali
 generateKeyPair()Ljava/security/KeyPair;
```

Thêm đoạn code sau ngay bên dưới `.registers X`:
```smali
invoke-static {p0}, Landroid/security/kaorios/KaoriosHook;->initGenerateSoftwareKeyPair(Ljava/lang/Object;)Ljava/security/KeyPair;
move-result-object vX

if-eqz vX, :cond_kaorios_gen_stock
return-object vX

:cond_kaorios_gen_stock
```

Trong method này, cần chú ý đến `.registers X`.

- Tăng số lượng register hiện tại thêm `1`
- Thay `vX` bằng số register tại vị trí `registers - 2`

Ví dụ:

- Nếu method ban đầu sử dụng `15` registers
- Đổi thành `16` registers
- Sau đó đổi `vX` thành `v14`

---

### D. Hook chuỗi chứng chỉ

**Class:**
```smali
Landroid/security/keystore2/AndroidKeyStoreSpi;
```

**Smali mẫu:** [`AndroidKeyStoreSpi.smali`](../Template/Template_V2060/framework/AndroidKeyStoreSpi.smali)

**Method:**
```smali
 engineGetCertificateChain(Ljava/lang/String;)[Ljava/security/cert/Certificate;
```

Trước lệnh return cuối cùng, truyền `Certificate[]` cuối cùng qua Kaorios:

Tìm đoạn:

```smali
const/4 vA, 0x0
aput-object vB, vC, vA
return-object vD
```

Bên dưới dòng:
```smali
const/4 vA, 0x0
aput-object vB, vC, vA
```

Thêm:

```smali
invoke-static {vC}, Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
move-result-object vD
# return-object vD
```

#### Lưu ý

`move-result-object vD` là giá trị được trả về bởi `return-object vD`.

Ngoài ra, trong `invoke-static {vC}`, register mảng `vC` phải là cùng register được sử dụng trong `aput-object vB, vC, vA`.

#### Ví dụ

```smali
const/4 v4, 0x0
aput-object v2, v3, v4

invoke-static {v3}, Landroid/security/kaorios/KaoriosHook;->CertificateChainIfNeeded([Ljava/security/cert/Certificate;)[Ljava/security/cert/Certificate;
move-result-object v3

return-object v3
```

---

## 2. `services.jar`

**Class:**
```smali
Lcom/android/server/SystemServer;
```

**Smali mẫu:** [`SystemServer.smali`](../Template/Template_V2060/service/SystemServer.smali)

Trước dòng:
```smali
Lcom/android/server/SystemServer;->startOtherServices(Lcom/android/server/utils/TimingsTraceAndSlog;)V
```

Thêm:
```smali
invoke-static {}, Landroid/security/kaorios/KaoriosHook;->initSystemServer()V
```

---

## Ghi chú

- Android 17 / SDK 37 cũng yêu cầu [patch các field của Build](notes-a17.md).

## 3. Các patch bổ sung (thử nghiệm)

Các patch này là tùy chọn. Chỉ thêm tính năng bạn cần sau khi patch cốt lõi đã khởi động thành công.

### Ẩn trạng thái Tùy chọn nhà phát triển / ADB

**Class:** `Landroid/provider/Settings$NameValueCache;`  
**Smali mẫu:** [`Settings$NameValueCache.smali`](../Template/Template_V2060/framework/Settings$NameValueCache.smali)  
**Method:** `getStringForUser(Landroid/content/ContentResolver;Ljava/lang/String;I)Ljava/lang/String;`

Thêm đoạn code sau ngay bên dưới `.registers X`:

```smali
if-eqz p2, :cond_kaorios_dev_stock
invoke-static/range {p1 .. p3}, Landroid/security/kaorios/KaoriosHook;->shouldHideDevStatusFromNameValueCache(Landroid/content/ContentResolver;Ljava/lang/String;I)Z
move-result v0
if-eqz v0, :cond_kaorios_dev_stock
const-string v0, "0"
return-object v0

:cond_kaorios_dev_stock
```

Chỉ sử dụng overload trả về `String`; không chèn đoạn này vào overload trả về `Pair`.

### Ẩn ứng dụng đã cài đặt theo caller

Patch method lọc của Package Manager được ROM đích sử dụng. Tham chiếu trên Android 17: `AppsFilterBase.shouldFilterApplication(...)`.  
**Smali mẫu:** [`AppsFilterBase.smali`](../Template/Template_V2060/service/AppsFilterBase.smali)

```smali
# callingUid, null resolver, target package name, userId
invoke-static {vCallingUid, vNull, vTargetPackage, vUserId}, Landroid/security/kaorios/KaoriosHook;->shouldHideAppListForCaller(ILandroid/content/ContentResolver;Ljava/lang/String;I)Z
move-result vResult
if-eqz vResult, :cond_kaorios_hide_stock
const/4 v0, 0x1
return v0

:cond_kaorios_hide_stock
```

Thứ tự tham số là cố định: `callingUid, resolver, targetPackageName, userId`. Hãy xác định các register thực tế trong ROM của bạn; template chỉ dùng để tham khảo.

### Giả mạo nguồn cài đặt (Sắp có)

**Class tham chiếu:** `Lcom/android/server/pm/ComputerEngine;`  
**Smali mẫu:** [`ComputerEngine.smali`](../Template/Template_V2060/service/ComputerEngine.smali)  
**Method:** `getInstallerPackageName(Ljava/lang/String;I)Ljava/lang/String;`

Sau khi giá trị installer stock được xác định, truyền nó qua:

```smali
const/4 vNull, 0x0
invoke-static {vNull, vCallingUid, p2, p1, vInstaller}, Landroid/security/kaorios/KaoriosHook;->filterInstallerPackageName(Landroid/content/ContentResolver;IILjava/lang/String;Ljava/lang/String;)Ljava/lang/String;
move-result-object vInstaller
return-object vInstaller
```

Hãy xác định `calling UID`, `user ID`, package đang được truy vấn và giá trị installer stock trước khi điều chỉnh đoạn code này cho ROM của bạn.

### Lọc giá trị Settings theo app gọi

Patch này chỉ thay giá trị trả về cho app đang đọc Settings; không ghi hay thay
đổi setting thật. Hỗ trợ đúng ba tên bảng: `global`, `secure` và `system`.

**Vị trí patch:** dùng đường GET phía server của `SettingsProvider`, khi Binder
vẫn giữ caller gốc. Không chèn vào cache phía client như
`Settings$NameValueCache`, sau `clearCallingIdentity()`, hoặc method trả về
`Bundle`/object `Setting` thay vì `String` cuối cùng.

Tìm điểm ngay trước khi provider trả về `String` stock. `vNamespace` là tên
bảng, `vName` là key và `vValue` là giá trị gốc. `vNull` là một local register
còn trống, đã gán null.

```smali
# Xử lý rule xóa trước. Chỉ dùng khi ROM biểu diễn setting String không tồn tại
# bằng null.
const/4 vNull, 0x0
invoke-static {vNull, vNamespace, vName}, Landroid/security/kaorios/KaoriosHook;->shouldRemoveSetting(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;)Z
move-result vRemove
if-eqz vRemove, :cond_kaorios_setting_value
const/4 vValue, 0x0
return-object vValue

:cond_kaorios_setting_value
invoke-static {vNull, vNamespace, vName, vValue}, Landroid/security/kaorios/KaoriosHook;->filterSettingValue(Landroid/content/ContentResolver;Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;)Ljava/lang/String;
move-result-object vValue
return-object vValue
```

Tự đổi tên register và cách return “không có setting” theo ROM đích. Nếu method
còn cleanup sau khi tạo `vValue`, giữ nguyên cleanup và chỉ chèn hai hook trước
lệnh return thật.

#### Kiểm tra patch cho tính năng nâng cao

Yêu cầu dưới đây áp dụng cho các bản Toolbox/framework có probe kiểm tra patch
Advanced. Bản cũ chưa có probe không vượt qua kiểm tra, kể cả khi báo cùng
framework version.

1. Cài Toolbox APK và DEX framework tương thích, **có hỗ trợ probe**.
2. Patch đường GET thật của SettingsProvider cho **cả ba bảng**: `global`,
   `secure`, `system`. Gọi `shouldRemoveSetting` trước, rồi `filterSettingValue`
   nếu removal trả false, trên cùng provider thread như ví dụ bên trên.
3. Bao phủ cả key chưa tồn tại và giá trị đã có. Không để nhánh return sớm khi
   thiếu key bỏ qua hai hook. Giữ nguyên kiểm tra quyền, Binder caller identity
   và cleanup bắt buộc của ROM.
4. Reboot sau khi cập nhật framework/provider patch, mở lại Toolbox và đợi
   kiểm tra hoàn tất trước khi bật **tính năng nâng cao**.

App yêu cầu framework version không rỗng và gửi challenge mới, chỉ đọc, qua
Settings reader của từng bảng. Probe chỉ trả lời khi cả hai hook thấy cùng
namespace/key trên cùng thread. Probe chạy trước HMA/config reads, không cần
bật Advanced trước, không ghi setting thử và không tin working flag lưu sẵn.

| Trường hợp | Hành vi mong đợi |
|---|---|
| Đang kiểm tra hoặc đang ghi công tắc | Khóa công tắc |
| Framework thiếu/cũ, thiếu hook/bảng hoặc đọc probe lỗi | Advanced không khả dụng; hiện thông báo cần patch |
| Framework tương thích và probe đủ ba bảng pass | Cho thao tác công tắc; bật vẫn cần quyền ghi |
| Bật root fallback nhưng probe fail | Vẫn chặn bật trước mọi nhánh ghi fallback |
| Cờ Advanced lưu sẵn là ON nhưng probe fail | UI Advanced vẫn không khả dụng; thao tác đọc không sửa cờ đã lưu |
| Probe pass nhưng ghi thất bại | Giữ trạng thái công tắc trước đó; báo lỗi ghi |

Cả hai layout Settings dùng chung kiểm tra. App kiểm tra lại trước khi ghi giá
trị bật; `true`/`TRUE` và khoảng trắng theo quy tắc Java được hiểu thống nhất với
parser boolean của framework. Probe không chặn thao tác tắt tại API ghi, nhưng
quyền ghi/block policy vẫn áp dụng. Framework cũ/thiếu API sync cache không làm
một lần ghi trực tiếp vào Settings đã thành công bị báo thất bại.

Nếu công tắc vẫn khóa, kiểm tra framework đang chạy có probe, hai call site có
thực thi với key chưa tồn tại ở từng bảng và thiết bị đã reboot vào các file đã
patch. Không tạo sẵn probe key hoặc ép cờ Advanced để lách kiểm tra. Chỉ có
framework version hay root là chưa đủ. Probe xác nhận capability Settings,
không xác nhận hook AppsFilter/installer-source và không phải chứng thực bảo
mật chống hệ thống đã bị sửa/root.

#### Cấu hình trong Toolbox

Sau khi kiểm tra patch pass, bật **tính năng nâng cao**, rồi thêm entry trong Toolbox. Dữ liệu dùng format
version 2, tách từng app theo bảng:

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

Nhánh spoof Settings theo app giữ nguyên đầu vào khi Advanced tắt, bảng không
hợp lệ, caller là system/Toolbox, UID caller có nhiều package hoặc không có entry
khớp. Đầu vào này có thể đã bị HMA thay đổi: HMA lọc giá trị/xóa key riêng và
không dùng chung mọi guard. Không mặc định rằng tắt Advanced sẽ trả lại giá trị
stock cho các rule HMA đã có. Giá trị spoof theo app là chuỗi; xóa key do rule
Settings của HMA xử lý qua `shouldRemoveSetting(...)` ở trên.

Hãy test một app có cấu hình, một app không cấu hình, cả ba bảng và setting thiếu
trước khi phát hành. Không dùng hook này để vượt quyền hoặc thay đổi access check
của SettingsProvider.

Host regression kiểm tra probe/state reader và write gate bằng boundary
Android/provider giả lập; không chứng minh Binder identity, quyền, Compose hay
khả năng tương thích ROM thật. Trên ROM đích, cần thử thiếu/patch một phần, root
fallback khi chưa patch, cờ ON cũ, lỗi quyền ghi, bật/tắt nhanh và cả hai layout
Settings. Kiểm tra thêm GET bình thường khi config cache còn trống để phát hiện
đệ quy, và xác nhận probe không tạo key trong DB. Probe pass không thay thế một
lần kiểm tra runtime đầy đủ.

### Vô hiệu hóa `FLAG_SECURE`

[Hướng dẫn Disable Secure Flag](Disable_Secure_Flag.md).

### Vô hiệu hóa xác minh chữ ký

[Hướng dẫn CorePatch](CorePatch.md). (Có thể khác nhau tùy ROM)

Thư mục smali tham chiếu: [`Template/Template_V2060`](../Template/Template_V2060)
