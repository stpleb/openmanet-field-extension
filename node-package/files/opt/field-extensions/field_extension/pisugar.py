"""PiSugar 3 module. Changes go through the supported pisugar-server text
protocol; the hardware is only *read* directly, to verify a change took.

The waterproof power switch is wired to the PiSugar itself, so power-on and
button behavior belong to the PiSugar (spec 19, 23, 24). No GPIO is used.
Open question for plain OpenMANET: pisugar-server has no OpenWrt package, so
it must be cross-built for aarch64 musl, or this module gets a direct-I2C
writer for CTR1 (needs the 0x0B write-protect unlock).

Protocol: one command per line on TCP 127.0.0.1:8423; each reply is a single
"name: value" line. Command names checked against pisugar-power-manager-rs
master (pisugar-server/src/cmds.rs), 2026-10-04.

Important: pisugar-server's "get auto_power_on" returns its *config file*
value, not the hardware. Verification therefore also reads the PiSugar 3
CTR1 register directly (I2C 0x57, register 0x02, bit 4 = power restore),
read-only, which is the register pisugar-server itself writes.
"""
import socket

from .i2c import read_registers

HOST, PORT = '127.0.0.1', 8423
TIMEOUT = 2.0

# Fixed read commands. Nothing here is built from user input.
READINGS = {
    'model': 'get model',
    'battery_percent': 'get battery',
    'battery_voltage': 'get battery_v',
    'battery_current': 'get battery_i',
    'charging': 'get battery_charging',
    'external_power': 'get battery_power_plugged',
    'charging_allowed': 'get battery_allow_charging',
    'auto_power_on': 'get auto_power_on',
}


def tcp_transport(command):
    """Send one fixed command and return the reply line, or None."""
    try:
        with socket.create_connection((HOST, PORT), timeout=TIMEOUT) as s:
            s.sendall(command.encode() + b'\n')
            data = b''
            while not data.endswith(b'\n') and len(data) < 4096:
                chunk = s.recv(1024)
                if not chunk:
                    break
                data += chunk
        return data.decode(errors='replace').strip()
    except OSError:
        return None


def parse(reply, expected_name):
    """Turn "name: value" into a Python value, or None when the reply is unusable."""
    if not reply or ':' not in reply:
        return None
    name, _, raw = reply.partition(':')
    name, raw = name.strip(), raw.strip()
    if name != expected_name:
        return None
    low = raw.lower()
    if low in ('true', 'false'):
        return low == 'true'
    try:
        number = float(raw)
        return int(number) if number.is_integer() else round(number, 3)
    except ValueError:
        return raw or None


I2C_BUS = '/dev/i2c-1'
P3_ADDRESS = 0x57
CTR1 = 0x02
RESTORE_BIT = 0x10  # CTR1 bit 4: power on automatically when a charger is connected

def read_ctr1(bus=I2C_BUS, address=P3_ADDRESS):
    """Read CTR1 twice (as pisugar-server does) and return it, or None."""
    first = read_registers(bus, address, CTR1)
    second = read_registers(bus, address, CTR1)
    if not first or not second or first != second:
        return None
    return first[0]


class PiSugar:
    def __init__(self, transport=tcp_transport, ctr1=read_ctr1):
        self.send = transport
        self.ctr1 = ctr1

    def hardware_auto_power_on(self):
        value = self.ctr1()
        return None if value is None else bool(value & RESTORE_BIT)

    def _get(self, command):
        return parse(self.send(command), command.split(' ', 1)[1])

    def status(self):
        model = self._get(READINGS['model'])
        if model is None:
            return {'available': False,
                    'message': 'PiSugar service not responding'}
        out = {'available': True}
        for key, command in READINGS.items():
            out[key] = self._get(command)
        out['auto_power_on_config'] = out.pop('auto_power_on')
        out['auto_power_on'] = self.hardware_auto_power_on()
        out['state'] = describe(out)
        return out

    def set_auto_power_on(self, enabled):
        """Apply the toggle, then read the hardware back to prove it took (spec 19)."""
        if not isinstance(enabled, bool):
            raise ValueError('enabled must be true or false')
        reply = self.send('set_auto_power_on ' + ('true' if enabled else 'false'))
        config = self._get(READINGS['auto_power_on'])   # persists across reboot
        hardware = self.hardware_auto_power_on()          # what the PiSugar will do
        return {
            'requested': enabled,
            'config': config,
            'hardware': hardware,
            'verified': config is enabled and hardware is enabled,
            'reply': reply if reply and len(reply) < 200 else None,
        }


def describe(s):
    """Plain-language power state for the field summary (spec 40, 41)."""
    plugged, charging, pct = s.get('external_power'), s.get('charging'), s.get('battery_percent')
    if plugged is True:
        source = 'External power, charging' if charging else 'External power'
    elif plugged is False:
        source = 'On battery'
    else:
        source = 'Power source unknown'
    if isinstance(pct, (int, float)):
        if pct < 10:
            return source + ', battery critical'
        if pct < 20:
            return source + ', battery low'
    return source
