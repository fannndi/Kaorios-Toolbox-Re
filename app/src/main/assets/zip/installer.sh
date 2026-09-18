#!/sbin/sh
#
# Farewell Toolbox installer (shell update-binary)
# Pattern based on the TWRP shell installer by agp2nd (UwuH addon),
# credits osm0sis@xda-developers & LeeGarGold.
#
# args: $1 = api version, $2 = output fd, $3 = zip path
#
OUTFD=/proc/self/fd/$2
ZIPFILE="$3"
# Test seam: a prefix for the target tree. Empty in TWRP; the guard test sets it
# to a temp dir to exercise the real sed against real prop files.
: "${TARGET_ROOT:=}"

ui_print() {
  while [ "$1" ]; do
    echo -e "ui_print $1
      ui_print" >> $OUTFD
    shift
  done
}

package_extract_file() {
  mkdir -p "$(dirname "$2")"
  unzip -o "$ZIPFILE" "$1" -p > "$2" 2>/dev/null
}

MANIFEST="$(unzip -p "$ZIPFILE" manifest.txt 2>/dev/null | tr -d '\r')"
meta() { echo "$MANIFEST" | grep -m1 "^# $1=" | cut -d= -f2-; }
ENTRIES="$(echo "$MANIFEST" | grep -v '^#' | grep -v '^$')"
FILES="$(echo "$ENTRIES" | awk '{print $1}')"
BACKUP="$(meta backup)"
STAMP="$(meta stamp)"
PROFILE="$(meta profile)"

if [ -z "$FILES" ]; then
  ui_print "!! manifest.txt missing or empty"
  exit 1
fi

ui_print " " " " " "
ui_print "###################################################"
if [ "$BACKUP" = "yes" ]; then
  ui_print "###            Farewell Toolbox patch           ###"
else
  ui_print "###            Farewell Toolbox restore         ###"
fi
ui_print "###   profile: ${PROFILE:-unknown}  stamp: ${STAMP:-unknown}"
ui_print "###################################################" " "

mount_one() {
  mount -w "/$1" 2>/dev/null || mount -o remount,rw "/$1" 2>/dev/null
}

NEEDED="$(echo "$FILES" | cut -d/ -f1 | sort -u)"
ui_print ":: Mounting partitions..."
FAILED=""
for part in $NEEDED; do
  mount_one "$part" || FAILED="$FAILED /$part"
done

if [ -n "$FAILED" ]; then
  ui_print ":: Deep remount for:$FAILED"
  package_extract_file "META-INF/com/ks/mount.sh" "/tmp/farewell-mount.sh"
  chmod 0755 /tmp/farewell-mount.sh
  /tmp/farewell-mount.sh >/dev/null 2>&1
  rm -f /tmp/farewell-mount.sh
  if [ -f /tmp/farewell-mount.log ]; then
    while read -r line; do
      case "$line" in
        *"needs SystemRW"*)
          ui_print "!! $line"
          ui_print "!! Flash SystemRW/MakeRW (recovery) once, then flash this zip again."
          ;;
      esac
    done < /tmp/farewell-mount.log
    rm -f /tmp/farewell-mount.log
  fi
  FAILED=""
  for part in $NEEDED; do
    mount_one "$part" || FAILED="$FAILED /$part"
  done
fi

if [ -n "$FAILED" ]; then
  ui_print "!! Could not mount:$FAILED"
  ui_print "!! If this is MIUI 13/14, those partitions may be EROFS: flash"
  ui_print "!! SystemRW/MakeRW first (it converts and resizes them), then retry."
  ui_print "!! Nothing was changed. Reboot TWRP and flash again."
  exit 1
fi

