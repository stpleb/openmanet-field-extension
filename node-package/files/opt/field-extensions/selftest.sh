#!/bin/sh
# Field Extension 1:1.0.9 self-test (POSIX sh / busybox ash).
# Prints one plain-language line per check: PASS, FAIL, or SKIP (could not be checked).
# The same lines are saved in /tmp/field-extensions-selftest.txt.
# Exit status: the number of FAIL lines (0 = all good).
# FE_SELFTEST_WAIT: seconds to wait for a just-started service (default 20).

OUT=/tmp/field-extensions-selftest.txt
TMP="$OUT.$$"
WAIT="${FE_SELFTEST_WAIT:-20}"
FE_DIR=/opt/field-extensions
fails=0

say() { echo "$*"; echo "$*" >> "$TMP"; }
pass() { say "PASS  $*"; }
fail() { say "FAIL  $*"; fails=$((fails + 1)); }
skip() { say "SKIP  $*"; }

: > "$TMP"
say "Field Extension self-test (version 1:1.0.9, $(date '+%Y-%m-%d %H:%M'))"

# 1. Python
py=0
if command -v python3 >/dev/null 2>&1 &&
	python3 -c 'import json, ssl, ctypes, encodings.idna, urllib.request, http.server' >/dev/null 2>&1; then
	py=1
	pass "Python is installed."
elif command -v python3 >/dev/null 2>&1; then
	fail "Python is installed but some parts are missing. Install the Field Extension file again."
else
	fail "Python is missing, so Field Extension cannot run. Install the Field Extension file again (it fetches Python), or use the offline file."
fi

# 2. I2C bus (battery board connection)
if [ -c /dev/i2c-1 ]; then
	pass "Battery-board connection (I2C bus 1) is on."
else
	fail "Battery-board connection (I2C bus 1) is off, so the battery cannot be read."
fi

# 3. PiSugar battery board at address 0x57
if [ ! -c /dev/i2c-1 ]; then
	skip "Battery board not checked (no I2C bus)."
else
	pct=
	if command -v i2cget >/dev/null 2>&1; then
		v=$(i2cget -y 1 0x57 0x2a b 2>/dev/null)
		case "$v" in
		0x[0-9a-fA-F][0-9a-fA-F]) pct=$((v)) ;;
		esac
	elif [ "$py" = 1 ]; then
		pct=$(PYTHONPATH=$FE_DIR python3 -c 'from field_extension.pisugar_i2c import PiSugarI2C
s = PiSugarI2C().status()
print(s.get("battery_percent", "") if s.get("available") else "")' 2>/dev/null)
	fi
	if [ -n "$pct" ]; then
		pass "Battery board (PiSugar) answers. Battery ${pct}%."
	elif command -v i2cget >/dev/null 2>&1 || [ "$py" = 1 ]; then
		fail "Battery board (PiSugar) does not answer at address 0x57. This is normal only if this node has no PiSugar."
	else
		skip "Battery board not checked (no i2cget and no Python)."
	fi
fi

# 4. Service parts (procd instances)
instance_running() {
	ubus call service list '{"name":"field-extensions"}' 2>/dev/null |
		jsonfilter -e "@['field-extensions'].instances['$1'].running" 2>/dev/null | grep -q true
}
instance_pid() {
	ubus call service list '{"name":"field-extensions"}' 2>/dev/null |
		jsonfilter -e "@['field-extensions'].instances['$1'].pid" 2>/dev/null
}
label() {
	case "$1" in
	instance1) echo "Web page and API" ;;
	led) echo "Status light driver" ;;
	eud) echo "Phone internet helper" ;;
	lora) echo "LoRa service" ;;
	recovery) echo "Self-recovery" ;;
	esac
}
if ! command -v ubus >/dev/null 2>&1; then
	skip "Service not checked (no ubus)."
else
	t=0
	while :; do
		missing=
		for i in instance1 led eud lora recovery; do
			instance_running "$i" || missing="$missing $i"
		done
		[ -z "$missing" ] || [ "$t" -ge "$WAIT" ] || [ "$py" = 0 ] && break
		sleep 2
		t=$((t + 2))
	done
	# A part that crashes on start looks "running" between restarts, so check that each
	# one keeps the same process for a few seconds.
	pids=
	crashing=
	for i in instance1 led eud lora recovery; do pids="$pids $i=$(instance_pid "$i")"; done
	[ "$py" = 0 ] || sleep 6
	for i in instance1 led eud lora recovery; do
		case " $pids " in *" $i= "*) ;; *" $i=$(instance_pid "$i") "*) ;; *) crashing="$crashing $i" ;; esac
	done
	for i in instance1 led eud lora recovery; do
		case " $missing " in
		*" $i "*)
			fail "$(label $i) is not running."
			;;
		*)
			case " $crashing " in
			*" $i "*) fail "$(label $i) keeps restarting. See: logread -e field-extensions" ;;
			*) pass "$(label $i) is running." ;;
			esac
			;;
		esac
	done
fi

# 4b. PiSugar button long press (soft shutdown) and the power-off step
if [ -c /dev/i2c-1 ] && [ "$py" = 1 ]; then
	mode=$(PYTHONPATH=$FE_DIR python3 -m field_extension.pisugar_i2c soft-shutdown 2>/dev/null)
	want=$(cat /etc/field-extensions/state/long-press-shutdown 2>/dev/null || echo 1)
	case "$mode" in
	on) pass "Button long press: clean shutdown (PiSugar soft-shutdown mode on)." ;;
	off) if [ "$want" = 0 ]; then pass "Button long press: cuts the power (set that way in Maintenance)."
		else skip "Button long press: PiSugar not switched to clean shutdown yet (the light driver does it within 10 s)."; fi ;;
	*) skip "Button long press mode not checked (PiSugar did not answer)." ;;
	esac
	if ls /etc/rc.d/K*field-power-off >/dev/null 2>&1; then
		pass "Power-off step is on (the node turns fully off after a Field Extension shutdown)."
	else
		fail "Power-off step is off. Run: /etc/init.d/field-power-off enable"
	fi
