#!/bin/sh
# Builds openmanet-1.8.0 + Field Extension SD image. Runs inside an arm64 Debian container.
set -e
apt-get update -qq >/dev/null && apt-get install -y -qq squashfs-tools xz-utils >/dev/null
mkdir -p /build && cp /w/stock.img /build/ && cp -r /w/pkgs /build/ && cd /build
OFF=$((147456*512))
USED=$(python3 -c "import struct;f=open('stock.img','rb');f.seek($OFF);print(struct.unpack('<Q',f.read(48)[40:48])[0])" 2>/dev/null || od -An -t u8 -j $((OFF+40)) -N 8 stock.img | tr -d ' ')
echo "stock squashfs bytes: $USED"
dd if=stock.img of=root.sqfs bs=512 skip=147456 status=none
rm -rf root && unsquashfs -q -ig -d root root.sqfs >/dev/null 2>&1 || true
rm -f root/dev/console
echo "files: $(find root | wc -l)"
# install packages natively (arm64) inside the image's own root
mkdir -p root/tmp/fe root/var/lock && cp pkgs/*.ipk root/tmp/fe/
mount -t proc proc root/proc; mount --bind /dev root/dev
chroot root /bin/sh -c 'cd /tmp/fe && opkg -o / install --nodeps $(ls *.ipk | grep -v field-extensions) >/tmp/opkg1.log 2>&1; opkg -o / install field-extensions_*.ipk >/tmp/opkg2.log 2>&1; echo "opkg rc=$?"; tail -3 /tmp/opkg2.log; opkg list-installed | grep -E "^(field-extensions|python3-light|python3-base) "'
# the package's own setup (skipped in an offline install): hooks + services on, no start
chroot root /bin/sh -c 'FE=/opt/field-extensions; chmod 0755 /etc/init.d/field-extensions /etc/init.d/field-power-off $FE/selftest.sh $FE/fe-setup.sh; mkdir -p /etc/field-extensions/state; $FE/fe-setup.sh sysupgrade-conf; $FE/fe-setup.sh rc-local; $FE/fe-setup.sh keep-package; $FE/fe-setup.sh python-ok && echo "python OK"; /etc/init.d/field-extensions enable; /etc/init.d/field-power-off enable; ls /etc/rc.d | grep -i field'
umount root/dev; umount root/proc
# PortableTAK loader (optional, see portabletak-loader/README.md): a PortableTAK drive plugged
# into the node installs/updates PortableTAK from the drive (signed packages only).
if [ -d /w/portabletak-loader ]; then
  L=/w/portabletak-loader
  install -D -m 755 $L/usr/sbin/portabletak-loader root/usr/sbin/portabletak-loader
  install -D -m 644 $L/etc/hotplug.d/block/10-portabletak-loader root/etc/hotplug.d/block/10-portabletak-loader
  install -D -m 644 $L/etc/portabletak/loader.pub root/etc/portabletak/loader.pub
  grep -qx /usr/sbin/portabletak-loader root/etc/sysupgrade.conf ||
    printf '/usr/sbin/portabletak-loader\n/etc/hotplug.d/block/10-portabletak-loader\n/etc/portabletak/\n' >> root/etc/sysupgrade.conf
  echo "PortableTAK loader added"
fi
# keep a copy of the offline packages on the node, like the installer does
mkdir -p root/opt/field-extensions/pkgs && cp pkgs/*.ipk pkgs/MANIFEST.txt root/opt/field-extensions/pkgs/ 2>/dev/null || true
rm -rf root/tmp/* root/var/lock/*
mksquashfs root new.sqfs -comp xz -b 262144 -noappend -all-root -no-xattrs -quiet -p 'dev/console c 600 0 0 5 1' >/dev/null
NEW=$(stat -c %s new.sqfs); echo "new squashfs bytes: $NEW"
head -c $OFF stock.img > out.img
cat new.sqfs >> out.img
# blank the area right after the system image so the first boot always makes a fresh overlay
dd if=/dev/zero bs=1M count=4 status=none >> out.img
echo "image bytes: $(stat -c %s out.img)"
cp out.img /w/out.img
unsquashfs -lln root.sqfs > /w/a.lst 2>/dev/null; unsquashfs -lln new.sqfs > /w/b.lst 2>/dev/null
