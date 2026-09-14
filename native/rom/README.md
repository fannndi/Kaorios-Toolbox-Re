# Farewell native daemon + stock-mode helper

Two ways to apply the native (non-Java) property layer:

| Mode | How | Needs ROM edit | Applies at |
| --- | --- | --- | --- |
| **Stock mode (default)** | Toolbox app streams `farewelld` to `/data/local/tmp/pfix` via root and runs it (`--once`) at boot and after PIF refresh | no | `BOOT_COMPLETED`, manual button, auto-refresh job |
| **ROM mode (optional)** | `farewelld` baked into `/system/bin` + init rc + SELinux rules from `native/rom/` | yes | `on late-init` (before zygote) |

Both write the same way: directly into the Android property areas (resetprop
technique), so native readers (`__system_property_get` inside DroidGuard, HALs,
init) see the spoofed values.

## What the flashable zips contain (built by the app)

Both zips use a **shell `update-binary`** (pattern based on the TWRP shell
installer by agp2nd / UwuH addon, credits osm0sis@xda-developers) instead of
edify, so the app can ship a dynamic `manifest.txt`:

```
# backup=yes|no
# stamp=<stamp>
# profile=<profile>
# delete=<comma separated paths>        (restore zip only)
system_root/system/framework/framework.jar 0644
product/build.prop 0644
...
```

`Farewell-Patch-<profile>-<stamp>.zip`
- `system_root/system/framework/framework.jar` (+ `services.jar`) - patched jars
- `system_root/system/build.prop`, `product/build.prop`, `vendor/build.prop`,
  `vendor/odm/etc/build.prop` - build.prop patch (PIF props, patch levels)
- `system_root/system/etc/farewell/props.conf` - config for the native helper/daemon
- `system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml`
- `system_root/system/framework/keystore.patch` - patch marker
- installer mounts **only the partitions used by the manifest** (surya: MIUI12 has
  system/product/vendor, MIUI13/14 adds system_ext; no `mi_ext`, `vendor/odm` is
  inside the vendor partition), using TWRP mounts first and the e2fsck
  `unshare_blocks` helper (`META-INF/com/ks/mount.sh`) as fallback
- **flash-time backup**: every file it is about to replace is copied to
  `/data/media/0/Farewell/backup-<stamp>/` (mirrored paths) together with a
  generated `restore.sh`; files that do not exist yet (props.conf, privapp XML)
  are not backed up
- sets `0:0 0644` per file, wipes package cache/dalvik and framework boot
  artifacts, aborts without touching anything if an entry cannot be written

`Farewell-Stock-<profile>-<stamp>.zip` (restore)
- same paths with the **original** contents captured by the app
- `# delete=` removes the privapp XML and `props.conf`, marker is reset to `stock`
- no flash-time backup is created

`native/tests/installer-test.sh` runs the installer end-to-end in a disposable
Linux/WSL environment (fake mount/unzip) and checks patch, backup, restore.sh,
stock restore, delete list and the abort path.

## Native helper protocol (stock mode)

1. `su -c 'mkdir -p /data/local/tmp/pfix && cat > /data/local/tmp/pfix/farewelld && chmod 0755 ...'` (binary piped over stdin)
2. `su -c 'cat > /data/local/tmp/pfix/props.conf'` (generated from PIF + boot overrides)
3. `su -c '/data/local/tmp/pfix/farewelld --once --config ... --verbose'`

The helper never creates properties; it updates **existing** ones only and skips
long properties / empty values. `sys.pfix_status` reports the result and can be
read with `getprop sys.pfix_status`.

## Building

```powershell
pwsh -File native/build.ps1              # arm64-v8a (copies to native/rom + app asset is manual)
pwsh -File native/build.ps1 -NoStrip     # keep symbols
pwsh -File native/build.ps1 -Shared      # also libfarewell.so (not used in Stage A)
```

The app asset lives at `app/src/main/assets/farewelld`; copy the built binary
there after rebuilding (`Copy-Item native/rom/system/bin/farewelld app/src/main/assets/farewelld`).

## ROM mode (optional, requires ROM access)

1. Copy `native/rom/system/*` into the unpacked ROM (see paths in the tree).
2. `pwsh -File native/verify-cil.ps1 -Rom <unpacked-ROM> -Apply` appends the
   SELinux fragment to `plat_sepolicy.cil` after validating every symbol.
3. Repack/flash. Delete `precompiled_sepolicy*` if a future ROM ships one.

## Verification

```bash
getprop sys.pfix_status                  # helper/daemon result
getprop ro.boot.verifiedbootstate        # green after helper runs
getprop ro.boot.flash.locked             # 1
logcat -s farewelld                      # daemon/helper log lines
```

## Sources

- `native/farewelld/` - C++17, no external deps, links libc/liblog only (static libc++).
- Property area protocol verified against bionic `android-10.0.0_r47` and
  `android-12.1.0_r27`: `prop_info` = serial(`len<<24 | counter`, dirty bit 0,
  `kLongFlag` = bit 16) + 92-byte value; header magic `"PROP"` at offset 8.
