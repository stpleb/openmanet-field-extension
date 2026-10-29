"""Write actions for the web UI and ATAK plugin (spec 42, 43).

Each action is a fixed function with validated arguments. Nothing from the
request ever reaches a shell. Callers must present a valid OpenWrt rpcd
session (the same login LuCI and the OpenMANET plugin use); it is checked
with `ubus call session get`, passed as an argv list.
"""
import hashlib
import json
import os
import re
import threading
import time
import urllib.request
from pathlib import Path

from . import eud, maintenance

SESSION_RE = re.compile(r'^[0-9a-f]{32}$')
BRIGHTNESS = (10, 25, 50, 75, 100)
STATE_DIR = Path('/etc/field-extensions/state')


class Login:
    """Sign in through the node's own rpcd (the LuCI login), over local HTTP so the
    password never appears in a process list. Five failures lock it for a minute."""

    def __init__(self, opener=None, url='http://127.0.0.1/ubus', clock=time.time, devices=None):
        self.opener = opener or urllib.request.build_opener(urllib.request.ProxyHandler({}))
        self.url, self.clock, self.failures = url, clock, []
        self.devices = devices

    def __call__(self, body):
        now = self.clock()
        self.failures = [t for t in self.failures if now - t < 60]
        if len(self.failures) >= 5:
            return 429, {'ok': False, 'error': 'Too many attempts. Wait a minute.'}
        user, password = body.get('username'), body.get('password')
        if not (isinstance(user, str) and isinstance(password, str)) or len(user) > 64 or len(password) > 256:
            return 400, {'ok': False, 'error': 'username and password are required'}
        req = urllib.request.Request(self.url, headers={'Content-Type': 'application/json'}, data=json.dumps(
            {'jsonrpc': '2.0', 'id': 1, 'method': 'call',
             'params': ['0' * 32, 'session', 'login', {'username': user, 'password': password}]}).encode())
        try:
            with self.opener.open(req, timeout=5) as response:
                result = json.loads(response.read(65536)).get('result') or []
        except Exception:
            return 503, {'ok': False, 'error': 'Node login service unavailable'}
        token = result[1].get('ubus_rpc_session') if len(result) > 1 and result[0] == 0 else None
        if not isinstance(token, str) or not SESSION_RE.match(token):
            self.failures.append(now)
            return 401, {'ok': False, 'error': 'Wrong username or password'}
        if self.devices is not None and body.get('remember', True) is not False:
            # A long-lived token for this device instead of the 5-minute rpcd session.
            return 200, {'ok': True, 'token': self.devices.issue(body.get('device')),
                         'remember_days': self.devices.REMEMBER_S // 86400}
        return 200, {'ok': True, 'token': token}


class Devices:
    """Remembered sign-ins, so the web page and the plugin ask for the password once per
    device instead of every few minutes (rpcd sessions expire after 5 minutes idle).
    Only a hash of each token is stored. Each use extends it; unused for REMEMBER_S it
    lapses. 'Sign out all devices' clears them (do that if a phone is lost)."""
    REMEMBER_S = 30 * 86400
    MAX = 20

    def __init__(self, path=STATE_DIR / 'devices.json', clock=time.time):
        self.path, self.clock, self.lock = Path(path), clock, threading.Lock()

    @staticmethod
    def _hash(token):
        return hashlib.sha256(token.encode()).hexdigest()

    def _load(self):
        try:
            data = json.loads(self.path.read_text())
            return data if isinstance(data, dict) else {}
        except (OSError, ValueError):
            return {}

    def _save(self, data):
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + '.tmp')
        tmp.write_text(json.dumps(data))
        os.chmod(tmp, 0o600)
        tmp.replace(self.path)

    def issue(self, label=None):
        token = os.urandom(16).hex()
        now = self.clock()
        with self.lock:
            data = {h: v for h, v in self._load().items() if now - v.get('used', 0) < self.REMEMBER_S}
            data[self._hash(token)] = {'made': int(now), 'used': int(now), 'label': (label or '')[:40]}
            if len(data) > self.MAX:   # drop the least recently used
                data = dict(sorted(data.items(), key=lambda kv: kv[1]['used'])[-self.MAX:])
            self._save(data)
        return token

    def valid(self, token):
        if not isinstance(token, str) or not SESSION_RE.match(token):
            return False
        now, h = self.clock(), self._hash(token)
        with self.lock:
            data = self._load()
            entry = data.get(h)
            if not entry or now - entry.get('used', 0) >= self.REMEMBER_S:
                return False
            if now - entry['used'] > 3600:   # write at most hourly
                entry['used'] = int(now)
                self._save(data)
        return True

    def forget(self, token=None):
        with self.lock:
            data = {} if token is None else {h: v for h, v in self._load().items() if h != self._hash(token)}
            self._save(data)

    def count(self):
        now = self.clock()
        return sum(1 for v in self._load().values() if now - v.get('used', 0) < self.REMEMBER_S)