BACKUP_DIR="/data/media/0/Farewell/backup-${STAMP:-manual}"
if [ "$BACKUP" = "yes" ]; then
  ui_print ":: Backing up current files..."
  ui_print "   -> $BACKUP_DIR"
  mkdir -p "$BACKUP_DIR"
  for entry in $FILES; do
    [ -f "/$entry" ] || continue
    mkdir -p "$BACKUP_DIR/$(dirname "$entry")"
    cp -af "/$entry" "$BACKUP_DIR/$entry" 2>/dev/null
  done
  {
    echo '#!/sbin/sh'
    echo "# Farewell restore script for backup-${STAMP:-manual}"
    echo "# Run from TWRP terminal:  sh $BACKUP_DIR/restore.sh"
    for part in $NEEDED; do
      echo "mount -w /$part 2>/dev/null || mount -o remount,rw /$part 2>/dev/null"
    done
    for entry in $FILES; do
      echo "if [ -f \"$BACKUP_DIR/$entry\" ]; then mkdir -p \"/\$(dirname $entry)\"; cp -af \"$BACKUP_DIR/$entry\" \"/$entry\" && echo \"restored /$entry\"; fi"
    done
    echo 'rm -rf /data/system/package_cache /data/dalvik-cache/*'
    echo 'echo "Farewell restore done. Reboot."'
  } > "$BACKUP_DIR/restore.sh"
  chmod 0755 "$BACKUP_DIR/restore.sh"
fi

ui_print ":: Installing files..."
INSTALL_FAILED=""
for entry in $FILES; do
  mkdir -p "$(dirname "/$entry")"
  if ! unzip -o "$ZIPFILE" "$entry" -p > "/$entry" 2>/dev/null; then
    INSTALL_FAILED="$INSTALL_FAILED /$entry"
  fi
done

if [ -n "$INSTALL_FAILED" ]; then
  ui_print "!! Failed to write:$INSTALL_FAILED"
  ui_print "!! Nothing was deleted, flashing the stock ZIP restores everything."
  exit 1
fi

DELETES="$(echo "$MANIFEST" | grep -m1 '^# delete=' | cut -d= -f2- | tr ',' ' ')"
if [ -n "$DELETES" ]; then
  ui_print ":: Removing patch extras..."
  for entry in $DELETES; do
    [ -n "$entry" ] && rm -f "/$entry"
  done
fi

ui_print ":: Setting permissions..."
echo "$ENTRIES" | while read entry mode; do
  [ -n "$entry" ] || continue
  chown 0:0 "/$entry" 2>/dev/null || chown 0.0 "/$entry" 2>/dev/null
  chmod "${mode:-0644}" "/$entry" 2>/dev/null
done

# Privileged-app bootloop guard. On MIUI `ro.control_privapp_permissions` is
# declared in vendor/build.prop as `enforce`, which makes the platform refuse to
# boot a privileged package whose allowlist does not list every permission it
# requests. The patched prop map already writes `log`, but this checks the files
# that were just installed and fixes any survivor, in place, so the value that
# actually boots is never `enforce`. Only runs for patch-style flashes
# (backup=yes): a restore zip must stay byte-honest to the backup it came from,
# and it keeps the allowlist XML instead (see manifestFor), so `enforce` is safe
# there.
if [ "$BACKUP" = "yes" ]; then
  for entry in $FILES; do
    case "$entry" in
      */build.prop|*/default.prop) ;;
      *) continue ;;
    esac
    file="$TARGET_ROOT/$entry"
    if grep -q '^ro\.control_privapp_permissions=enforce' "$file" 2>/dev/null; then
      if sed -i 's/^ro\.control_privapp_permissions=enforce$/ro.control_privapp_permissions=log/' "$file" 2>/dev/null; then
        GUARD_NOTE="$GUARD_NOTE /$entry"
        ui_print ":: Privapp guard: /$entry enforce -> log"
      else
        ui_print "!! Privapp guard could not rewrite /$entry (still enforce)"
      fi
    fi
  done
fi

