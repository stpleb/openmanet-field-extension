"""Read-only collectors. Hardware ownership is never inferred from a hostname."""
import json
import platform
import shutil
import socket
import subprocess
import time
import urllib.request
from pathlib import Path

from .capabilities import Detector
from .pisugar import PiSugar
from .pisugar_i2c import AutoPiSugar, PiSugarI2C
from .ina219 import INA219
from . import radios
from . import updates as upd
from . import upgrade
from . import led as led_model
from . import maintenance
from . import network as net

SERVICES = ('openmanetd', 'gpsd', 'meshtastic', 'pisugar-server', 'link_monitor',
            'qos-rate-adapt', 'mcs-latch-watchdog', 'link-demote', 'reboot-tracker',
            'gateway')
RPC = {
    'system': '/openmanet.dashboard.v1.DashboardService/GetDashboardStatus',
    'mesh': '/openmanet.mesh_topology.v1.MeshTopologyService/GetMeshTopology',
    'comms': '/openmanet.comms.v1.CommsService/GetCommsStatus',
}

def run(argv):
    try:
        p = subprocess.run(argv, capture_output=True, text=True, timeout=4)
        return {'available': True, 'ok': p.returncode == 0, 'code': p.returncode,
                'output': p.stdout[:32768]}
    except FileNotFoundError:
        return {'available': False, 'ok': False, 'error': 'Not installed'}
    except subprocess.TimeoutExpired:
        return {'available': True, 'ok': False, 'error': 'Timed out'}
    except OSError:
        return {'available': False, 'ok': False, 'error': 'Cannot access command'}

def read(path):
    try:
        return Path(path).read_text().strip()
    except (OSError, UnicodeError):
        return None

def _setting(name, default, state=Path('/etc/field-extensions/state')):
    try:
        return int((state / name).read_text().strip())
    except (OSError, ValueError):
        return default


def _lora_radio():
    try:
        return int(Path('/var/run/field-lora-radio').read_text().strip())
    except (OSError, ValueError):
        return None