fi

# 5. Web page answers with JSON
addr="$(uci -q get network.ahwlan.ipaddr 2>/dev/null)"
[ -n "$addr" ] || addr="$(uci -q get network.lan.ipaddr 2>/dev/null)"
addr="${addr%%/*}"
[ -n "$addr" ] || addr=127.0.0.1
url="http://$addr:8787/api/field/summary"
fetch_json() {
	if [ "$py" = 1 ]; then
		python3 -c 'import json, sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=5) as r:
    assert isinstance(json.load(r), dict)' "$url" >/dev/null 2>&1
	else
		wget -q -T 5 -O - "$url" 2>/dev/null | grep -q '^{'
	fi
}
t=0
until fetch_json; do
	if [ "$t" -ge "$WAIT" ] || [ "$py" = 0 ]; then
		break
	fi
	sleep 2
	t=$((t + 2))
done
if fetch_json; then
	pass "Field Extension page answers at http://$addr:8787/"
else
	fail "Field Extension page does not answer at http://$addr:8787/"
fi
if [ "$py" = 1 ]; then
	if python3 -c 'import json, sys, urllib.request
with urllib.request.urlopen(sys.argv[1], timeout=5) as r:
    assert "active" in json.load(r)["led"]["maintenance"]' "$url" >/dev/null 2>&1; then
		pass "Status light reports maintenance (white light) state."
	else
		fail "Status light does not report its maintenance state. Install the Field Extension file again."
	fi
fi

# 6. HaLow mesh settings that must match on every node (lessons from Node02/Node01, 2026-10-12)
# FE_HALOW_PRIM_INDEX: the fleet's 1 MHz primary channel index (stock OpenMANET 1.8 = 1).
want_idx="${FE_HALOW_PRIM_INDEX:-1}"
if ! command -v uci >/dev/null 2>&1; then
	skip "HaLow mesh settings not checked (no uci)."
else
	mradio=
	for r in $(uci -q show wireless | sed -n "s/^wireless\.\([^.=]*\)=wifi-device$/\1/p"); do
		[ "$(uci -q get wireless.$r.type)" = morse ] && mradio=$r && break
	done
	miface=
	for s in $(uci -q show wireless | sed -n "s/^wireless\.\([^.=]*\)=wifi-iface$/\1/p"); do
		[ -n "$mradio" ] && [ "$(uci -q get wireless.$s.device)" = "$mradio" ] &&
			[ "$(uci -q get wireless.$s.mode)" = mesh ] && miface=$s && break
	done
	if [ -z "$mradio" ]; then
		skip "HaLow mesh not checked (no HaLow radio set up)."
	elif [ -z "$miface" ]; then
		fail "HaLow radio has no mesh set up, so this node cannot join the HaLow mesh."
	else
		if [ "$(uci -q get wireless.$miface.disabled)" = 1 ]; then
			fail "HaLow mesh is turned off. In LuCI: Network > Wireless > enable the HaLow mesh."
		fi
		onbat=
		for n in $(uci -q get wireless.$miface.network); do
			[ "$(uci -q get network.$n.proto)" = batadv_hardif ] && onbat=$n
		done
		if [ -n "$onbat" ]; then
			pass "HaLow mesh carries mesh traffic (network $onbat)."
		else
			fail "HaLow mesh is not on the mesh network (batmesh0), so linked nodes cannot pass traffic. In LuCI: edit the HaLow mesh, Network: tick batmesh0 only."
		fi
		idx=$(uci -q get wireless.$mradio.s1g_prim_1mhz_chan_index)
		[ -n "$idx" ] || idx=$(uci -q get wireless.$miface.s1g_prim_1mhz_chan_index)
		if [ -z "$idx" ]; then
			skip "HaLow primary channel index not set in config (radio default in use)."
		elif [ "$idx" = "$want_idx" ]; then
			pass "HaLow primary channel index is $idx (matches the other nodes)."
		else
			fail "HaLow primary channel index is $idx but the other nodes use $want_idx, so they cannot hear each other."
		fi
		if [ -n "$(uci -q get wireless.$miface.sae_pwe)" ]; then
			skip "HaLow mesh still has an old compatibility setting (sae_pwe). Stock OpenMANET nodes do not need it."
		fi
	fi
fi

# 7. Temporary mesh join waiting to switch back
jf=/etc/field-extensions/state/mesh-join.json
if [ -f "$jf" ]; then
	due=$(sed -n 's/.*"until": *\([0-9]*\).*/\1/p' "$jf")
	from=$(sed -n 's/.*"from_mesh_id": *"\([^"]*\)".*/\1/p' "$jf")
	when=$(date -d "@$due" '+%Y-%m-%d %H:%M' 2>/dev/null || echo "$due")
	pass "Temporary mesh join: switches back to mesh $from at $when (keep it from the app's MESH tab)."
fi

if [ "$fails" -eq 0 ]; then
	say "RESULT: all checks passed."
else
	say "RESULT: $fails problem(s) found. See the FAIL lines above."
fi
mv -f "$TMP" "$OUT"
exit "$fails"
