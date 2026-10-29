#!/bin/sh
# Run on the node to fix (stock OpenMANET 1.8): ssh root@<node> 'sh -s' < fix-halow-mesh.sh
# Puts the node's HaLow mesh into batman (batmesh0) and sets the 1.8 default primary
# channel (1 MHz, index 1). Backs up first. Never prints keys.
set -e
ts=$(date +%s)
cp /etc/config/wireless /root/wireless.bak-fix-$ts
cp /etc/config/network /root/network.bak-fix-$ts
echo "backup: /root/wireless.bak-fix-$ts /root/network.bak-fix-$ts"

# Find the morse radio and its mesh iface
radio=$(uci show wireless | sed -n "s/^wireless\.\([^.]*\)\.type='morse'$/\1/p" | head -1)
[ -n "$radio" ] || { echo "no morse radio found"; exit 1; }
iface=$(uci show wireless | sed -n "s/^wireless\.\([^.]*\)\.device='$radio'$/\1/p" | while read s; do
  [ "$(uci -q get wireless.$s.mode)" = mesh ] && echo $s; done | head -1)
[ -n "$iface" ] || { echo "no HaLow mesh iface on $radio"; exit 1; }
echo "HaLow: radio=$radio iface=$iface"

# batman hard-interface sections (stock 1.8 has them; recreate if missing)
uci -q get network.bat0 >/dev/null || { echo "network.bat0 missing - stop"; exit 1; }
if ! uci -q get network.batmesh0 >/dev/null; then
  uci set network.batmesh0=interface
  uci set network.batmesh0.proto=batadv_hardif
  uci set network.batmesh0.master=bat0
  uci commit network
  echo "created network.batmesh0"
fi

uci set wireless.$iface.network=batmesh0
uci set wireless.$radio.s1g_prim_chwidth=1
uci set wireless.$radio.s1g_prim_1mhz_chan_index=1
uci -q delete wireless.$iface.sae_pwe || true
uci commit wireless

uci show wireless | grep -E "^wireless\.($radio|$iface)\." | grep -vE "\.(key|sae_password)="
/etc/init.d/network reload
wifi reload
echo "applied; check 'batctl n' in ~60 s"
