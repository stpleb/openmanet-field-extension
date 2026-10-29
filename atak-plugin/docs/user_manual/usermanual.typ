#import "@preview/polylux:0.4.0": *
#import "formatting.typ": *

#show: userguide.with(
   plugin-name: "ManetNode FE (Field Extension for OpenMANET)",
   plugin-version: "1.4",
   platform: "ATAK",
   platform-version: "5.7.0 / 5.8.0",
)


#tak-slide[
  = Overview
#toolbox.side-by-side(columns: (.75fr, 9fr))[
#image("plugin_icon.png", width: 70%)
][
 ManetNode FE controls OpenMANET nodes running the Field Extension node package from inside ATAK:
 the node's status light, PiSugar battery and power, mesh health, LoRa (Meshtastic) fallback and
 push-to-talk. Join a node's Wi-Fi and open the plugin from the Tools menu; it finds the node by itself.
 The full node web page (port 8787) opens from the plugin for everything else.
]
= Tabs
 - *PTT*: push-to-talk to the team over the mesh, in the same format as the ManetNode app.
 - *STATUS*: the node's light colour and what it means, battery and time left, mesh links
   (HaLow / 2.4 GHz), internet, LoRa and node health.
 - *LORA*: fallback mode (Auto / Always / Off), privacy profile (Open / Covert / Dark), team channel
   with share QR code, radios heard.
 - *SET*: node callsign and team, light and power settings (PiSugar auto power-on and long-press
   shutdown), position source, node markers on the map, clear fleet data, new-node setup code.
]
