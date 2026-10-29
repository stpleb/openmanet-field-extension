# SD card image

`build.sh` turns the stock OpenMANET 1.8 Raspberry Pi 4 image
(`openmanet-1.8.0-rpi4-mm6108-spi-squashfs-sysupgrade.img`) into a ready-to-flash image with the
Field Extension node package and its Python already installed. Nothing node-specific goes in:
no keys, passwords, callsigns or mesh settings.

Build (arm64 Linux, e.g. Docker on an Apple-silicon Mac):

1. Put `stock.img` and a `pkgs/` folder (the `field-extensions_*.ipk` plus the Python `.ipk`s from a
   node's `/opt/field-extensions/pkgs/`) in one folder.
2. `docker run --rm --privileged -v "$PWD":/w debian:bookworm sh /w/build.sh`
3. The result is `out.img`; compress it with `gzip -9`.

The script unpacks the system image (squashfs), installs the packages natively with the image's own
`opkg`, runs the package's setup (boot hook, keep-files list, services enabled, nothing started),
repacks with the same settings (xz, 256 KiB blocks, all files root-owned) and blanks the area
after it so the first boot always creates a fresh overlay.

## Optional: PortableTAK loader

If a `portabletak-loader/` folder is next to `build.sh` (it is in this repo), the build also adds
the PortableTAK loader: plugging a PortableTAK drive into the node then installs or updates
PortableTAK from the drive (signed packages only) and starts it. See `portabletak-loader/README.md`.
Remove the folder to build without it.
