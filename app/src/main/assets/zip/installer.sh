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