class Node:
    def __init__(self, runner=run, root=Path('/'), opener=None, pisugar=None, ina219=None,
                 releases=None, state_dir=Path('/etc/field-extensions/state')):
        self.run, self.root = runner, Path(root)
        self.state_dir = Path(state_dir)   # LED settings written by actions.Actions
        self.releases = releases or upd.Releases()
        self.installer = None       # upgrade.Installer, set only when writes are enabled
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.pisugar = pisugar or AutoPiSugar(PiSugar(), PiSugarI2C())
        self.ina219 = ina219 or INA219()
        from . import internet
        from .eud import EudInternet
        # Real reachability check on a node; tests with a fake root keep the route-only answer.
        self.reach = internet.online if self.root == Path('/') else (lambda: True)
        self.eud = EudInternet(runner, self.state_dir, reach=lambda: self.reach())

    def file(self, path):
        return self.root / path.lstrip('/')

    def rpc(self, name):
        req = urllib.request.Request('http://127.0.0.1:8087' + RPC[name], data=b'{}',
              headers={'Content-Type': 'application/json', 'Connect-Protocol-Version': '1'})
        try:
            with self.opener.open(req, timeout=3) as response:
                data = response.read(262145)
                if len(data) > 262144:
                    raise ValueError('oversize response')
                return {'available': True, 'data': json.loads(data)}
        except Exception:
            return {'available': False, 'error': 'OpenMANET API unavailable or incompatible'}

    def capabilities(self):
        return Detector(root=self.root,
                        pisugar=lambda: self.pisugar.status()['available'],
                        ina219=lambda: self.ina219.status()['available'],
                        routes=lambda: self.run(['ip', 'route']).get('output', ''),
                        reach=self.reach).detect()

    def system(self):
        return {'hostname': socket.gethostname(), 'kernel': platform.release(),
                'architecture': platform.machine(),
                'uptime': read(self.file('/proc/uptime')),
                'load': read(self.file('/proc/loadavg')),
                'memory': read(self.file('/proc/meminfo')),
                'temperature_millicelsius': read(self.file('/sys/class/thermal/thermal_zone0/temp')),
                'openmanet': self.rpc('system')}

    def services(self):
        """Service health (spec 16): the Field Extension services, read from procd, with plain states:
        RUNNING, RESTARTING (crashed and restarted within the last minute), STOPPED,
        OFF (turned off in a setting) and UNAVAILABLE (not on this node)."""
        if self.file('/etc/init.d/field-extensions').exists():
            return self.fe_services()
        result = {}
        for name in SERVICES:
            path = self.file('/etc/init.d/' + name)
            if not path.exists():
                result[name] = 'UNAVAILABLE'
                continue
            r = self.run([str(path), 'status'])
            result[name] = 'RUNNING' if r.get('ok') else 'UNKNOWN'
        return result

    def _procd(self, name):
        """{instance: {'running': bool, 'pid': int}} from `ubus call service list`, or None."""
        r = self.run(['ubus', 'call', 'service', 'list', json.dumps({'name': name})])
        if not r.get('ok'):
            return None
        try:
            inst = (json.loads(r.get('output') or '{}').get(name) or {}).get('instances') or {}
        except ValueError:
            return None
        return {k: {'running': bool(v.get('running')), 'pid': v.get('pid')} for k, v in inst.items()}

    def fe_services(self, now=None):
        now = time.monotonic() if now is None else now
        seen = self.__dict__.setdefault('_pids', {})   # instance -> (pid, time the pid changed)
        out = {}

        def state(key, info):
            if not info or not info.get('running'):
                return 'STOPPED'
            pid = info.get('pid')
            old = seen.get(key)
            if old is None:
                seen[key] = (pid, None)
            elif old[0] != pid:
                seen[key] = (pid, now)
            changed = seen[key][1]
            return 'RESTARTING' if changed is not None and now - changed < 60 else 'RUNNING'

        for name in ('openmanetd', 'gpsd'):
            if not self.file('/etc/init.d/' + name).exists():
                out[name] = 'UNAVAILABLE'
                continue
            inst = self._procd(name)
            if inst is None:
                r = self.run(['/etc/init.d/' + name, 'status'])
                out[name] = 'RUNNING' if r.get('ok') else 'STOPPED'
            else:
                running = [v for v in inst.values() if v['running']]
                out[name] = state(name, running[0]) if running else 'STOPPED'
        fe = self._procd('field-extensions') or {}
        for key, label in (('instance1', 'fe-web'), ('led', 'fe-light'), ('eud', 'fe-phone-internet'),
                           ('lora', 'fe-lora'), ('recovery', 'fe-recovery')):
            out[label] = state(label, fe.get(key)) if fe else 'UNKNOWN'
        try:
            rec = json.loads(self.file('/tmp/field-recovery.json').read_text())
        except (OSError, ValueError):
            rec = None
        settings = rec.get('settings') if isinstance(rec, dict) else None
        if isinstance(settings, dict):
            # These run inside the recovery service: on when their setting is, and only
            # as healthy as that service.
            for key, label in (('radio-watchdog', 'fe-halow-watchdog'), ('peer-watchdog', 'fe-peer-watchdog'),
                               ('mcs-latch', 'fe-rate-latch'),
                               ('link-check', 'fe-link-check'), ('link-demote', 'fe-link-demote'),
                               ('mesh-guard', 'fe-mesh-guard'), ('qos', 'fe-traffic-shaping')):
                if key in settings:
                    out[label] = out.get('fe-recovery', 'UNKNOWN') if settings[key] else 'OFF'
        out['fe-power-off'] = 'RUNNING' if any(self.file('/etc/rc.d').glob('K*field-power-off')) else 'STOPPED'
        return out

    def power(self):
        r = self.run(['vcgencmd', 'get_throttled'])
        battery = self.pisugar.status()
        if not battery.get('available'):
            # No PiSugar: fall back to the INA219 UPS HAT fitted on Node02.
            battery = self.ina219.status()
        if getattr(self, 'calibration', None) is not None and battery.get('source') == 'pisugar-i2c':
            battery = {**battery, 'calibration': self.calibration.status()}
        if getattr(self, 'guard', None) is not None and battery.get('source') == 'pisugar-i2c':
            battery = {**battery, 'safe_shutdown': self.guard.status()}
        direct = getattr(self.pisugar, 'direct', None)
        if getattr(self, 'guard', None) is not None and direct is not None and battery.get('source') == 'pisugar-i2c':
            try:
                hw = direct.soft_shutdown()
            except Exception:
                hw = None
            battery = {**battery, 'long_press_shutdown': {'enabled': _setting('long-press-shutdown', 1) == 1,
                                                          'hardware': hw}}
        if getattr(self, 'estimate', None) is not None and battery.get('source') == 'pisugar-i2c':
            est = self.estimate.status(battery)
            battery = {**battery, 'estimate': est, 'time_left_min': est.get('time_left_min'),
                       'time_to_full_min': est.get('time_to_full_min')}
        return {'battery': battery, 'throttling': r}

    def network(self):
        out = {name: self.run(args) for name, args in {
            'interfaces': ['ip', '-j', 'address', 'show'],
            'routes': ['ip', '-j', 'route', 'show'],
            'mesh_interfaces': ['batctl', 'if'],
            'wifi_interfaces': ['iw', 'dev'],
        }.items()}
        text = lambda k: out[k].get('output', '') if out[k].get('ok') else ''
        out['parsed'] = net.parse(text('interfaces'), text('routes'), text('wifi_interfaces'),
                                  self.halow_iface())
        return out

    def setting(self, name, default):
        try:
            return int((self.state_dir / name).read_text().strip())
        except (OSError, ValueError):
            return default

    def led(self, summary=None):
        """LED settings plus the status the light shows, resolved by led.py from the
        summary readings. Status is resolved as if the light were on, so the web UI
        can show it even when the light is switched off."""
        s = summary or self.summary()
        enabled = self.setting('led-enabled', 1) == 1
        brightness = self.setting('led-brightness', 100)
        if brightness not in (10, 25, 50, 75, 100):
            brightness = 100
        power = s.get('power') or {}
        uptime = (read(self.file('/proc/uptime')) or '').split()
        try:
            booting = float(uptime[0]) < 60
        except (IndexError, ValueError):
            booting = False
        busy = maintenance.busy_dir_for(self.state_dir)
        maint = maintenance.status(state_dir=self.state_dir, busy_dir=busy)
        status = led_model.resolve({
            'enabled': True, 'booting': booting,
            'battery_percent': power.get('battery_percent') if power.get('available') else None,
            'external_power': power.get('external_power') if power.get('available') else None,
            'charging': power.get('charging') if power.get('available') else None,
            'mesh_neighbors': len((s.get('mesh') or {}).get('neighbors') or []),
            'internet': (s.get('internet') or {}).get('available') is True,
            'lora_available': (s.get('lora') or {}).get('available') is True,
            'gps_fix': (s.get('gps') or {}).get('state') == 'FIX',
            'admin': maint['active'],
            'shutting_down': maintenance.shutting_down(busy),
        })
        return {'available': True, 'enabled': enabled, 'brightness': brightness, 'maintenance': maint,
                'chat_alerts': self.setting('alerts-enabled', 0) == 1,
                'hardware': (s.get('capabilities') or {}).get('status_led'), **status}

    def summary(self):
        """Field-readiness strip (spec 40): each subsystem independent, plain words."""
        caps = self.capabilities()
        halow_if = self.halow_iface()
        halow = radios.halow(self.run, halow_if)
        neigh = radios.neighbors(self.run, halow_if)
        gps = radios.gps()
        battery = self.power()['battery']
        from . import fleet_clear, identity, mesh_join
        out = {
            'node': socket.gethostname(),
            'identity': identity.load(),
            'platform': caps.get('platform'),
            'power': battery,
            'halow': halow,
            'mesh': {'nodes': neigh.get('nodes', neigh.get('count', 0)) + 1 if neigh.get('available') else None,
                     'neighbors': neigh.get('neighbors', []),
                     'interfaces': radios.mesh_interfaces(self.run, halow_if),
                     'join': mesh_join.status()},
            'lora': {'available': caps.get('meshtastic', False)},
            'gps': gps,
            'internet': {'available': caps.get('internet_gateway', False)},
            'fleet_clear': fleet_clear.current(),
        }
        out['led'] = self.led({**out, 'capabilities': caps})
        # Names the ATAK plugin's node markers read.
        from . import node_position
        pos = node_position.pick(gps)
        out['position'] = {k: pos[k] for k in ('lat', 'lon', 'alt', 'source')} if pos else None
        out['position_source'] = node_position.get_source()
        if isinstance(battery, dict) and battery.get('available'):
            out['power'] = {**battery, 'battery': {'time_left_min': battery.get('time_left_min'),
                                                   'time_to_full_min': battery.get('time_to_full_min'),
                                                   'percent': battery.get('battery_percent')}}
        out['eud'] = self.eud.status()
        if getattr(self, 'updater', None) is not None:
            out['mesh_update'] = self.updater.status()
        try:
            out['recovery'] = json.loads(self.file('/tmp/field-recovery.json').read_text())
        except (OSError, ValueError):
            out['recovery'] = None
        return out

    def halow_iface(self):
        """The interface on the Morse phy (Node02: wlan0), never a hard-coded name."""
        base = self.file('/sys/class/net')
        try:
            for dev in sorted(base.iterdir()):
                drv = dev / 'device' / 'driver'
                if (dev / 'wireless').exists() or (dev / 'phy80211').exists():
                    if drv.exists() and 'morse' in drv.resolve().name.lower() and dev.name != 'morse0':
                        return dev.name
        except OSError:
            pass
        return None

    def dependencies(self):
        """Does each OpenMANET piece Field Extension relies on still answer?
        Run after an upgrade to see what the new release changed."""
        iface = self.halow_iface()
        checks = {
            'OpenMANET API (openmanetd)': self.rpc('system').get('available', False),
            'Mesh routing (batctl)': self.run(['batctl', 'if']).get('ok', False),
            'HaLow radio (morse_cli)': bool(iface) and self.run(['morse_cli', '-i', iface, 'channel']).get('ok', False),
            'GPS (gpsd)': radios.gps().get('state') not in (None, 'GPS ABSENT') or None,
        }
        return [{'name': k, 'ok': v} for k, v in checks.items()]

    def updates(self, refresh=False):
        detector = Detector(root=self.root)
        probe = upd.Probe(self.root, self.halow_iface(), self.run)
        key, source = probe.image_key(detector.node_conf().get('OPENMANET_IMAGE'))
        current = upd.installed_version(read(self.file('/etc/openwrt_release')))
        data, error = self.releases.get(refresh)
        out = upd.report((data or {}).get('releases', []), current, key, source,
                         probe.driver_version())
        out['checked_at'] = (data or {}).get('checked_at')
        out['error'] = error
        out['dependencies'] = self.dependencies()
        if getattr(self, 'updater', None) is not None:
            out['mesh_update'] = self.updater.status()
        out['upgrade'] = {'enabled': self.installer is not None,
                          'locked': upgrade.eligibility(out, self.power()['battery']),
                          'status': self.installer.status if self.installer else None}
        return out

    def mesh_nodes(self):
        subnet = self.eud.mesh_subnet()
        ip = None
        for iface in ('ahwlan', 'lan'):
            ip = self.eud.uci('get', 'network.%s.ipaddr' % iface)
            if ip:
                ip = ip.split('/')[0]
                break
        cached = getattr(self, '_nodes_cache', None)
        if cached and time.time() - cached[0] < 60:
            return cached[1]
        out = radios.mesh_nodes(self.run, subnet, ip or None, is_node=radios.answers_as_node)
        self._nodes_cache = (time.time(), out)
        return out

    def hello(self):
        """Cheap 'who are you' for launcher apps: name, version and mesh address only."""
        from . import __version__
        nodes = self.mesh_nodes()
        me = next((n['ip'] for n in nodes['nodes'] if n.get('self')), None)
        try:
            version = self.file('/opt/field-extensions/VERSION').read_text().strip()
        except OSError:
            version = ''
        from .fleet_update import package_info
        from . import fleet_clear, identity
        me_id = identity.load()
        return {'field_extension': True, 'node': socket.gethostname(),
                'callsign': me_id['callsign'], 'color': me_id['color'], 'role': me_id['role'],
                'team_role': me_id['team_role'],
                'version': version or __version__, 'ip': me,
                'package': package_info(self.file('/opt/field-extensions'), version or None),
                'fleet_clear': fleet_clear.current(), 'lora_radio': _lora_radio()}

    def diagnostics(self):
        """Support bundle (spec 53): what someone helping remotely needs, nothing secret.
        Passphrases, keys and tokens never appear in these outputs; LoRa channel keys are
        reported only as private/open."""
        halow = self.halow_iface()
        cmds = {'batctl n': ['batctl', 'n'], 'batctl o': ['batctl', 'o'], 'batctl if': ['batctl', 'if'],
                'ip route': ['ip', 'route'], 'ip addr': ['ip', '-brief', 'address'], 'iw dev': ['iw', 'dev'],
                'uptime': ['uptime'], 'df': ['df', '-h'], 'free': ['free'], 'logread (FE)': ['sh', '-c', 'logread | grep -i field | tail -n 80']}
        if halow:
            cmds['morse_cli channel'] = ['morse_cli', '-i', halow, 'channel']
            cmds['iw station dump'] = ['iw', 'dev', halow, 'station', 'dump']
        out = {name: (self.run(argv).get('output') or '')[-8000:] for name, argv in cmds.items()}
        for name, path in (('version', '/opt/field-extensions/VERSION'), ('openwrt_release', '/etc/openwrt_release'),
                           ('selftest', '/tmp/field-extensions-selftest.txt'),
                           ('reboot log', '/etc/field-extensions/state/reboot-log.txt')):
            out[name] = (read(self.file(path)) or '')[-8000:]
        out['summary'] = self.summary()
        lora = self.lora()
        out['lora'] = {k: v for k, v in lora.items() if k not in ('recent',)}
        out['generated'] = int(time.time())
        return out

    def card(self):
        """One node at a glance, for map markers in the ATAK plugin and other nodes' pages:
        where it is, its light, battery and links. Read-only and public on the mesh."""
        s = self.summary()
        hello = self.hello()
        p, g, led = s.get('power') or {}, s.get('gps') or {}, s.get('led') or {}
        est = p.get('estimate') or {}
        lora = self.lora()
        return {
            'node': hello['node'], 'ip': hello['ip'], 'version': hello['version'],
            'callsign': hello.get('callsign') or hello['node'], 'color': hello.get('color'), 'role': hello.get('role'),
            'led': {k: led.get(k) for k in ('state', 'color', 'blink', 'pulse_every') if k in led},
            'battery': {'percent': p.get('battery_percent'), 'external_power': p.get('external_power'),
                        'charging': p.get('charging'), 'time_left_min': est.get('time_left_min'),
                        'time_to_full_min': est.get('time_to_full_min')} if p.get('available') else None,
            'position': _node_position(g),
            'gps': g.get('state'),
            'mesh': {'neighbors': [{'mac': n.get('mac'), 'transport': n.get('transport'),
                                    'throughput_mbps': n.get('throughput_mbps')}
                                   for n in (s.get('mesh') or {}).get('neighbors') or []]},
            'internet': (s.get('internet') or {}).get('available'),
            'lora': {'available': lora.get('available', False), 'mode': lora.get('mode'),
                     'sending': lora.get('sending_over_lora'),
                     'lora_only': lora.get('lora_only') or [],
                     'node_id': (lora.get('radio') or {}).get('node_id')},
        }

    def lora(self):
        """LoRa radio and fallback status from the LoRa service (lora.py)."""
        from . import lora
        try:
            reply = lora.request({'cmd': 'status'})
        except OSError:
            return {'available': False, 'message': 'LoRa service is not running (no radio plugged in?)'}
        reply.pop('ok', None)
        return reply

    def get(self, name):
        if name in ('mesh', 'comms'):
            return self.rpc(name)
        return {'capabilities': self.capabilities, 'system': self.system,
                'services': self.services, 'summary': self.summary,
                'gps': radios.gps,
                'halow': lambda: radios.halow(self.run, self.halow_iface()),
                'neighbors': lambda: radios.neighbors(self.run, self.halow_iface()),
                'power': self.power, 'network': self.network, 'led': self.led,
                'eud': self.eud.status, 'updates': self.updates,
                'nodes': self.mesh_nodes, 'hello': self.hello, 'lora': self.lora, 'card': self.card,
                'diagnostics': self.diagnostics}[name]()


def _node_position(gps):
    from . import node_position
    return node_position.pick(gps)
