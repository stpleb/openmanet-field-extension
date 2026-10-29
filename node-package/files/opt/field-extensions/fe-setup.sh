#!/bin/sh
# Field Extension 1:1.0.9 setup helper (POSIX sh / busybox ash).
# Used by the package scripts and by the boot hook in /etc/rc.local.
#   fe-setup.sh boot             every boot: keep-files list, red-light setting, Python
#                                (reinstalled after a firmware update), service on, self-test
#   fe-setup.sh after-install    background job from postinst when Python is still missing
#   fe-setup.sh python-ok        exit 0 when python3 and every module Field Extension needs work
#   fe-setup.sh install-python   from /opt/field-extensions/pkgs (offline copy), else the internet
#   fe-setup.sh usb-wifi         driver for a Ralink 2.4 GHz mesh stick, if one is plugged in
#   fe-setup.sh config-txt | sysupgrade-conf | rc-local | remove-hooks
# FE_ROOT (tests only) prefixes the files this script edits.

R="${FE_ROOT:-}"
FE_DIR=/opt/field-extensions
INIT=/etc/init.d/field-extensions
PKG_DIR="$R$FE_DIR/pkgs"
PY_PACKAGES="python3-light python3-urllib python3-email python3-ctypes python3-codecs python3-openssl"
LOG="${FE_SETUP_LOG:-/tmp/field-extensions-setup.log}"
RC_TAG='# field-extensions boot hook'
KEEP_PATHS='/opt/field-extensions/ /etc/init.d/field-extensions /etc/rc.d/S99field-extensions /etc/init.d/field-power-off /etc/rc.d/S99field-power-off /etc/rc.d/K95field-power-off /etc/field-extensions/ /etc/rc.local'

end_newline() {
	# so an appended line does not join a last line that has no newline
	[ -s "$1" ] && [ -n "$(tail -c 1 "$1")" ] && echo >> "$1"
	return 0
}

log() {
	echo "$*"
	echo "$(date '+%Y-%m-%d %H:%M:%S') $*" >> "$LOG" 2>/dev/null
	[ -n "$R" ] || logger -t field-extensions "$*" 2>/dev/null
	return 0
}

python_ok() {
	command -v python3 >/dev/null 2>&1 || return 1
	python3 -c 'import json, ssl, ctypes, encodings.idna, urllib.request, http.server' >/dev/null 2>&1
}

wait_for_opkg() {
	# A postinst runs while opkg holds its lock, so wait until that opkg has exited.
	n=0
	while pidof opkg >/dev/null 2>&1; do
		n=$((n + 1))
		[ "$n" -gt 300 ] && return 1
		sleep 2
	done
	return 0
}

install_python() {
	python_ok && return 0
	if ! wait_for_opkg; then
		log "FAIL  Another software install is still running. Try again in a few minutes."
		return 1
	fi
	if ls "$PKG_DIR"/*.ipk >/dev/null 2>&1; then
		log "Installing Python from the copy kept on this node..."
		opkg install "$PKG_DIR"/*.ipk >> "$LOG" 2>&1
		if python_ok; then
			log "PASS  Python installed from the copy kept on this node."
			return 0
		fi
		log "The kept copy of Python did not work; trying the internet."
	fi
	log "Installing Python from the internet..."
	opkg update >> "$LOG" 2>&1
	# shellcheck disable=SC2086
	opkg install $PY_PACKAGES >> "$LOG" 2>&1
	if python_ok; then
		log "PASS  Python installed from the internet."
		return 0
	fi
	log "FAIL  Python could not be installed. Connect the node to the internet and install the file again, or use the offline installer file."
	return 1
}

usb_wifi() {
	# A Ralink RT5370-family stick (the 2.4 GHz mesh dongle) needs kmod-rt2800-usb, which
	# stock OpenMANET 1.8 lacks. Install it (kept copy first, then the internet) and let
	# `wifi config` add the radio section, disabled, ready for a mesh join. No Wi-Fi restart.
	found=
	for v in "$R"/sys/bus/usb/devices/*/idVendor; do
		[ "$(cat "$v" 2>/dev/null)" = 148f ] && found=1
	done
	[ -n "$found" ] || return 0
	[ -d "$R/sys/module/rt2800usb" ] && return 0
	wait_for_opkg || return 1
	if ls "$PKG_DIR"/kmod-*.ipk >/dev/null 2>&1; then
		opkg install "$PKG_DIR"/kmod-*.ipk >> "$LOG" 2>&1
	fi
	if ! opkg list-installed 2>/dev/null | grep -q '^kmod-rt2800-usb '; then
		opkg update >> "$LOG" 2>&1
		opkg install kmod-rt2800-usb >> "$LOG" 2>&1
	fi
	if opkg list-installed 2>/dev/null | grep -q '^kmod-rt2800-usb '; then
		modprobe rt2800usb >> "$LOG" 2>&1
		sleep 3
		wifi config >> "$LOG" 2>&1
		log "PASS  2.4 GHz mesh stick driver installed."
	else
		log "FAIL  2.4 GHz mesh stick found but its driver (kmod-rt2800-usb) could not be installed. Connect the node to the internet and reboot."
	fi
	return 0
}