def clear_lora_log(rec=None):
    """A fleet clear also empties the LoRa service's recent-messages list."""
    from . import lora
    try:
        lora.request({'cmd': 'clear_log'})
    except OSError:
        pass


def session_valid(run, token, devices=None):
    if not isinstance(token, str) or not SESSION_RE.match(token):
        return False
    if devices is not None and devices.valid(token):
        return True
    r = run(['ubus', 'call', 'session', 'get', json.dumps({'ubus_rpc_session': token})])
    return bool(r.get('ok'))


LORA_SETTINGS = {'set_radio', 'set_channel', 'set_profile', 'set_device', 'provision', 'wipe', 'setup'}


class Actions:
    def __init__(self, run, pisugar, state_dir=STATE_DIR, capabilities=None, node=None):
        self.run = run
        self.node = node
        self.pisugar = pisugar
        self.state = Path(state_dir)
        self.busy = maintenance.busy_dir_for(self.state)
        self.capabilities = capabilities or (lambda: {})
        self.later = lambda seconds, fn: threading.Timer(seconds, fn).start()
        self.on_fleet_clear = clear_lora_log

    def _write(self, name, value):
        self.state.mkdir(parents=True, exist_ok=True)
        (self.state / name).write_text(str(value) + '\n')

    def auto_power_on(self, body):
        enabled = body.get('enabled')
        if not isinstance(enabled, bool):
            return 400, {'ok': False, 'error': 'enabled must be true or false'}
        result = self.pisugar.set_auto_power_on(enabled)
        return (200 if result['verified'] else 502), {'ok': result['verified'], **result}

    def led(self, body):
        if 'enabled' in body:
            if not isinstance(body['enabled'], bool):
                return 400, {'ok': False, 'error': 'enabled must be true or false'}
            self._write('led-enabled', 1 if body['enabled'] else 0)
        if 'chat_alerts' in body:
            if not isinstance(body['chat_alerts'], bool):
                return 400, {'ok': False, 'error': 'chat_alerts must be true or false'}
            self._write('alerts-enabled', 1 if body['chat_alerts'] else 0)
        if 'brightness' in body:
            if body['brightness'] not in BRIGHTNESS or isinstance(body['brightness'], bool):
                return 400, {'ok': False, 'error': 'brightness must be one of 10, 25, 50, 75, 100'}
            self._write('led-brightness', body['brightness'])
        return 200, {'ok': True}

    def node_power(self, body, action):
        if body.get('confirm') is not True:
            return 400, {'ok': False, 'error': 'confirm must be true'}
        argv = {'reboot': ['/sbin/reboot'], 'shutdown': ['/sbin/poweroff']}[action]
        maintenance.mark('shutting-down', 180, self.busy)   # red short blinks until the node is off
        if action == 'shutdown':
            from .pisugar_i2c import arm_power_cut
            arm_power_cut()   # really off afterwards, not halted and draining the battery
        self.run(argv)
        return 202, {'ok': True, 'action': action}

    def openmanet_update(self, body):
        if self.node is None or self.node.installer is None:
            return 404, {'ok': False, 'error': 'Updates are not enabled on this node'}
        if body.get('confirm') is not True:
            return 400, {'ok': False, 'error': 'confirm must be true'}
        version = body.get('version')
        if not isinstance(version, str) or len(version) > 32:
            return 400, {'ok': False, 'error': 'version is required'}
        report = self.node.updates()
        return self.node.installer.start(report, self.node.power()['battery'], version,
                                         body.get('acknowledge_warnings') is True)

    def eud_internet(self, body):
        if self.node is None:
            return 404, {'ok': False, 'error': 'Phone internet control is not available'}
        if body.get('mode') not in eud.MODES:
            return 400, {'ok': False, 'error': 'mode must be auto, backhaul or cellular'}
        self.node.eud.set_mode(body['mode'])
        result = self.node.eud.apply()
        return (200 if result['ok'] else 502), {**result, 'mode': body['mode']}

    def lora(self, cmd, body, fields):
        from . import lora
        req = {'cmd': cmd, **{k: body[k] for k in fields if k in body}}
        if cmd in LORA_SETTINGS:
            maintenance.mark('settings', 120, self.busy)   # team channel or radio change being applied
        try:
            result = lora.request(req)
        except OSError:
            return 503, {'ok': False, 'error': 'LoRa service is not running'}
        return (200 if result.get('ok') else 400), result

    def battery(self, body):
        cal = getattr(self.node, 'calibration', None)
        if cal is None:
            return 409, {'ok': False, 'error': 'Battery calibration is not available on this node'}
        action = body.get('action')
        if action == 'start':
            r = cal.start()
        elif action == 'cancel':
            r = cal.cancel()
        elif action == 'reset':
            cal.reset()
            r = {'ok': True, 'note': 'Using the PiSugar chip\'s own estimate again.'}
        else:
            return 400, {'ok': False, 'error': 'action must be start, cancel or reset'}
        return (200 if r.get('ok') else 400), r

    def battery_size(self, body):
        est = getattr(self.node, 'estimate', None)
        if est is None:
            return 409, {'ok': False, 'error': 'Battery size is not available on this node'}
        r = est.set_mah(body.get('mah'))
        return (200 if r.get('ok') else 400), r

    def safe_shutdown(self, body):
        guard = getattr(self.node, 'guard', None)
        if guard is None:
            return 409, {'ok': False, 'error': 'Safe shutdown is not available on this node'}
        r = guard.set_percent(body.get('percent'))
        return (200 if r.get('ok') else 400), r

    def node_position(self, body):
        from . import node_position
        ok, r = node_position.save(body, self.state.parent / 'position.json')
        return (200 if ok else 400), r

    def node_position_source(self, body):
        from . import node_position
        ok, r = node_position.set_source(body.get('source'), self.state.parent / 'position.json')
        return (200 if ok else 400), r

    def node_identity(self, body):
        from . import identity
        ok, r = identity.save(body, self.state.parent / 'identity.json')
        return (200 if ok else 400), r

    def long_press(self, body):
        if not hasattr(self.pisugar, 'direct'):
            return 409, {'ok': False, 'error': 'Not available on this node'}
        on = body.get('enabled')
        if not isinstance(on, bool):
            return 400, {'ok': False, 'error': 'enabled must be true or false'}
        self._write('long-press-shutdown', 1 if on else 0)
        return 200, {'ok': True, 'note': 'A long press of the PiSugar button now shuts the node down cleanly.' if on else
                     'A long press of the PiSugar button cuts the power at once (the PiSugar default).'}

    def mesh_update(self, body):
        up = getattr(self.node, 'updater', None)
        if up is None:
            return 409, {'ok': False, 'error': 'Mesh updates are not available on this node'}
        action = body.get('action')
        if action == 'check':
            offer = up.check()
            r = {'ok': True, 'offer': offer, 'note': 'Newer version %s on %s.' % (offer['version'], offer['node'] or offer['ip'])
                 if offer else 'No newer Field Extension on the mesh.'}
        elif action == 'install':
            r = up.install(body.get('ip'))
        elif action == 'auto' and isinstance(body.get('enabled'), bool):
            r = up.set_auto(body['enabled'])
        else:
            return 400, {'ok': False, 'error': 'action must be check, install or auto'}
        return (200 if r.get('ok') else 400), r

    def recovery(self, body):
        from .recovery import DEFAULTS, STATE
        name, on = body.get('name'), body.get('enabled')
        if name not in DEFAULTS or not isinstance(on, bool):
            return 400, {'ok': False, 'error': 'name must be one of %s, enabled true or false' % ', '.join(DEFAULTS)}
        STATE.mkdir(parents=True, exist_ok=True)
        (STATE / ('recovery-' + name)).write_text('1\n' if on else '0\n')
        return 200, {'ok': True, 'note': '%s %s.' % (name, 'on' if on else 'off')}

    def mesh_share(self, body):
        from . import mesh_join
        r = mesh_join.share(self.run)
        return (200 if r.get('ok') else 409), r

    def mesh_join(self, body):
        from . import mesh_join
        if body.get('confirm') is not True:
            return 400, {'ok': False, 'error': 'confirm must be true (the node reboots to join)'}
        r = mesh_join.join(self.run, body.get('code'), body.get('revert_hours'), state=self.state)
        if not r.get('ok'):
            return 400, r
        # Reboot a moment later so this answer reaches the phone first. No Wi-Fi restart:
        # a remote one half-stuck Node02 on 2026-10-13.
        maintenance.mark('settings', 120, self.busy)
        self.later(3, lambda: (self.run(['sync']), self.run(['/sbin/reboot'])))
        return 202, r

    def setup_code(self, body):
        from . import lora, mesh_join, node_setup
        mesh = mesh_join.share(self.run)
        if not mesh.get('ok'):
            return 409, mesh
        try:
            team = lora.request({'cmd': 'share'})
        except OSError:
            team = {}
        url = team.get('url') if team.get('ok') else None
        return 200, {'ok': True, 'code': node_setup.make(mesh['code'], url), 'mesh_id': mesh['mesh_id'],
                     'parts': mesh['parts'] + (['lora'] if url else []),
                     'note': 'This code holds the mesh password and the team key. Only show it to your team.'}

    def node_setup(self, body):
        """Set up this node from another node's setup code: LoRa team, callsign, then mesh (reboots)."""
        from . import identity, lora, mesh_join, node_setup
        if body.get('confirm') is not True:
            return 400, {'ok': False, 'error': 'confirm must be true (the node reboots to join the mesh)'}
        try:
            mesh_code, url = node_setup.read(body.get('code'))
        except ValueError as e:
            return 400, {'ok': False, 'error': str(e)}
        maintenance.mark('settings', 300, self.busy)
        if body.get('callsign'):
            ok, r = identity.save({'callsign': body['callsign']}, self.state.parent / 'identity.json')
            if not ok:
                return 400, r
        notes = []
        if url:
            try:
                r = lora.request({'cmd': 'set_channel', 'url': url})
            except OSError:
                r = {'ok': False}
            notes.append('LoRa team channel set.' if r.get('ok') else
                         'LoRa team channel not set (no radio yet); scan the team QR in Comms later.')
        r = mesh_join.join(self.run, mesh_code, None, state=self.state)
        if not r.get('ok'):
            return 400, {**r, 'notes': notes}
        self.later(3, lambda: (self.run(['sync']), self.run(['/sbin/reboot'])))
        return 202, {**r, 'notes': notes, 'note': ' '.join(notes + [r['note']])}

    def mesh_join_cancel(self, body):
        from . import mesh_join
        r = mesh_join.cancel(self.state)
        return (200 if r.get('ok') else 409), r

    def fleet_clear(self, body):
        from . import fleet_clear, identity
        if body.get('confirm') is not True:
            return 400, {'ok': False, 'error': 'confirm must be true (this cannot be undone)'}
        r = fleet_clear.start(body.get('what'), identity.load().get('callsign'), state=self.state)
        if not r.get('ok'):
            return 400, r
        self.on_fleet_clear(r['clear'])
        return 200, r

    def maintenance(self, body):
        """Manual maintenance mode: the light shows white until it ends (default 30 min)."""
        enabled = body.get('enabled')
        if not isinstance(enabled, bool):
            return 400, {'ok': False, 'error': 'enabled must be true or false'}
        minutes = body.get('minutes', 30)
        if isinstance(minutes, bool) or not isinstance(minutes, int) or not 1 <= minutes <= maintenance.MAX_MINUTES:
            return 400, {'ok': False, 'error': 'minutes must be a whole number from 1 to %d' % maintenance.MAX_MINUTES}
        maintenance.set_manual(enabled, minutes, state_dir=self.state)
        return 200, {'ok': True, **maintenance.status(state_dir=self.state, busy_dir=self.busy)}

    def dispatch(self, path, body):
        table = {
            'maintenance': self.maintenance,
            'power/auto-power-on': self.auto_power_on,
            'led': self.led,
            'node/reboot': lambda b: self.node_power(b, 'reboot'),
            'node/shutdown': lambda b: self.node_power(b, 'shutdown'),
            'openmanet/update': self.openmanet_update,
            'eud/internet': self.eud_internet,
            'battery/calibration': self.battery,
            'fleet/clear': self.fleet_clear,
            'battery/size': self.battery_size,
            'update/mesh': self.mesh_update,
            'recovery/setting': self.recovery,
            'battery/safe-shutdown': self.safe_shutdown,
            'battery/long-press': self.long_press,
            'node/identity': self.node_identity,
            'node/position': self.node_position,
            'node/position/source': self.node_position_source,
            'mesh/share': self.mesh_share,
            'mesh/join': self.mesh_join,
            'mesh/join/cancel': self.mesh_join_cancel,
            'node/setup-code': self.setup_code,
            'node/setup': self.node_setup,
            'lora/mode': lambda b: self.lora('set_mode', b, ('mode',)),
            'lora/node-radios': lambda b: self.lora('set_node_radios', b, ('show',)),
            'lora/radio': lambda b: self.lora('set_radio', b, ('region', 'preset', 'fleet')),
            'lora/channel': lambda b: self.lora('set_channel', b, ('security', 'name', 'passphrase', 'url', 'fleet')),
            'lora/send': lambda b: self.lora('send_text', b, ('text',)),
            'lora/profile': lambda b: self.lora('set_profile', b, ('profile', 'fleet')),
            'lora/ping': lambda b: self.lora('ping', b, ()),
            'lora/trace': lambda b: self.lora('trace', b, ('node',)),
            'lora/share': lambda b: self.lora('share', b, ()),
            'lora/device': lambda b: self.lora('set_device', b, ('device',)),
            'lora/provision': lambda b: self.lora('provision', b, ('radios',)),
            'lora/wipe': lambda b: self.lora('wipe', b, ('target',)),
            'lora/setup': lambda b: self.lora('setup', b, ('region', 'preset', 'name', 'passphrase', 'profile')),
        }
        handler = table.get(path)
        if handler is None:
            return 404, {'ok': False, 'error': 'Unknown action'}
        if not isinstance(body, dict):
            return 400, {'ok': False, 'error': 'JSON object expected'}
        return handler(body)
