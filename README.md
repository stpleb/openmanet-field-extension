# Field Extension for OpenMANET

Field add-ons for plain OpenMANET 1.8 nodes (Raspberry Pi 4 + Morse Micro HaLow): the status light,
PiSugar battery and power control, LoRa (Meshtastic) fallback for ATAK, phone internet, self-repair
and a node web page, plus an ATAK plugin and the ManetNode phone app to control it all from the field.
PortableTAK is a separate project.

**New here? Read the complete guide: [Field-Extension-for-OpenMANET-Guide.pdf](Field-Extension-for-OpenMANET-Guide.pdf)**
(parts and wiring, setting up nodes, every light pattern, the web page, plugin and app, LoRa, troubleshooting).

## Download (Releases)

Ready-to-use files are attached to the newest release (Releases, on the right):

| File | Use |
|---|---|
| `FieldExtension-OpenMANET-v1.0-SD-image-rpi4.img.gz` | **Easiest:** SD card image, stock OpenMANET 1.8 (Pi 4, HaLow MM6108 SPI) with the Field Extension already installed (plus the optional PortableTAK loader, idle unless a signed PortableTAK drive is plugged in) |
| `field-extensions_1.0.9_all.ipk` | Node package 1.0.9: install on an existing OpenMANET 1.8 node (see `node-installer/`) |
| `FieldExtension-OpenMANET-v1.0-ATAK-plugin-ATAK-5.7.0.apk`, `...-ATAK-5.8.0.apk` | ATAK plugin, signed by TAK.gov: installs on regular ATAK CIV 5.7 / 5.8 |
| `FieldExtension-OpenMANET-v1.0-ManetNode-Android.apk` | ManetNode Android app: team map (points, geofences, breadcrumbs), team chat, PTT, nodes, node settings and the Capabilities Test |
| `FieldExtension-OpenMANET-v1.0-ManetNode-Mac.zip` | Mac launcher that finds nodes on the mesh |
| `FieldExtension-OpenMANET-v1.0-field-card.pdf` | One-page field card for teammates |
| `Field-Extension-for-OpenMANET-Guide.pdf` | The complete guide (also in this repo's root) |
| `SHA256SUMS.txt` | Checksums for every file |

## Hardware

- Raspberry Pi 4 with a Morse Micro HaLow radio (MM6108 SPI), running OpenMANET 1.8
- Optional: RT5370 USB stick for a 2.4 GHz mesh link, PiSugar 3 / 3 Plus battery, RGB status light
  on GPIO, USB Meshtastic LoRa radio (e.g. Heltec V4), GPS
- Phones: Android with ATAK CIV 5.7 / 5.8 and/or the ManetNode app

## Status light

| Light | Meaning |
|---|---|
| Purple | Mesh + internet |
| Green | Mesh only (other nodes heard) |
| Cyan | Internet only: no mesh neighbours and no LoRa radio |
| Blue, slow blink | LoRa only: no mesh neighbours; the LoRa radio carries the team |
| Amber | No mesh, no internet, no LoRa |
| White | Maintenance: being updated, applying settings, running diagnostics or Maintenance mode on. Don't unplug |
| Red, solid | Starting up, or a fault |
| Red, quick blink | Shutting down cleanly |
| Red, pulse / fast blink | Battery below 20 % / critical (on battery) |
| Blue 2 s flash every ~10 s | GPS fix (over the colour above) |
| Green blinks every 10 s | Charging: one blink per 20 % of charge (over a steady colour) |

The full guide (`Field-Extension-for-OpenMANET-Guide.pdf`) explains every pattern with pictures.

## What the node package does

- **Status light** (RGB on GPIO R27 / G22 / B26): mesh, internet, LoRa-only and battery states at a glance.
- **Power** (PiSugar 3 / 3 Plus over I2C): battery percent with a calibrated curve, time left,
  auto power-on, safe low-battery shutdown, long-press clean shutdown that really powers off.
- **LoRa fallback**: ATAK positions and chat over a Meshtastic radio when the mesh is down;
  Open / Covert / Dark modes, team channel with share QR code, fleet-wide changes over LoRa.
- **Mesh health**: node list, neighbours per link (HaLow / 2.4 GHz), HaLow config checks,
  radio and peer watchdogs, reboot log, diagnostics bundle.
- **Node identity**: callsign, ATAK team colour and role, used for node alerts in ATAK chat.
- **Web page** on port 8787 (OpenMANET keeps 8080/8081/8087) and a JSON API under `/api/field/`.
- **Self-test**: `sh /opt/field-extensions/selftest.sh` prints PASS / FAIL per check.

## Layout

| Folder | Contents |
|---|---|
| `node-package/` | Node package contents: `files/` (installed paths: `/opt/field-extensions`, `/etc/init.d`) and `CONTROL/` (opkg control and install scripts) |
| `atak-plugin/` | ATAK plugin "ManetNode FE" 1.4 (ATAK CIV 5.7 / 5.8). Build notes in `README-build.md` |
| `launcher-apps/` | ManetNode launcher: Android, iOS, macOS and Windows |
| `node-installer/` | Double-click installer for Mac and Windows: backs up the node, installs the newest .ipk, runs the self-test |
| `field-card/` | Field card source (HTML) and PDF |
| `sd-image/` | Script that builds the ready-to-flash SD card image |
| `docs/` | Phone install steps, HaLow mesh fix script, the guide's source (`docs/guide/`) |

## ManetNode app

- **Map**: nodes (coloured like their status light), team members, mesh links, address and MGRS of the crosshair.
- **Points and geofences**: long-press the map or use the + button. Shared with every ManetNode user on
  the mesh, and with ATAK when "Share to ATAK" is on (Settings → Me). Geofences alert when someone
  enters or leaves.
- **Breadcrumbs**: trails for you and the team; save your own as KML.
- **Chat and PTT**: ATAK's All Chat Rooms and push-to-talk over the mesh (LoRa when the mesh is down).
- **Capabilities Test** (Settings): walk away from a node and record how far 2.4 GHz, HaLow and LoRa
  reach; the track is saved as KML and CSV.

## Set up a new node (SD card image)

1. Flash `FieldExtension-OpenMANET-v1.0-SD-image-rpi4.img.gz` to a microSD card with Raspberry Pi Imager ("Use custom") or balenaEtcher.
2. Boot the Pi 4 and finish OpenMANET's own first-boot setup (password, region).
3. On an existing node: Node Settings → Add a new node → Show code. On the new node's Wi-Fi, in
   ManetNode: Node Settings → Add a new node → Set up → scan it. The node joins the mesh and LoRa
   team channel, takes its callsign and reboots.

## Install on an existing node

1. Put the `.ipk` from the release into `node-installer/`.
2. Join the node's Wi-Fi, then run `Install Field Extension (Mac).command` or the Windows `.ps1`.
3. Open `http://<node address>:8787/`.

Stock OpenMANET 1.8 has no Python; the installer needs the node's Python packages (`python3-light`
and friends, OpenWrt 24.10 aarch64) either from another node or from the OpenWrt feed.

## Notes

- HaLow mesh between nodes: same mesh ID, channel, width, key, 1 MHz primary channel **index 1**
  (the 1.8 default), and the HaLow interface on network `batmesh0`. The self-test checks these.
- RT5370 USB dongles as 2.4 GHz mesh: use HT20; if the link peers but carries no data, set
  `rt2800usb nohwcrypt=1` in `/etc/modules.d/rt2800-usb` and reboot.
- Never restart Wi-Fi remotely on a node (`wifi down/up`): reboot instead.

Third-party software keeps its own license: ATAK CIV SDK (TAK Product Center terms), OpenMANET /
OpenWrt (GPL-2.0), Meshtastic protobuf definitions (GPL-3.0).
