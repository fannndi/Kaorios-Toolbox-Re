#!/sbin/sh
#
# Farewell Toolbox seed (rootless setup helper)
#
# Copies the stock files the app cannot read without root — the build.prop
# family, which is SELinux-restricted even for the shell — into the app's own
# external files directory. After this runs once, "Build Patch ZIP" in the app
# needs no root: the jars are readable directly and everything else comes from
# this seed.
#
# Nothing on the device is modified: every step is a read plus a copy into
# /data/media/0/Android/data/io.farewell.toolbox/files/seed/.
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

ui_print " " " " " "
ui_print "###################################################"
ui_print "###          Farewell Toolbox seed              ###"
ui_print "###   rootless setup: stock files -> app dir    ###"
ui_print "###################################################" " "

SEED="/data/media/0/Android/data/io.farewell.toolbox/files/seed"
LIST="$(unzip -p "$ZIPFILE" seed.txt 2>/dev/null | tr -d '\r')"
if [ -z "$LIST" ]; then
  ui_print "!! seed.txt missing or empty"
  exit 1
fi

mount_one() {
  mount -w "/$1" 2>/dev/null || mount -o remount,rw "/$1" 2>/dev/null
}

NEEDED="$(echo "$LIST" | cut -d/ -f1 | sort -u)"
ui_print ":: Mounting partitions (read is enough)..."
FAILED=""
for part in $NEEDED; do
  mount_one "$part" || FAILED="$FAILED /$part"
done

if [ -n "$FAILED" ]; then
  ui_print ":: Deep remount for:$FAILED"
  mkdir -p /tmp
  unzip -o "$ZIPFILE" "META-INF/com/ks/mount.sh" -p > /tmp/farewell-mount.sh 2>/dev/null
  chmod 0755 /tmp/farewell-mount.sh
  /tmp/farewell-mount.sh >/dev/null 2>&1
  rm -f /tmp/farewell-mount.sh
  if [ -f /tmp/farewell-mount.log ]; then
    while read -r line; do
      case "$line" in
        *"needs SystemRW"*)
          ui_print "!! $line"
          ui_print "!! Flash SystemRW/MakeRW once, then run this seed zip again."
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
  ui_print "!! Nothing was copied. Reboot TWRP and try again."
  exit 1
fi

ui_print ":: Copying stock files to the app seed..."
mkdir -p "$SEED"
copied=0
missing=""
for entry in $LIST; do
  src="/$entry"
  if [ ! -f "$src" ]; then
    missing="$missing /$entry"
    continue
  fi
  mkdir -p "$SEED/$(dirname "$entry")"
  if cp -af "$src" "$SEED/$entry" 2>/dev/null; then
    chmod 0644 "$SEED/$entry" 2>/dev/null
    copied=$((copied + 1))
  else
    missing="$missing /$entry"
  fi
done

# TWRP runs as root; the FUSE layer serves these to the app only with sane
# ownership/permissions, so normalize them before finishing.
chown -R 1023:1023 "$SEED" 2>/dev/null
chmod -R u=rwX,go=rX "$SEED" 2>/dev/null

ui_print ":: Seeded $copied file(s)."
if [ -n "$missing" ]; then
  ui_print "!! Not found:$missing"
fi
ui_print " " "Now open Farewell Toolbox and build the patch ZIP." " "
