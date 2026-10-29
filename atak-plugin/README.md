# ManetNode FE: ATAK plugin

ATAK plugin for OpenMANET nodes running the Field Extension node package. Signed by TAK.gov as
version 1.4 for ATAK CIV 5.7.0 and 5.8.0 (the signed APKs are attached to the v1.0 release).

## Purpose and capabilities

Control and watch the nodes from inside ATAK, without a separate app:

- **PTT**: push-to-talk over the mesh (OpenMANET comms format, same as the ManetNode app).
- **STATUS**: status light colour and meaning, PiSugar battery and time left, mesh links per radio
  (HaLow / 2.4 GHz), internet, LoRa and node health.
- **LORA**: Meshtastic fallback mode and privacy profile, team channel with QR share, radios heard.
- **SET**: callsign and team, light and power settings, Maintenance mode (white light), position source, node markers and mesh
  lines on the ATAK map, clear fleet data, new-node setup code.
- A button opens the node's full Field Extension web page for in-depth information.

## Status

Released: v1.0 (plugin 1.4).

## Ports required

All on the node's local network (no internet needed):

| Port | Use |
|---|---|
| TCP 8787 | Field Extension API and web page on the node |
| UDP 239.2.3.1:6969 | ATAK SA (positions, node markers) |
| UDP 224.10.10.1:17012 | ATAK GeoChat |
| UDP multicast from the node's comms settings | Push-to-talk audio |

## Equipment required

An Android phone with ATAK CIV 5.7 or 5.8, joined to the Wi-Fi of an OpenMANET 1.8 node
(Raspberry Pi 4 + HaLow) with the Field Extension node package.

## Equipment supported

Status light (RGB on GPIO), PiSugar 3 / 3 Plus battery, USB Meshtastic LoRa radios
(for example Heltec V3 / V4, RAK), GPS on the node or the phone.

## Compilation

See `README-build.md`.

## Developer notes

Package `com.fieldext.plugin`. `FieldClient` talks to the node API (`/api/field/...`),
`FieldPanel` builds the tabs, `NodeMarkers` draws the nodes and mesh links on the ATAK map,
`PttTab` / `PttEngine` handle push-to-talk.
