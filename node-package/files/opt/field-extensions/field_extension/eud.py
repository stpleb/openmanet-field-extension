"""Phone (EUD) internet choice, the same behaviour as RC1 on Node02.

Modes, stored in <state>/eud-internet-mode:
  auto      phones use the node's internet when it has a default route, otherwise their own cellular
  backhaul  same as auto (kept so RC1 settings carry over)
  cellular  phones always use their own cellular data; the node only answers for the mesh

Cellular works through DHCP on the phone AP: option 3 (router) is sent empty, so the
phone keeps mobile data as its default route, and option 121 adds a classless route
for the mesh subnet only. The options go in uncommitted UCI (gone at reboot, like RC1)
and dnsmasq restarts only when the active mode actually changes. Phones pick the change
up at their next DHCP renew or Wi-Fi reconnect.

Run as `python3 -m field_extension.eud watch|apply|status|set MODE`.
"""
import ipaddress
import json
import sys
import time
from pathlib import Path

from .core import run

MODES = ('auto', 'backhaul', 'cellular')
STATE_DIR = Path('/etc/field-extensions/state')
INTERVAL_S = 15


class EudInternet:
    def __init__(self, runner=run, state_dir=STATE_DIR, leases='/tmp/dhcp.leases', reach=None):
        self.run, self.state, self.leases = runner, Path(state_dir), Path(leases)
        self.reach = reach   # callable -> bool: real internet behind the default route

    def _read(self, name):
        try:
            return (self.state / name).read_text().strip()
        except OSError:
            return None

    def _write(self, name, value):
        self.state.mkdir(parents=True, exist_ok=True)
        (self.state / name).write_text(value + '\n')

    def mode(self):
        m = self._read('eud-internet-mode')
        return m if m in MODES else 'auto'

    def set_mode(self, mode):
        if mode not in MODES:
            raise ValueError('mode must be one of ' + ', '.join(MODES))
        self._write('eud-internet-mode', mode)

    def uci(self, *args):
        r = self.run(['uci', '-q'] + list(args))
        return r.get('output', '').strip() if r.get('ok') else ''

    def dhcp_section(self):
        """The dhcp section serving the phone AP: interface ahwlan on OpenMANET, lan on RC1."""
        out = self.run(['uci', '-q', 'show', 'dhcp']).get('output', '')
        found = {}
        for line in out.splitlines():
            key, _, value = line.partition('=')
            parts = key.split('.')
            if len(parts) == 3 and parts[2] == 'interface':
                found[value.strip("'")] = parts[1]
        return found.get('ahwlan') or found.get('lan')

    def mesh_subnet(self):
        for iface in ('ahwlan', 'lan'):
            addr = self.uci('get', 'network.%s.ipaddr' % iface)
            if addr:
                mask = self.uci('get', 'network.%s.netmask' % iface) or None
                try:
                    return ipaddress.ip_interface(addr if '/' in addr or not mask else addr + '/' + mask).network
                except ValueError:
                    return None
        return None

    def node_internet(self):
        # A Mesh Point always has a default route to its gate, uplink or not, so the
        # route only counts when traffic actually gets out.
        r = self.run(['ip', 'route', 'show', 'default'])
        return bool(r.get('ok') and r.get('output', '').strip()) and (self.reach() if self.reach else True)

    def resolve(self, mode=None, internet=None):
        mode = mode or self.mode()
        if mode == 'cellular':
            return 'cellular'
        internet = self.node_internet() if internet is None else internet
        return 'backhaul' if internet else 'cellular'

    def current(self, section):
        """What DHCP is handing out now, read from UCI (staged changes included), so a
        reboot, which drops the uncommitted options, is noticed."""
        out = self.run(['uci', '-q', 'get', 'dhcp.%s.dhcp_option' % section]).get('output', '')
        opts = out.split()
        return 'cellular' if '3' in opts and any(o.startswith('121,') for o in opts) else 'backhaul'

    def apply(self):
        """Bring DHCP in line with the mode. Returns the active mode and whether it changed."""
        section = self.dhcp_section()
        subnet = self.mesh_subnet()
        want = self.resolve()
        if section is None or subnet is None:
            return {'ok': False, 'active': self._read('eud-internet-active'),
                    'error': 'phone AP DHCP section or mesh address not found'}
        if want == self.current(section):
            if self._read('eud-internet-active') != want:
                self._write('eud-internet-active', want)
            return {'ok': True, 'active': want, 'changed': False}
        key = 'dhcp.%s.dhcp_option' % section
        self.run(['uci', '-q', 'revert', key])
        if want == 'cellular':
            self.run(['uci', 'add_list', key + '=3'])
            self.run(['uci', 'add_list', '%s=121,%s,0.0.0.0' % (key, subnet)])
        self._write('eud-internet-active', want)
        self.run(['/etc/init.d/dnsmasq', 'restart'])
        return {'ok': True, 'active': want, 'changed': True}

    def restore(self):
        """Undo cellular mode (service stop). Leaves node DHCP as configured."""
        if self._read('eud-internet-active') == 'cellular':
            section = self.dhcp_section()
            if section:
                self.run(['uci', '-q', 'revert', 'dhcp.%s.dhcp_option' % section])
                self.run(['/etc/init.d/dnsmasq', 'restart'])
        self._write('eud-internet-active', 'backhaul')

    def clients(self):
        try:
            lines = self.leases.read_text().splitlines()
        except OSError:
            lines = []
        out = []
        for line in lines:
            f = line.split()
            if len(f) >= 4:
                out.append({'mac': f[1], 'ip': f[2], 'name': '' if f[3] == '*' else f[3]})
        return out

    def status(self):
        mode, section = self.mode(), self.dhcp_section()
        return {'mode': mode, 'modes': list(MODES),
                'active': self.current(section) if section else None,
                'node_internet': self.node_internet(), 'clients': self.clients()}


def main(argv=None):
    argv = sys.argv[1:] if argv is None else argv
    from . import internet
    eud = EudInternet(reach=internet.online)
    cmd = argv[0] if argv else 'status'
    if cmd == 'set' and len(argv) == 2:
        eud.set_mode(argv[1])
        print(json.dumps(eud.apply()))
    elif cmd == 'apply':
        print(json.dumps(eud.apply()))
    elif cmd == 'restore':
        eud.restore()
    elif cmd == 'watch':
        while True:
            try:
                result = eud.apply()
                if result.get('changed'):
                    print('phone internet now', result['active'], flush=True)
            except Exception as exc:   # keep watching; a bad read must not stop the loop
                print('eud apply failed:', exc, flush=True)
            time.sleep(INTERVAL_S)
    elif cmd == 'status':
        print(json.dumps(eud.status()))
    else:
        print('usage: python3 -m field_extension.eud watch|apply|status|restore|set MODE', file=sys.stderr)
        return 2
    return 0


if __name__ == '__main__':
    sys.exit(main())
