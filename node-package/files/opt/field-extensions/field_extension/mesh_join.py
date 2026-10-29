"""Share this node's mesh settings as a code (shown as a QR), and join another mesh from one.

share() packs the HaLow mesh and, if set up, the 2.4 GHz mesh into "FEMESH1:" plus
base64url JSON. It holds the mesh password, so it is only handed out to a signed-in
caller. join() backs up /etc/config/wireless, writes the new settings with uci (argv
lists, whitelisted options, printable values only) and reboots: a remote Wi-Fi restart
half-stuck Node02 on 2026-10-13, so the node never does one. A temporary join stores a
deadline; the recovery service calls tick(), which puts the backup back and reboots
once the deadline passes (it survives reboots). cancel() keeps the join for good.
"""
import base64
import json
import os
import re
import shutil
import time
from pathlib import Path

PREFIX = 'FEMESH1:'
STATE = Path('/etc/field-extensions/state')
WIRELESS = Path('/etc/config/wireless')
IFACE_KEYS = ('mesh_id', 'encryption', 'key', 'sae_pwe', 'mesh_fwding', 'beacon_int',
              's1g_prim_chwidth', 's1g_prim_1mhz_chan_index')
RADIO_KEYS = ('channel', 'htmode', 'bw', 'country', 's1g_prim_chwidth', 's1g_prim_1mhz_chan_index')
DROP_IF_ABSENT = ('sae_pwe',)   # an old compatibility setting must not linger after a join
VALUE_RE = re.compile(r'^[\x20-\x7e]{0,128}$')
SECTION_RE = re.compile(r'^[A-Za-z0-9_@\[\]-]{1,40}$')
MAX_HOURS = 24 * 30


def _unquote(v):
    v = v.strip()
    if len(v) >= 2 and v[0] == v[-1] == "'":
        v = v[1:-1].replace("'\\''", "'")
    return v


def parse_show(text):
    """`uci show wireless` -> {section: {'.type': type, option: value}}."""
    out = {}
    for line in (text or '').splitlines():
        m = re.match(r'^wireless\.([^.=]+)=(\S+)$', line)
        if m:
            out.setdefault(m.group(1), {})['.type'] = m.group(2)
            continue
        m = re.match(r'^wireless\.([^.=]+)\.([^=]+)=(.*)$', line)
        if m:
            out.setdefault(m.group(1), {})[m.group(2)] = _unquote(m.group(3))
    return out


def meshes(config):
    """{'halow': (radio, iface), 'w24': (radio, iface)} for the mesh interfaces set up here."""
    found = {}
    for name, sec in config.items():
        if sec.get('.type') != 'wifi-iface' or sec.get('mode') != 'mesh':
            continue
        radio = config.get(sec.get('device', ''), {})
        if radio.get('type') == 'morse':
            found.setdefault('halow', (sec['device'], name))
        elif radio.get('band') == '2g' or radio.get('hwmode') in ('11g', '11b', '11n'):
            found.setdefault('w24', (sec['device'], name))
    return found


def usb_radio_24(config):
    """A 2.4 GHz USB Wi-Fi dongle (radio section) with no mesh on it yet, or None.
    The Pi's built-in Wi-Fi is also 2.4 GHz but is the phone hotspot, so only a USB
    radio is taken."""
    used = {sec.get('device') for sec in config.values()
            if sec.get('.type') == 'wifi-iface' and sec.get('mode') == 'mesh'}
    for name, sec in sorted(config.items()):
        if (sec.get('.type') == 'wifi-device' and name not in used and 'usb' in sec.get('path', '')
                and (sec.get('band') == '2g' or sec.get('hwmode') in ('11g', '11b', '11n'))):
            return name
    return None


NEW_W24_IFACE = 'fe_mesh24'
W24_NETWORK = 'batmesh1'   # OpenMANET 1.8 defines it (second batman hard interface)


def _read(run):
    return parse_show(run(['uci', '-q', 'show', 'wireless']).get('output', ''))


def share(run):
    config = _read(run)
    parts = {}
    for kind, (radio, iface) in meshes(config).items():
        parts[kind] = {'iface': {k: config[iface][k] for k in IFACE_KEYS if k in config[iface]},
                       'radio': {k: config[radio][k] for k in RADIO_KEYS if k in config[radio]}}
    if 'halow' not in parts:
        return {'ok': False, 'error': 'This node has no HaLow mesh to share'}
    blob = json.dumps({'v': 1, **parts}, separators=(',', ':')).encode()
    return {'ok': True, 'code': PREFIX + base64.urlsafe_b64encode(blob).decode().rstrip('='),
            'mesh_id': parts['halow']['iface'].get('mesh_id'), 'parts': sorted(parts)}


