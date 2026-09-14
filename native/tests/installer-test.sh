#!/bin/sh
# End-to-end test of the Farewell shell installer (shell update-binary).
# WARNING: only run inside a disposable Linux environment (WSL/container) as root:
# it fakes mount/umount/unzip and writes to /system_root, /data/media/0 and /tmp.
set -e

REPO=$(cd "$(dirname "$0")/../.." && pwd)
INSTALLER=$REPO/app/src/main/assets/zip/installer.sh
BASE=/tmp/fwtest
BIN=$BASE/bin
WORK=$BASE/work
OUT=$BASE/out

rm -rf "$BASE"
mkdir -p "$BIN" "$WORK" "$OUT"

cat > "$BIN/mount" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$BIN/umount" <<'EOF'
#!/bin/sh
exit 0
EOF
cat > "$BIN/unzip" <<'EOF'
#!/bin/sh
zip=""
entry=""
for a in "$@"; do
  case "$a" in
    -*) ;;
    *) if [ -z "$zip" ]; then zip="$a"; else entry="$a"; fi ;;
  esac
done
[ -n "$entry" ] || exit 0
python3 -c 'import sys,zipfile
z=zipfile.ZipFile(sys.argv[1])
sys.stdout.buffer.write(z.read(sys.argv[2]))' "$zip" "$entry"
EOF
chmod 755 "$BIN"/*
export PATH="$BIN:$PATH"

fail() { echo "FAIL: $1"; exit 1; }
pass() { echo "ok: $1"; }

make_zip() {
  # make_zip <zipfile> <manifest> <payloaddir> <installer>
  python3 - "$1" "$2" "$3" "$4" <<'PY'
import os, sys, zipfile
zipfile_path, manifest, payload, installer = sys.argv[1:5]
with zipfile.ZipFile(zipfile_path, "w", zipfile.ZIP_DEFLATED) as z:
    z.write(manifest, "manifest.txt")
    for root, _, files in os.walk(payload):
        for f in files:
            full = os.path.join(root, f)
            z.write(full, os.path.relpath(full, payload))
    z.write(installer, "META-INF/com/ks/mount.sh")
    z.write(installer, "META-INF/com/google/android/update-binary")
PY
}

run_installer() {
  # run_installer <zip> <logprefix>
  python3 - "$1" "$BASE/installer.sh" <<'PY'
import sys, zipfile
with zipfile.ZipFile(sys.argv[1]) as z:
    data = z.read("META-INF/com/google/android/update-binary")
open(sys.argv[2], "wb").write(data)
PY
  exec 9>"$OUT/$2.log"
  sh "$BASE/installer.sh" 3 9 "$1" || fail "installer exited non-zero ($2)"
  exec 9>&-
}

echo "=================== 1) patch zip ==================="
mkdir -p /system_root/system/framework /system_root/system/etc/farewell /system_root/system/etc/permissions
printf 'ORIGINAL-JAR\n' > /system_root/system/framework/framework.jar
printf 'ORIGINAL-PROP\n' > /system_root/system/build.prop
rm -rf /data/media/0/Farewell

mkdir -p "$WORK/payload/system_root/system/framework" \
         "$WORK/payload/system_root/system/etc/farewell" \
         "$WORK/payload/system_root/system/etc/permissions"
printf 'PATCHED-JAR\n' > "$WORK/payload/system_root/system/framework/framework.jar"
printf 'PATCHED-PROP\n' > "$WORK/payload/system_root/system/build.prop"
printf 'CONF\n' > "$WORK/payload/system_root/system/etc/farewell/props.conf"
printf 'XML\n' > "$WORK/payload/system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml"
printf 'ks2 marker\n' > "$WORK/payload/system_root/system/framework/keystore.patch"

cat > "$WORK/manifest.txt" <<'EOF'
# backup=yes
# stamp=TEST
# profile=surya-miui13
system_root/system/build.prop 0644
system_root/system/etc/farewell/props.conf 0644
system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml 0644
system_root/system/framework/framework.jar 0644
system_root/system/framework/keystore.patch 0644
EOF

make_zip "$WORK/patch.zip" "$WORK/manifest.txt" "$WORK/payload" "$INSTALLER"
run_installer "$WORK/patch.zip" patch

[ "$(cat /system_root/system/framework/framework.jar)" = "PATCHED-JAR" ] || fail "jar not patched"
pass "jar patched"
[ "$(cat /system_root/system/build.prop)" = "PATCHED-PROP" ] || fail "prop not patched"
pass "prop patched"
[ "$(cat /system_root/system/etc/farewell/props.conf)" = "CONF" ] || fail "props.conf missing"
pass "props.conf installed"
BK=/data/media/0/Farewell/backup-TEST
[ "$(cat $BK/system_root/system/framework/framework.jar)" = "ORIGINAL-JAR" ] || fail "jar backup wrong"
pass "jar backed up on flash"
[ "$(cat $BK/system_root/system/build.prop)" = "ORIGINAL-PROP" ] || fail "prop backup wrong"
pass "prop backed up on flash"
[ -f "$BK/system_root/system/etc/farewell/props.conf" ] && fail "props.conf should not be backed up (new file)"
pass "new files are not backed up"
[ -x "$BK/restore.sh" ] || fail "restore.sh missing"
pass "restore.sh generated"
grep -q "PATCHED" "$OUT/patch.log" && fail "log contains payload"
grep -q "Backup: $BK" "$OUT/patch.log" || fail "installer did not print backup path"
pass "installer output mentions backup path"

echo "=================== 2) restore.sh ==================="
sh "$BK/restore.sh" > "$OUT/restore.log" 2>&1
[ "$(cat /system_root/system/framework/framework.jar)" = "ORIGINAL-JAR" ] || fail "restore.sh did not restore jar"
pass "restore.sh restored jar"
[ "$(cat /system_root/system/build.prop)" = "ORIGINAL-PROP" ] || fail "restore.sh did not restore prop"
pass "restore.sh restored prop"

echo "=================== 3) stock zip ==================="
# leave the device patched again
printf 'PATCHED-JAR\n' > /system_root/system/framework/framework.jar
printf 'PATCHED-PROP\n' > /system_root/system/build.prop
printf 'CONF\n' > /system_root/system/etc/farewell/props.conf
printf 'XML\n' > /system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml
printf 'ks2 marker\n' > /system_root/system/framework/keystore.patch
rm -rf /data/media/0/Farewell

STOCK="$WORK/stock"
mkdir -p "$STOCK/payload/system_root/system/framework"
printf 'ORIGINAL-JAR\n' > "$STOCK/payload/system_root/system/framework/framework.jar"
printf 'ORIGINAL-PROP\n' > "$STOCK/payload/system_root/system/build.prop"
printf 'stock marker\n' > "$STOCK/payload/system_root/system/framework/keystore.patch"
cat > "$STOCK/manifest.txt" <<'EOF'
# backup=no
# stamp=STOCKTEST
# profile=surya-miui13
# delete=system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml,system_root/system/etc/farewell/props.conf
system_root/system/build.prop 0644
system_root/system/framework/framework.jar 0644
system_root/system/framework/keystore.patch 0644
EOF
make_zip "$STOCK/stock.zip" "$STOCK/manifest.txt" "$STOCK/payload" "$INSTALLER"
run_installer "$STOCK/stock.zip" stock

[ "$(cat /system_root/system/framework/framework.jar)" = "ORIGINAL-JAR" ] || fail "stock zip did not restore jar"
pass "stock zip restored jar"
[ "$(cat /system_root/system/build.prop)" = "ORIGINAL-PROP" ] || fail "stock zip did not restore prop"
pass "stock zip restored prop"
[ -f /system_root/system/etc/farewell/props.conf ] && fail "props.conf not deleted"
pass "props.conf deleted by stock zip"
[ -f /system_root/system/etc/permissions/privapp-permissions-io.farewell.toolbox.xml ] && fail "privapp xml not deleted"
pass "privapp XML deleted by stock zip"
[ -d /data/media/0/Farewell ] && fail "stock zip created a backup dir"
pass "stock zip does not create backups"

echo "=================== 4) failure path ==================="
# missing entry in zip must abort before touching anything
printf 'PATCHED-AGAIN\n' > "$WORK/manifest-bad.txt"
python3 - "$WORK/patch.zip" "$WORK/bad.zip" <<'PY'
import sys, zipfile, shutil
shutil.copy(sys.argv[1], sys.argv[2])
with zipfile.ZipFile(sys.argv[2], "a") as z:
    z.writestr("manifest.txt", "# backup=yes\n# stamp=BAD\nsystem_root/system/framework/missing.jar 0644\n")
PY
python3 - "$WORK/bad.zip" "$BASE/installer.sh" <<'PY'
import sys, zipfile
open(sys.argv[2], "wb").write(zipfile.ZipFile(sys.argv[1]).read("META-INF/com/google/android/update-binary"))
PY
exec 9>"$OUT/bad.log"
if sh "$BASE/installer.sh" 3 9 "$WORK/bad.zip"; then
  fail "installer should fail on unreadable entry"
fi
exec 9>&-
[ "$(cat /system_root/system/framework/framework.jar)" = "ORIGINAL-JAR" ] || fail "failed install modified the jar"
pass "failed install left files untouched"
grep -q "Failed to write" "$OUT/bad.log" || fail "missing failure message"
pass "failure message printed"

echo
echo "ALL INSTALLER TESTS PASSED"
