# PortableTAK loader (optional part of the SD image)

With these three files on the node, plugging in a PortableTAK drive installs PortableTAK
on the node the first time (and updates it when a drive carries a newer release), then
starts it. Only node packages signed with the PortableTAK release key are accepted
(checked with OpenWrt's `usign`; key id c24087f4f016955e). Nothing runs unless a
PortableTAK drive is plugged in.

| Node path | Mode |
|---|---|
| /usr/sbin/portabletak-loader | 755 |
| /etc/hotplug.d/block/10-portabletak-loader | 644 |
| /etc/portabletak/loader.pub | 644 |

`build.sh` copies them into the image when this folder is present and lists them in
/etc/sysupgrade.conf. Source and updates: github.com/stpleb/portabletak (node-loader/).
PortableTAK itself also needs python3 (from the Python packages in pkgs/), chroot,
kmod-fs-ext4 and kmod-usb-storage, which OpenMANET 1.8 + this image already have.