config_txt() {
	# Status light red from power-on: gpio 27 (red) high, 22 and 26 (green, blue) low.
	cfg=
	mnt=
	if [ -f "$R/boot/config.txt" ]; then
		cfg="$R/boot/config.txt"
	elif [ -z "$R" ]; then
		for dev in /dev/mmcblk0p1 /dev/sda1 /dev/nvme0n1p1; do
			[ -b "$dev" ] || continue
			m=/tmp/fe-boot.$$
			mkdir -p "$m"
			if mount -t vfat "$dev" "$m" 2>/dev/null; then
				if [ -f "$m/config.txt" ]; then
					cfg="$m/config.txt"
					mnt="$m"
					break
				fi
				umount "$m"
			fi
			rmdir "$m" 2>/dev/null
		done
	fi
	if [ -z "$cfg" ]; then
		log "SKIP  Boot settings file (config.txt) not found, so the red power-on light was not set."
		return 0
	fi
	need=
	tr -d '\r' < "$cfg" | grep -qx 'gpio=27=op,dh' || need="$need gpio=27=op,dh"
	tr -d '\r' < "$cfg" | grep -qx 'gpio=22,26=op,dl' || need="$need gpio=22,26=op,dl"
	if [ -z "$need" ]; then
		log "Red power-on light already set in the boot settings."
	elif { [ -f "$cfg.bak-fe" ] || cp -p "$cfg" "$cfg.bak-fe"; } &&
		{ printf '\n[all]\n# Field Extension: status light red from power-on\n'; for l in $need; do echo "$l"; done; } >> "$cfg"; then
		sync
		log "Red power-on light added to the boot settings (works from the next power-on)."
	else
		log "FAIL  Could not change the boot settings file $cfg."
	fi
	if [ -n "$mnt" ]; then
		umount "$mnt"
		rmdir "$mnt" 2>/dev/null
	fi
	return 0
}

sysupgrade_conf() {
	# Files listed here survive an OpenMANET firmware update ("keep settings").
	f="$R/etc/sysupgrade.conf"
	[ -f "$f" ] || : > "$f"
	end_newline "$f"
	for p in $KEEP_PATHS; do
		grep -qxF "$p" "$f" || echo "$p" >> "$f"
	done
}

rc_local() {
	# /etc/rc.local is kept by firmware updates; this line puts Python and the service back.
	f="$R/etc/rc.local"
	[ -f "$f" ] || printf '#!/bin/sh\n\nexit 0\n' > "$f"
	grep -qF "$RC_TAG" "$f" && return 0
	line="[ -x $FE_DIR/fe-setup.sh ] && $FE_DIR/fe-setup.sh boot </dev/null >/dev/null 2>&1 &  $RC_TAG"
	if grep -q '^exit 0' "$f"; then
		awk -v l="$line" '!done && /^exit 0/ { print l; done = 1 } { print }' "$f" > "$f.fe-new" &&
			cat "$f.fe-new" > "$f"
		rm -f "$f.fe-new"
	else
		end_newline "$f"
		echo "$line" >> "$f"
	fi
}

remove_hooks() {
	[ -f "$R/etc/rc.local" ] && sed -i '/field-extensions boot hook/d' "$R/etc/rc.local"
	[ -f "$R/etc/sysupgrade.conf" ] && sed -i -e '/^\/opt\/field-extensions\/$/d' \
		-e '/^\/etc\/init\.d\/field-extensions$/d' -e '/^\/etc\/rc\.d\/S99field-extensions$/d' \
		"$R/etc/sysupgrade.conf"
	return 0
}

keep_package() {
	# Keep the package this node was installed from, so other nodes can update from it
	# over the mesh (GET /api/field/package). $1, or the newest matching file in /tmp or /root.
	v=$(cat "$R$FE_DIR/VERSION" 2>/dev/null)
	fv=${v#*:}	# file names carry no opkg epoch
	f="$1"
	if [ -z "$f" ]; then
		f=$(ls -t "$R"/tmp/field-extensions_"$fv"_*.ipk "$R"/root/field-extensions_"$fv"_*.ipk 2>/dev/null | head -n 1)
	fi
	[ -n "$v" ] && [ -f "$f" ] || return 0
	cp "$f" "$R$FE_DIR/package.ipk.new" || return 0
	sha=$(sha256sum "$R$FE_DIR/package.ipk.new" | cut -d' ' -f1)
	size=$(wc -c < "$R$FE_DIR/package.ipk.new" | tr -d ' ')
	mv "$R$FE_DIR/package.ipk.new" "$R$FE_DIR/package.ipk"
	printf '{"version": "%s", "sha256": "%s", "size": %s}\n' "$v" "$sha" "$size" > "$R$FE_DIR/package.json"
}

selftest() {
	FE_SELFTEST_WAIT="${FE_SELFTEST_WAIT:-30}" "$FE_DIR/selftest.sh" >> "$LOG" 2>&1
}

case "$1" in
boot)
	sleep 5
	sysupgrade_conf
	rc_local
	config_txt
	usb_wifi
	if python_ok; then
		$INIT enable
		$INIT start
	elif install_python; then
		$INIT enable
		$INIT restart
	fi
	selftest
	;;
after-install)
	if install_python; then
		$INIT enable
		$INIT restart
	fi
	selftest
	;;
keep-package) keep_package "$2" ;;
python-ok) python_ok ;;
install-python) install_python ;;
usb-wifi) usb_wifi ;;
config-txt) config_txt ;;
sysupgrade-conf) sysupgrade_conf ;;
rc-local) rc_local ;;
remove-hooks) remove_hooks ;;
*)
	echo "usage: $0 boot|after-install|keep-package [ipk]|python-ok|install-python|usb-wifi|config-txt|sysupgrade-conf|rc-local|remove-hooks" >&2
	exit 2
	;;
esac