# Verify what was actually written. A flash can fail after the copy loop in ways
# the loop cannot see (a truncated stream, a filesystem that accepted less than
# the entry size), and a half-written boot jar is worse than a failed flash.
# Sizes are compared against the zip entry; on any mismatch the write step is
# rolled back: backed-up files are restored, files that did not exist before are
# removed, and the script exits non-zero.
ui_print ":: Verifying written files..."
VERIFY_FAILED=""
for entry in $FILES; do
  if [ ! -f "$TARGET_ROOT/$entry" ]; then
    VERIFY_FAILED="$VERIFY_FAILED /$entry"
    continue
  fi
  expected="$(unzip -p "$ZIPFILE" "$entry" 2>/dev/null | wc -c | tr -d ' ')"
  actual="$(wc -c < "$TARGET_ROOT/$entry" 2>/dev/null | tr -d ' ')"
  [ "$expected" = "$actual" ] || VERIFY_FAILED="$VERIFY_FAILED /$entry"
done

VERIFY_RESULT="OK"
if [ -n "$VERIFY_FAILED" ]; then
  VERIFY_RESULT="MISMATCH:$VERIFY_FAILED"
  ui_print "!! Size mismatch after writing:$VERIFY_FAILED"
  if [ "$BACKUP" = "yes" ] && [ -d "$BACKUP_DIR" ]; then
    ui_print ":: Rolling the write back from the backup..."
    for entry in $VERIFY_FAILED; do
      if [ -f "$BACKUP_DIR/$entry" ]; then
        mkdir -p "$(dirname "$TARGET_ROOT/$entry")"
        cp -af "$BACKUP_DIR/$entry" "$TARGET_ROOT/$entry" 2>/dev/null
      else
        # Did not exist before this flash: removing it is the rollback.
        rm -f "$TARGET_ROOT/$entry"
      fi
    done
    ui_print "!! Rolled back. Reboot TWRP and flash again."
  else
    ui_print "!! Nothing to roll back to (restore zip); flash the patch zip again."
  fi
  exit 1
else
  ui_print "   all $(echo "$FILES" | wc -w | tr -d ' ') file(s) match the zip"
fi

# Flash report: one file that answers "what did the flash actually do?" without
# a second device session.
REPORT_DIR="/data/media/0/Farewell"
REPORT="$REPORT_DIR/flash-${STAMP:-manual}.log"
mkdir -p "$REPORT_DIR"
{
  echo "Farewell flash report"
  echo "stamp=$STAMP"
  echo "profile=$PROFILE"
  echo "backup=$BACKUP"
  echo "backup_dir=$BACKUP_DIR"
  echo "files=$(echo "$FILES" | wc -w | tr -d ' ')"
  echo "verify=$VERIFY_RESULT"
  echo "privapp_guard=${GUARD_NOTE:-none}"
  if [ -f /tmp/farewell-mount.log ]; then
    echo "mount_notes:"
    sed 's/^/  /' /tmp/farewell-mount.log
  fi
  echo "entries:"
  for entry in $FILES; do
    echo "  $entry"
  done
} > "$REPORT" 2>/dev/null
ui_print ":: Flash report: $REPORT"

ui_print ":: Clearing caches..."
rm -rf /data/system/package_cache /data/dalvik-cache/*
rm -f /system_root/system/framework/boot-framework.vdex
rm -f /system_root/system/framework/arm/boot-framework.art \
      /system_root/system/framework/arm/boot-framework.oat \
      /system_root/system/framework/arm/boot-framework.vdex \
      /system_root/system/framework/arm64/boot-framework.art \
      /system_root/system/framework/arm64/boot-framework.oat \
      /system_root/system/framework/arm64/boot-framework.vdex
rm -f /system_root/system/framework/oat/arm/services.art \
      /system_root/system/framework/oat/arm/services.odex \
      /system_root/system/framework/oat/arm/services.vdex \
      /system_root/system/framework/oat/arm64/services.art \
      /system_root/system/framework/oat/arm64/services.odex \
      /system_root/system/framework/oat/arm64/services.vdex

if [ "$BACKUP" = "yes" ]; then
  ui_print " " "Backup: $BACKUP_DIR"
  ui_print "Restore: flash the Stock ZIP or run"
  ui_print "         sh $BACKUP_DIR/restore.sh"
else
  ui_print " " "Originals restored."
fi

umount /system_root /system_ext /product /vendor /odm 2>/dev/null
ui_print " " "                  DONE - reboot!" " "
