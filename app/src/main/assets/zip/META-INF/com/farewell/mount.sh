#! /sbin/sh
e2fsck -f /dev/block/mapper/system
blockdev --setrw /dev/block/mapper/system
e2fsck -E unshare_blocks -y -f /dev/block/mapper/system
resize2fs /dev/block/mapper/system

e2fsck -f /dev/block/mapper/system_ext
blockdev --setrw /dev/block/mapper/system_ext
e2fsck -E unshare_blocks -y -f /dev/block/mapper/system_ext
resize2fs /dev/block/mapper/system_ext

e2fsck -f /dev/block/mapper/product
blockdev --setrw /dev/block/mapper/product
e2fsck -E unshare_blocks -y -f /dev/block/mapper/product
resize2fs /dev/block/mapper/product
   
e2fsck -f /dev/block/mapper/vendor
blockdev --setrw /dev/block/mapper/vendor
e2fsck -E unshare_blocks -y -f /dev/block/mapper/vendor
resize2fs /dev/block/mapper/vendor

mount -o ro -t auto /dev/block/mapper/system /system_root
blockdev --setrw /dev/block/mapper/system
mount -o rw,remount -t auto /system_root
mount -o ro -t auto /dev/block/mapper/system_ext /system_ext
blockdev --setrw /dev/block/mapper/system_ext
mount -o rw,remount -t auto /system_ext
mount -o ro -t auto /dev/block/mapper/product /product
blockdev --setrw /dev/block/mapper/product
mount -o rw,remount -t auto /product
mount -o ro -t auto /dev/block/mapper/vendor /vendor
blockdev --setrw /dev/block/mapper/vendor
mount -o rw,remount -t auto /vendor




#umount /system_root
#umount /system_ext
#umount /product
#umount /vendor