def decode(code):
    """Validated parts from a code, or raises ValueError with a message for the user."""
    if not isinstance(code, str) or not code.startswith(PREFIX) or len(code) > 4096:
        raise ValueError('Not a mesh code')
    raw = code[len(PREFIX):].strip()
    try:
        data = json.loads(base64.urlsafe_b64decode(raw + '=' * (-len(raw) % 4)))
    except (ValueError, TypeError):
        raise ValueError('The mesh code is damaged')
    if not isinstance(data, dict) or data.get('v') != 1:
        raise ValueError('Unknown mesh code version')
    parts = {}
    for kind in ('halow', 'w24'):
        part = data.get(kind)
        if part is None:
            continue
        if not isinstance(part, dict):
            raise ValueError('The mesh code is damaged')
        clean = {}
        for sect, allowed in (('iface', IFACE_KEYS), ('radio', RADIO_KEYS)):
            values = part.get(sect) or {}
            if not isinstance(values, dict) or set(values) - set(allowed):
                raise ValueError('The mesh code has settings this node does not accept')
            if not all(isinstance(v, str) and VALUE_RE.match(v) for v in values.values()):
                raise ValueError('The mesh code has an invalid value')
            clean[sect] = values
        if not clean['iface'].get('mesh_id'):
            raise ValueError('The mesh code has no mesh name')
        parts[kind] = clean
    if 'halow' not in parts:
        raise ValueError('The mesh code has no HaLow mesh')
    return parts


def _state_file(state):
    return Path(state) / 'mesh-join.json'


def status(state=STATE):
    try:
        data = json.loads(_state_file(state).read_text())
    except (OSError, ValueError):
        return None
    return {k: data.get(k) for k in ('until', 'from_mesh_id', 'to_mesh_id')} if isinstance(data, dict) else None


def _save(state, data):
    path = _state_file(state)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + '.tmp')
    tmp.write_text(json.dumps(data))
    tmp.replace(path)


def join(run, code, revert_hours=None, state=STATE, wireless=None, clock=time.time):
    """Write the new mesh settings. Returns a result dict; the caller reboots when ok."""
    wireless = wireless or WIRELESS
    try:
        parts = decode(code)
    except ValueError as e:
        return {'ok': False, 'error': str(e)}
    if revert_hours is not None and (isinstance(revert_hours, bool) or not isinstance(revert_hours, (int, float))
                                     or not 0 < revert_hours <= MAX_HOURS):
        return {'ok': False, 'error': 'revert_hours must be between 0 and %d, or null for permanent' % MAX_HOURS}
    config = _read(run)
    local = meshes(config)
    if 'halow' not in local:
        return {'ok': False, 'error': 'This node has no HaLow mesh set up to change'}
    pending = status(state)
    backup = Path(state) / 'wireless.before-join'
    if pending is None:   # a second join while one is pending keeps the original backup
        backup.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(wireless, backup)
        os.chmod(backup, 0o600)
    applied, skipped = [], []
    if 'w24' in parts and 'w24' not in local:
        radio = usb_radio_24(config)
        if radio:   # a new node's 2.4 GHz dongle: give it the mesh interface first
            for opt in ('=wifi-iface', '.device=' + radio, '.mode=mesh', '.network=' + W24_NETWORK):
                run(['uci', 'set', 'wireless.%s%s' % (NEW_W24_IFACE, opt)])
            run(['uci', 'set', 'wireless.%s.disabled=0' % radio])
            config[NEW_W24_IFACE] = {'.type': 'wifi-iface', 'device': radio, 'mode': 'mesh'}
            local['w24'] = (radio, NEW_W24_IFACE)
    for kind, part in parts.items():
        if kind not in local:
            skipped.append(kind)
            continue
        radio, iface = local[kind]
        for sect, values, keys in ((iface, part['iface'], IFACE_KEYS), (radio, part['radio'], RADIO_KEYS)):
            for k, v in values.items():
                run(['uci', 'set', 'wireless.%s.%s=%s' % (sect, k, v)])
            for k in DROP_IF_ABSENT:
                if k in keys and k not in values and k in config.get(sect, {}):
                    run(['uci', '-q', 'delete', 'wireless.%s.%s' % (sect, k)])
        applied.append(kind)
    if not run(['uci', 'commit', 'wireless']).get('ok'):
        run(['uci', 'revert', 'wireless'])
        return {'ok': False, 'error': 'Could not save the wireless settings; nothing changed'}
    from_id = (pending or {}).get('from_mesh_id') or config[local['halow'][1]].get('mesh_id')
    to_id = parts['halow']['iface']['mesh_id']
    if revert_hours is None:
        if pending is not None:
            _state_file(state).unlink()
        note = 'Joined mesh %s for good. Rebooting to apply.' % to_id
        until = None
    else:
        until = int(clock() + revert_hours * 3600)
        _save(state, {'until': until, 'from_mesh_id': from_id, 'to_mesh_id': to_id})
        note = 'Joined mesh %s. It goes back to %s after %g h. Rebooting to apply.' % (to_id, from_id, revert_hours)
    return {'ok': True, 'applied': applied, 'skipped': skipped, 'until': until, 'note': note}


def cancel(state=STATE):
    """Keep the current (temporary) join for good."""
    if status(state) is None:
        return {'ok': False, 'error': 'No temporary mesh join is pending'}
    _state_file(state).unlink()
    return {'ok': True, 'note': 'This mesh is kept; it will not switch back.'}


def tick(state=STATE, wireless=None, clock=time.time, reboot=None):
    """Put the old mesh settings back once a temporary join runs out. True when it did."""
    wireless = wireless or WIRELESS
    s = status(state)
    if not s or not isinstance(s.get('until'), int) or clock() < s['until']:
        return False
    backup = Path(state) / 'wireless.before-join'
    if backup.exists():
        shutil.copy2(backup, wireless)
    _state_file(state).unlink()
    if reboot:
        reboot()
    return True
