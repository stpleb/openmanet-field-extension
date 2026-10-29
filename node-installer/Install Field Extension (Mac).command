#!/bin/bash
# Installs (or updates) the Field Extension on an OpenMANET node from this Mac.
# Double-click it. Needs: the computer on the node's Wi-Fi (or LAN), and the node's root password
# (the same one as the OpenMANET web page). You type the password; it is never stored.
cd "$(dirname "$0")"
echo "Field Extension installer"
echo "=========================="
IPK=$(ls -t field-extensions_*_all.ipk 2>/dev/null | head -1)
if [ -z "$IPK" ]; then echo "No field-extensions_*.ipk file next to this installer."; read -p "Press Return to close."; exit 1; fi
echo "Package: $IPK"
read -p "Node address [10.41.0.1]: " NODE; NODE=${NODE:-10.41.0.1}
if ! ping -c1 -t3 "$NODE" >/dev/null 2>&1; then
  echo "Can't reach $NODE. Join the node's Wi-Fi (or plug into its LAN port) and try again."; read -p "Press Return to close."; exit 1
fi
SOCK="/tmp/fe-install-$$"
SSHOPT=(-o ControlMaster=auto -o ControlPath="$SOCK" -o ControlPersist=60 -o StrictHostKeyChecking=accept-new)
echo; echo "Connecting to root@$NODE. Type the node password when asked."
ssh "${SSHOPT[@]}" root@"$NODE" 'echo connected; opkg list-installed | grep field-extensions || echo "the Field Extension not installed yet"' || { echo "Could not sign in."; read -p "Press Return to close."; exit 1; }
echo; echo "Backing up the node's settings to this folder first..."
ssh "${SSHOPT[@]}" root@"$NODE" 'sysupgrade -b /tmp/pre-fe-install.tar.gz >/dev/null 2>&1 & S=$!; for i in $(seq 1 40); do sleep 1; kill -0 $S 2>/dev/null || break; P=$(pgrep -x flock); [ -n "$P" ] && [ $i -ge 5 ] && kill $P; done; ls /tmp/pre-fe-install.tar.gz' >/dev/null
scp -O "${SSHOPT[@]}" root@"$NODE":/tmp/pre-fe-install.tar.gz "./backup-$NODE-$(date +%Y%m%d-%H%M).tar.gz" && echo "Backup saved."
echo; echo "Installing $IPK ..."
scp -O "${SSHOPT[@]}" "$IPK" root@"$NODE":/tmp/fe.ipk
ssh "${SSHOPT[@]}" root@"$NODE" 'opkg install --force-reinstall /tmp/fe.ipk 2>&1 | tail -12; echo; sh /opt/field-extensions/selftest.sh'
ssh -o ControlPath="$SOCK" -O exit root@"$NODE" >/dev/null 2>&1
echo; echo "Done. Open http://$NODE:8787/ (or the ManetNode app) to see the Field Extension."
read -p "Press Return to close."
