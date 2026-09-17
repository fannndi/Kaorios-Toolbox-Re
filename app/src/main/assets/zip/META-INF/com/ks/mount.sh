#! /sbin/sh
# Farewell deep-remount helper.
#
# Makes a logical partition writable in place when the plain remount failed.
# Two things are checked before anything destructive runs:
#
#   1. The filesystem type, read from the on-disk magic. Only ext4 gets the
#      e2fsck/unshare_blocks/resize2fs treatment — running e2fsck on an EROFS
#      or F2FS partition is at best noise and at worst a corruption risk.
#   2. The device node, so a partition that does not exist on this ROM (MIUI 12
#      has no system_ext) is skipped instead of feeding e2fsck a missing file.
#
# EROFS/F2FS cannot be remounted rw in place at all: the image has to be
# converted and the super partition rebuilt, which is what SystemRW/MakeRW
# (lebigmac's sysrw) does from recovery or root. Those partitions are reported
# to /tmp/farewell-mount.log so installer.sh can tell the user exactly which
# partition needs that step, instead of a bare "mount failed".

BLK=/dev/block/mapper
PARTITIONS="system system_ext product vendor"
LOG=/tmp/farewell-mount.log

: > "$LOG"

# Prints ext4, erofs, f2fs or unknown for a block device.
fs_type() {
  dev="$1"
  ext4_magic="$(dd if="$dev" bs=1 skip=1080 count=2 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')"
  [ "$ext4_magic" = "53ef" ] && { echo ext4; return; }
  head_magic="$(dd if="$dev" bs=1 skip=1024 count=4 2>/dev/null | od -An -tx1 2>/dev/null | tr -d ' \n')"
  case "$head_magic" in
    e2e1f5e0*) echo erofs; return ;;
    1020f5f2*) echo f2fs; return ;;
  esac
  echo unknown
}

# Pass 1: convert ext4 partitions (grow into freed dedup blocks).
for part in $PARTITIONS; do
  dev="$BLK/$part"
  [ -e "$dev" ] || continue
  fs="$(fs_type "$dev")"
  if [ "$fs" = "ext4" ]; then
    e2fsck -f "$dev" >/dev/null 2>&1
    blockdev --setrw "$dev" 2>/dev/null
    e2fsck -E unshare_blocks -y -f "$dev" >/dev/null 2>&1
    resize2fs "$dev" >/dev/null 2>&1
    echo "$part ext4 converted" >> "$LOG"
  else
    echo "$part is $fs: cannot be remounted rw in place; needs SystemRW/MakeRW (super rebuild)" >> "$LOG"
  fi
done

# Pass 2: mount read-only, force the block device rw, remount rw.
for part in $PARTITIONS; do
  dev="$BLK/$part"
  [ -e "$dev" ] || continue
  case "$part" in
    system)     target=/system_root ;;
    *)          target=/$part ;;
  esac
  mount -o ro -t auto "$dev" "$target" 2>/dev/null
  blockdev --setrw "$dev" 2>/dev/null
  mount -o rw,remount -t auto "$target" 2>/dev/null
done
